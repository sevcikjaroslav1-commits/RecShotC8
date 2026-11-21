package sk.vespal.recshot

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Bundle
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Vlastný enkodér obrazovky: video (H.264) + zvuk (AAC) do MP4.
 * Zvuk môže byť zo systému (AudioPlaybackCapture – hry, videá, hudba), z mikrofónu, alebo oboje zmixované.
 */
class ScreenEncoder(
    private val fd: FileDescriptor,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val projection: MediaProjection,
    private val captureSystem: Boolean,
    private val captureMic: Boolean,
) {
    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE = 160_000
        private const val TIMEOUT_US = 10_000L
    }

    private val hasAudio get() = captureSystem || captureMic

    private lateinit var videoCodec: MediaCodec
    private var audioCodec: MediaCodec? = null
    private var systemRec: AudioRecord? = null
    private var micRec: AudioRecord? = null
    private lateinit var muxer: MediaMuxer

    private val muxLock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var wroteSamples = false

    @Volatile private var running = false
    @Volatile private var paused = false
    private var pauseStartUs = 0L
    @Volatile private var pausedTotalUs = 0L
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    lateinit var inputSurface: Surface
        private set

    /** Pripraví kodeky. Vráti false, ak sa video enkodér nepodarilo nastaviť. Zvuk pri chybe vypne. */
    @SuppressLint("MissingPermission")
    fun prepare(): Boolean {
        try {
            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // pri statickej obrazovke opakuj posledný snímok, aby video nemalo „diery“
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / max(1, fps))
            }
            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoCodec.createInputSurface()
        } catch (e: Exception) {
            return false
        }

        if (hasAudio) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = max(minBuf, 4096) * 4
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                if (captureSystem) {
                    val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    systemRec = AudioRecord.Builder()
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bufSize)
                        .setAudioPlaybackCaptureConfig(cfg)
                        .build()
                }
                if (captureMic) {
                    micRec = AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bufSize)
                        .build()
                }
                val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                }
                audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                    it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            } catch (e: Exception) {
                releaseAudio()
            }
        }

        return try {
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** True, ak sa zvuk podarilo pripraviť (inak sa nahráva iba obraz). */
    val audioActive: Boolean get() = audioCodec != null

    fun start() {
        running = true
        videoCodec.start()
        videoThread = Thread({ drainVideo() }, "rec-video").also { it.start() }

        val ac = audioCodec
        if (ac != null) {
            ac.start()
            systemRec?.startRecording()
            micRec?.startRecording()
            audioThread = Thread({ audioLoop(ac) }, "rec-audio").also { it.start() }
        }
    }

    fun pause() {
        if (paused) return
        pauseStartUs = nowUs()
        paused = true
        try {
            videoCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 1) })
        } catch (_: Exception) {
        }
    }

    fun resume() {
        if (!paused) return
        pausedTotalUs += nowUs() - pauseStartUs
        paused = false
        try {
            videoCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 0) })
        } catch (_: Exception) {
        }
    }

    /** Ukončí nahrávanie. Vráti true, ak vzniklo platné video. */
    fun stop(): Boolean {
        if (paused) resume()
        val wasRunning = running
        running = false
        if (::videoCodec.isInitialized) {
            if (wasRunning) {
                try { videoCodec.signalEndOfInputStream() } catch (_: Exception) {}
            }
            try { audioThread?.join(3000) } catch (_: Exception) {}
            try { videoThread?.join(3000) } catch (_: Exception) {}
            try { videoCodec.stop() } catch (_: Exception) {}
            try { videoCodec.release() } catch (_: Exception) {}
        }
        releaseAudio()
        if (!::muxer.isInitialized) return false

        var ok = false
        synchronized(muxLock) {
            try {
                if (muxerStarted && wroteSamples) {
                    muxer.stop()
                    ok = true
                }
            } catch (_: Exception) {
                ok = false
            }
            try { muxer.release() } catch (_: Exception) {}
        }
        if (::inputSurface.isInitialized) {
            try { inputSurface.release() } catch (_: Exception) {}
        }
        return ok
    }

    // ------------------------------------------------------------------ video

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try {
                videoCodec.dequeueOutputBuffer(info, TIMEOUT_US)
            } catch (e: Exception) {
                break
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxLock) {
                        videoTrack = muxer.addTrack(videoCodec.outputFormat)
                        maybeStartMuxer()
                    }
                }
                idx >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && !paused) {
                        val pts = info.presentationTimeUs - pausedTotalUs
                        if (pts > lastVideoPts) {
                            val buf = videoCodec.getOutputBuffer(idx)
                            if (buf != null) {
                                info.presentationTimeUs = pts
                                writeSample(videoTrack, buf, info)
                                lastVideoPts = pts
                            }
                        }
                    }
                    videoCodec.releaseOutputBuffer(idx, false)
                    if (eos) break
                }
                else -> if (!running && idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // po signalEndOfInputStream ešte chvíľu čakáme na EOS (join má timeout)
                }
            }
        }
    }

    // ------------------------------------------------------------------ audio

    private fun audioLoop(codec: MediaCodec) {
        val frameSamples = 1024
        val primary = systemRec ?: micRec ?: return
        val secondary = if (systemRec != null) micRec else null
        val a = ShortArray(frameSamples)
        val b = ShortArray(frameSamples)
        val bytes = ByteArray(frameSamples * 2)
        val info = MediaCodec.BufferInfo()
        val frameDurUs = frameSamples * 1_000_000L / SAMPLE_RATE

        while (running) {
            val n = primary.read(a, 0, frameSamples)
            if (n <= 0) {
                drainAudio(codec, info, false)
                try { Thread.sleep(5) } catch (_: InterruptedException) {}
                continue
            }
            if (secondary != null) {
                java.util.Arrays.fill(b, 0.toShort())
                val m = secondary.read(b, 0, n, AudioRecord.READ_NON_BLOCKING)
                if (m > 0) {
                    for (i in 0 until m) {
                        val s = a[i] + b[i]
                        a[i] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
            }
            if (!paused) {
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(a, 0, n)
                var pts = nowUs() - pausedTotalUs - frameDurUs
                if (pts <= lastAudioPts) pts = lastAudioPts + 1
                lastAudioPts = pts
                queueAudio(codec, bytes, n * 2, pts, false)
            }
            drainAudio(codec, info, false)
        }
        // koniec streamu
        queueAudio(codec, bytes, 0, max(lastAudioPts + 1, 0), true)
        var guard = 0
        while (guard < 200 && !drainAudio(codec, info, true)) guard++
    }

    private fun queueAudio(codec: MediaCodec, data: ByteArray, len: Int, ptsUs: Long, eos: Boolean) {
        try {
            val idx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (idx < 0) return
            val buf = codec.getInputBuffer(idx) ?: return
            buf.clear()
            val size = minOf(len, buf.remaining())
            buf.put(data, 0, size)
            codec.queueInputBuffer(
                idx, 0, size, ptsUs,
                if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            )
        } catch (_: Exception) {
        }
    }

    /** Vráti true po prijatí EOS. */
    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo, waitForEos: Boolean): Boolean {
        while (true) {
            val idx = try {
                codec.dequeueOutputBuffer(info, if (waitForEos) TIMEOUT_US else 0)
            } catch (e: Exception) {
                return true
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                    audioTrack = muxer.addTrack(codec.outputFormat)
                    maybeStartMuxer()
                }
                idx >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        codec.getOutputBuffer(idx)?.let { writeSample(audioTrack, it, info) }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) return true
                }
            }
        }
    }

    // ------------------------------------------------------------------ muxer

    private fun maybeStartMuxer() {
        val audioReady = audioCodec == null || audioTrack >= 0
        if (!muxerStarted && videoTrack >= 0 && audioReady) {
            muxer.start()
            muxerStarted = true
        }
    }

    private fun writeSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxLock) {
            if (!muxerStarted || track < 0) return
            try {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                muxer.writeSampleData(track, buf, info)
                wroteSamples = true
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseAudio() {
        try { systemRec?.stop() } catch (_: Exception) {}
        try { systemRec?.release() } catch (_: Exception) {}
        systemRec = null
        try { micRec?.stop() } catch (_: Exception) {}
        try { micRec?.release() } catch (_: Exception) {}
        micRec = null
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null
    }

    private fun nowUs() = System.nanoTime() / 1000
}
