package sk.vespal.recshot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** Stav nahrávania zvuku (null = nenahráva sa). */
data class AudioRecState(
    val startedAt: Long,          // SystemClock.elapsedRealtime pri štarte
    val pausedTotal: Long = 0,    // súčet pozastavených úsekov
    val pausedAt: Long? = null,   // kedy sa pozastavilo (null = beží)
) {
    val paused: Boolean get() = pausedAt != null
    fun elapsed(now: Long = SystemClock.elapsedRealtime()): Long =
        ((pausedAt ?: now) - startedAt - pausedTotal).coerceAtLeast(0)
}

/** Nahrávanie zvuku z mikrofónu (hlas v miestnosti) do Music/RecShotC8 ako .m4a. */
class AudioRecorderService : Service() {

    companion object {
        const val ACTION_START = "sk.vespal.recshot.AUDIO_START"
        const val ACTION_PAUSE = "sk.vespal.recshot.AUDIO_PAUSE"
        const val ACTION_RESUME = "sk.vespal.recshot.AUDIO_RESUME"
        const val ACTION_STOP = "sk.vespal.recshot.AUDIO_STOP"
        private const val CHANNEL = "audio_rec"
        private const val NOTIF_ID = 31

        val state = MutableStateFlow<AudioRecState?>(null)
        /** Hlasitosť 0..1 pre živú vlnovku. */
        val level = MutableStateFlow(0f)

        fun command(ctx: Context, action: String) {
            val i = Intent(ctx, AudioRecorderService::class.java).setAction(action)
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private val handler = Handler(Looper.getMainLooper())

    private val meter = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val st = state.value
            level.value = if (st == null || st.paused) 0f else try {
                (r.maxAmplitude / 32767f).coerceIn(0f, 1f)
            } catch (_: Exception) { 0f }
            handler.postDelayed(this, 80)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Nahrávanie zvuku", "Audio recording"), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop()
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        // foreground musí ísť hneď, inak systém službu ukončí
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (recorder != null) return
        val u = MediaStoreUtil.newAudio(this)
        if (u == null) {
            toast(tr("Nedá sa vytvoriť súbor", "Cannot create file"))
            finish()
            return
        }
        try {
            val fd = contentResolver.openFileDescriptor(u, "w") ?: throw IllegalStateException("no fd")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioChannels(1)
            r.setOutputFile(fd.fileDescriptor)
            r.prepare()
            r.start()
            recorder = r
            pfd = fd
            uri = u
            state.value = AudioRecState(startedAt = SystemClock.elapsedRealtime())
            handler.post(meter)
        } catch (e: Exception) {
            MediaStoreUtil.delete(this, u)
            toast(tr("Mikrofón nie je dostupný", "Microphone not available"))
            finish()
        }
    }

    private fun pause() {
        val st = state.value ?: return
        if (st.paused) return
        try {
            recorder?.pause()
            state.value = st.copy(pausedAt = SystemClock.elapsedRealtime())
            notifyUpdate()
        } catch (_: Exception) {
        }
    }

    private fun resume() {
        val st = state.value ?: return
        val at = st.pausedAt ?: return
        try {
            recorder?.resume()
            state.value = st.copy(pausedTotal = st.pausedTotal + (SystemClock.elapsedRealtime() - at), pausedAt = null)
            notifyUpdate()
        } catch (_: Exception) {
        }
    }

    private fun stop() {
        val r = recorder
        val u = uri
        handler.removeCallbacks(meter)
        var ok = false
        if (r != null) {
            ok = try {
                r.stop(); true
            } catch (_: Exception) {
                false // príliš krátka nahrávka
            }
            try { r.release() } catch (_: Exception) {}
        }
        try { pfd?.close() } catch (_: Exception) {}
        recorder = null
        pfd = null
        uri = null
        if (u != null) {
            if (ok) {
                MediaStoreUtil.publish(this, u)
                RecorderService.savedCounter.value = RecorderService.savedCounter.value + 1
                toast(tr("Zvuk uložený do Hudba › ${MediaStoreUtil.FOLDER}", "Audio saved to Music › ${MediaStoreUtil.FOLDER}"))
            } else {
                MediaStoreUtil.delete(this, u)
                toast(tr("Nahrávka bola príliš krátka", "Recording was too short"))
            }
        }
        finish()
    }

    private fun finish() {
        state.value = null
        level.value = 0f
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) stop()
        super.onDestroy()
    }

    private fun notifyUpdate() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(state.value?.paused == true))
    }

    private fun buildNotification(paused: Boolean): android.app.Notification {
        fun svc(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, AudioRecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 40, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(if (paused) tr("Nahrávanie zvuku pozastavené", "Audio recording paused") else tr("Nahrávam zvuk", "Recording audio"))
            .setContentText(tr("Mikrofón", "Microphone"))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                if (paused) tr("Pokračovať", "Resume") else tr("Pauza", "Pause"),
                if (paused) svc(ACTION_RESUME, 41) else svc(ACTION_PAUSE, 42)
            )
            .addAction(0, tr("Stop a uložiť", "Stop & save"), svc(ACTION_STOP, 43))
            .build()
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
