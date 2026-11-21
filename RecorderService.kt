package sk.vespal.recshot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed class RecState {
    data object Idle : RecState()
    data object Starting : RecState()
    data class Recording(val paused: Boolean, val startedAt: Long) : RecState()
    data object TakingShot : RecState()
    /** Režim snímok: aplikácia je skrytá, snímka sa robí zatrasením alebo z notifikácie. */
    data object ShotSession : RecState()
}

/**
 * Služba v popredí, ktorá drží MediaProjection.
 * - nahráva celú obrazovku do MP4 (vlastný enkodér, zvuk zo systému aj mikrofónu) – bez prekrytia,
 * - robí snímky obrazovky (ImageReader),
 * - ovláda sa cez notifikáciu, dlaždice v rýchlych nastaveniach alebo zatrasením.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_RECORD = "sk.vespal.recshot.action.RECORD"
        const val ACTION_SCREENSHOT = "sk.vespal.recshot.action.SCREENSHOT"
        const val ACTION_STOP = "sk.vespal.recshot.action.STOP"
        const val ACTION_PAUSE = "sk.vespal.recshot.action.PAUSE"
        const val ACTION_RESUME = "sk.vespal.recshot.action.RESUME"
        const val ACTION_SHOT_DURING = "sk.vespal.recshot.action.SHOT_DURING"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_WIDTH = "w"
        const val EXTRA_HEIGHT = "h"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_SESSION = "session"

        private const val CHANNEL_REC = "recording"
        private const val CHANNEL_DONE = "done"
        private const val NOTIF_ONGOING = 42
        private const val NOTIF_DONE_VIDEO = 43
        private const val NOTIF_DONE_SHOT = 44

        private val _state = MutableStateFlow<RecState>(RecState.Idle)
        val state: StateFlow<RecState> = _state.asStateFlow()

        /** Zvýši sa po uložení nového súboru – galéria sa podľa toho obnoví. */
        val savedCounter = MutableStateFlow(0)

        fun command(ctx: Context, action: String) {
            val i = Intent(ctx, RecorderService::class.java).setAction(action)
            try {
                ctx.startService(i)
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: ScreenEncoder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    private var shake: ShakeDetector? = null

    private var screenW = 1080
    private var screenH = 2400
    private var dpi = 420
    private var recW = 0
    private var recH = 0
    private var stopping = false
    private var withMic = false
    private var withSystem = false
    private var shotReader: ImageReader? = null
    private var floating: FloatingShotButton? = null
    private var shotBusy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }
        val action = intent.action
        val needsSession = action == ACTION_PAUSE || action == ACTION_RESUME || action == ACTION_SHOT_DURING
        if (needsSession && projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_RECORD -> startCapture(intent, record = true)
            ACTION_SCREENSHOT -> startCapture(intent, record = false)
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_SHOT_DURING -> handler.postDelayed({
                if (recorder != null) shotDuringRecording() else sessionShot()
            }, 600)
            else -> if (projection == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (recorder != null) stopRecording() else releaseAll()
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- štart

    private fun startCapture(intent: Intent, record: Boolean) {
        if (projection != null) return // už beží
        stopping = false
        createChannels()

        val settings = Prefs.load(this)
        val audioPerm = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        withMic = record && settings.mic && audioPerm
        withSystem = record && settings.system && audioPerm

        val type = if (withMic) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        val notif = if (record) ongoingNotification(starting = true, paused = false) else shotNotification()
        try {
            ServiceCompat.startForeground(this, NOTIF_ONGOING, notif, type)
        } catch (e: Exception) {
            toast("Nepodarilo sa spustiť službu: ${e.message}")
            stopSelf()
            return
        }

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        screenW = intent.getIntExtra(EXTRA_WIDTH, screenW)
        screenH = intent.getIntExtra(EXTRA_HEIGHT, screenH)
        dpi = intent.getIntExtra(EXTRA_DPI, dpi)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            if (data != null) mpm?.getMediaProjection(code, data) else null
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            toast("Prístup k obrazovke nebol povolený")
            releaseAll()
            return
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Používateľ zastavil zdieľanie cez systém (napr. čip v stavovej lište).
                handler.post {
                    if (!stopping) {
                        if (recorder != null) stopRecording() else releaseAll()
                    }
                }
            }
        }, handler)

        if (record) {
            _state.value = RecState.Starting
            val delayMs = max(1, settings.countdown) * 1000L
            if (settings.countdown > 0) toast("Nahrávanie začne o ${settings.countdown} s")
            handler.postDelayed({ beginRecording(settings) }, delayMs)
        } else if (intent.getBooleanExtra(EXTRA_SESSION, false)) {
            handler.postDelayed({ startShotSession() }, 500)
        } else {
            _state.value = RecState.TakingShot
            // počkáme, kým zmizne systémový dialóg a naša aplikácia
            handler.postDelayed({ takeStandaloneShot() }, 900)
        }
    }

    // ---------------------------------------------------------------- nahrávanie

    private fun beginRecording(settings: RecSettings) {
        val p = projection ?: return
        if (stopping) return
        val (w, h) = computeVideoSize(settings.resolution)
        val uri = MediaStoreUtil.newVideo(this)
        if (uri == null) {
            toast("Nedá sa vytvoriť súbor pre video")
            releaseAll()
            return
        }
        val fd = try {
            contentResolver.openFileDescriptor(uri, "rw")
        } catch (e: Exception) {
            null
        }
        if (fd == null) {
            MediaStoreUtil.delete(this, uri)
            toast("Nedá sa otvoriť súbor pre video")
            releaseAll()
            return
        }

        val enc = ScreenEncoder(
            fd = fd.fileDescriptor,
            width = w,
            height = h,
            fps = settings.fps,
            bitrate = bitrateFor(w, h, settings.fps),
            projection = p,
            captureSystem = withSystem,
            captureMic = withMic,
        )
        try {
            if (!enc.prepare()) throw IllegalStateException("enkodér nie je dostupný")
            virtualDisplay = p.createVirtualDisplay(
                "RecShotC8",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                enc.inputSurface, null, handler
            )
            enc.start()
        } catch (e: Exception) {
            enc.stop()
            virtualDisplay?.release()
            virtualDisplay = null
            try { fd.close() } catch (_: Exception) {}
            MediaStoreUtil.delete(this, uri)
            toast("Nahrávanie sa nepodarilo spustiť: ${e.message}")
            releaseAll()
            return
        }
        if ((withSystem || withMic) && !enc.audioActive) {
            toast("Zvuk sa nepodarilo zapnúť – nahrávam iba obraz")
        }

        recorder = enc
        pfd = fd
        videoUri = uri
        recW = w
        recH = h
        _state.value = RecState.Recording(paused = false, startedAt = System.currentTimeMillis())
        updateOngoing()

        if (settings.shakeToStop) {
            shake = ShakeDetector(this) { stopRecording() }.also { it.start() }
        }
    }

    private fun pauseRecording() {
        val r = recorder ?: return
        val s = _state.value as? RecState.Recording ?: return
        if (s.paused) return
        try {
            r.pause()
            _state.value = s.copy(paused = true)
            updateOngoing()
        } catch (_: Exception) {
        }
    }

    private fun resumeRecording() {
        val r = recorder ?: return
        val s = _state.value as? RecState.Recording ?: return
        if (!s.paused) return
        try {
            r.resume()
            _state.value = s.copy(paused = false)
            updateOngoing()
        } catch (_: Exception) {
        }
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        shake?.stop()
        shake = null

        val enc = recorder
        recorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        val fd = pfd
        pfd = null
        val uri = videoUri
        videoUri = null
        _state.value = RecState.Starting // „ukladám…“

        // Dokončenie enkodéra môže chvíľu trvať – mimo hlavného vlákna.
        io.execute {
            val ok = enc?.stop() ?: false
            try { fd?.close() } catch (_: Exception) {}
            handler.post {
                if (uri != null) {
                    if (ok) {
                        MediaStoreUtil.publish(this, uri)
                        savedCounter.value = savedCounter.value + 1
                        notifyDone(uri, isVideo = true)
                        toast("Video uložené")
                    } else {
                        MediaStoreUtil.delete(this, uri)
                        toast("Video je príliš krátke, neuložilo sa")
                    }
                }
                releaseAll()
            }
        }
    }

    // ---------------------------------------------------------------- snímky

    /** Snímka počas nahrávania: na chvíľu presmerujeme obraz z enkodéra do ImageReadera. */
    private fun shotDuringRecording() {
        val vd = virtualDisplay ?: return
        val r = recorder ?: return
        val w = recW
        val h = recH
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var done = false

        fun restore() {
            if (recorder != null && virtualDisplay === vd) {
                try { vd.surface = r.inputSurface } catch (_: Exception) {}
            }
            handler.post { try { reader.close() } catch (_: Exception) {} }
        }

        reader.setOnImageAvailableListener({ rd ->
            if (done) return@setOnImageAvailableListener
            val img = try { rd.acquireLatestImage() } catch (_: Exception) { null }
                ?: return@setOnImageAvailableListener
            done = true
            val bmp = imageToBitmap(img, w, h)
            img.close()
            restore()
            if (bmp != null) saveShot(bmp) else toast("Snímka sa nepodarila")
        }, handler)

        try {
            vd.surface = reader.surface
        } catch (e: Exception) {
            done = true
            restore()
            toast("Snímka sa nepodarila")
            return
        }
        handler.postDelayed({
            if (!done) {
                done = true
                restore()
                toast("Snímka sa nepodarila (obrazovka sa nezmenila)")
            }
        }, 1500)
    }

    // ---------------------------------------------------------------- režim snímok

    /** Spustí režim snímok: virtuálny displej čaká bez obrazu, kým si používateľ nevyžiada snímku. */
    private fun startShotSession() {
        val p = projection ?: return releaseAll()
        if (stopping) return
        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        try {
            virtualDisplay = p.createVirtualDisplay(
                "RecShotC8Shots", screenW, screenH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null, null, handler
            )
        } catch (e: Exception) {
            reader.close()
            toast("Režim snímok sa nepodarilo spustiť: ${e.message}")
            releaseAll()
            return
        }
        shotReader = reader
        _state.value = RecState.ShotSession
        val nm = getSystemService(NotificationManager::class.java)
        try {
            nm?.notify(NOTIF_ONGOING, sessionNotification())
        } catch (_: SecurityException) {
        }
        val fb = FloatingShotButton(
            this, screenW, screenH,
            onShot = { sessionShot() },
            onClose = { stopRecording() },
        )
        if (fb.show()) {
            floating = fb
            toast("Ťukni na modré tlačidlo a obrazovka sa odfotí")
        } else {
            toast("Povoľ „Zobrazovať cez iné aplikácie“ pre RecShotC8")
            releaseAll()
        }
    }

    /** Jedna snímka v režime snímok – obraz sa na chvíľu pustí do ImageReadera. */
    private fun sessionShot() {
        if (shotBusy) return
        val vd = virtualDisplay ?: return
        val reader = shotReader ?: return
        if (_state.value != RecState.ShotSession) return
        shotBusy = true
        val w = screenW
        val h = screenH
        var done = false

        try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}

        fun finish(ok: Boolean) {
            try { vd.surface = null } catch (_: Exception) {}
            shotBusy = false
            floating?.restore(ok)
        }

        reader.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (_: Exception) { null }
                ?: return@setOnImageAvailableListener
            if (done) {
                img.close()
                return@setOnImageAvailableListener
            }
            done = true
            val bmp = imageToBitmap(img, w, h)
            img.close()
            finish(bmp != null)
            if (bmp != null) saveShot(bmp) else toast("Snímka sa nepodarila")
        }, handler)

        // najprv skryť plávajúce tlačidlo, aby nebolo na snímke
        floating?.hideForCapture()
        handler.postDelayed({
            if (done) return@postDelayed
            try {
                vd.surface = reader.surface
            } catch (e: Exception) {
                done = true
                finish(false)
                toast("Snímka sa nepodarila")
                return@postDelayed
            }
            handler.postDelayed({
                if (!done) {
                    done = true
                    finish(false)
                    toast("Snímka sa nepodarila")
                }
            }, 2000)
        }, 180)
    }

    /** Samostatná snímka (bez nahrávania) v plnom rozlíšení displeja. */
    private fun takeStandaloneShot() {
        val p = projection ?: return releaseAll()
        val w = screenW
        val h = screenH
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var done = false

        reader.setOnImageAvailableListener({ rd ->
            if (done) return@setOnImageAvailableListener
            val img = try { rd.acquireLatestImage() } catch (_: Exception) { null }
                ?: return@setOnImageAvailableListener
            done = true
            val bmp = imageToBitmap(img, w, h)
            img.close()
            if (bmp != null) saveShot(bmp) else toast("Snímka sa nepodarila")
            handler.post {
                try { reader.close() } catch (_: Exception) {}
                releaseAll()
            }
        }, handler)

        try {
            virtualDisplay = p.createVirtualDisplay(
                "RecShotC8Shot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        } catch (e: Exception) {
            toast("Snímka sa nepodarila: ${e.message}")
            reader.close()
            releaseAll()
            return
        }
        handler.postDelayed({
            if (!done) {
                done = true
                toast("Snímka sa nepodarila")
                try { reader.close() } catch (_: Exception) {}
                releaseAll()
            }
        }, 3000)
    }

    private fun imageToBitmap(img: Image, w: Int, h: Int): Bitmap? = try {
        val plane = img.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        if (full.width == w) full else Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() }
    } catch (e: Exception) {
        null
    }

    private fun saveShot(bmp: Bitmap) {
        io.execute {
            val uri = MediaStoreUtil.saveBitmap(this, bmp)
            bmp.recycle()
            handler.post {
                if (uri != null) {
                    savedCounter.value = savedCounter.value + 1
                    notifyDone(uri, isVideo = false)
                    toast("Snímka uložená")
                } else {
                    toast("Snímku sa nepodarilo uložiť")
                }
            }
        }
    }

    // ---------------------------------------------------------------- upratanie

    private fun releaseAll() {
        stopping = true
        floating?.remove()
        floating = null
        try { shotReader?.close() } catch (_: Exception) {}
        shotReader = null
        shotBusy = false
        shake?.stop()
        shake = null
        val enc = recorder
        recorder = null
        if (enc != null) {
            try { enc.stop() } catch (_: Exception) {}
        }
        virtualDisplay?.release()
        virtualDisplay = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) {}
        _state.value = RecState.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- pomocné

    private fun computeVideoSize(resolution: Int): Pair<Int, Int> {
        val shortSide = min(screenW, screenH)
        val scale = if (resolution <= 0 || resolution >= shortSide) 1f else resolution.toFloat() / shortSide
        var w = even((screenW * scale).roundToInt())
        var h = even((screenH * scale).roundToInt())

        val caps = avcCaps()
        if (caps != null) {
            if (!caps.isSizeSupported(w, h)) {
                w = align16(w)
                h = align16(h)
            }
            var guard = 0
            while (!caps.isSizeSupported(w, h) && guard < 20 && w > 320 && h > 320) {
                w = align16((w * 0.9f).roundToInt())
                h = align16((h * 0.9f).roundToInt())
                guard++
            }
        }
        return w to h
    }

    private fun avcCaps(): MediaCodecInfo.VideoCapabilities? = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } }
            .sortedByDescending { it.isHardwareAccelerated }
            .firstOrNull()
            ?.getCapabilitiesForType("video/avc")
            ?.videoCapabilities
    } catch (e: Exception) {
        null
    }

    private fun even(v: Int) = max(2, v and 1.inv())
    private fun align16(v: Int) = max(16, (v / 16) * 16)

    private fun bitrateFor(w: Int, h: Int, fps: Int): Int {
        val raw = (w.toLong() * h * fps / 10).toInt()
        return raw.coerceIn(2_000_000, 24_000_000)
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- notifikácie

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REC, "Nahrávanie", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ovládanie nahrávania obrazovky"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Uložené súbory", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Upozornenie na uložené video alebo snímku"
            }
        )
    }

    private fun servicePi(action: String, req: Int): PendingIntent =
        PendingIntent.getService(
            this, req,
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun openAppPi(): PendingIntent =
        PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun ongoingNotification(starting: Boolean, paused: Boolean): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_REC)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppPi())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (starting) {
            b.setContentTitle("Pripravujem nahrávanie…")
            return b.build()
        }

        val shotPi = shotTrampolinePi()

        b.setContentTitle(if (paused) "Nahrávanie pozastavené" else "Nahráva sa obrazovka")
            .setContentText(if (Prefs.load(this).shakeToStop) "Zastavíš aj zatrasením telefónu" else "Ťukni na Stop pre uloženie")
        if (!paused) {
            val s = _state.value as? RecState.Recording
            b.setUsesChronometer(true).setWhen(s?.startedAt ?: System.currentTimeMillis()).setShowWhen(true)
            b.addAction(R.drawable.ic_pause, "Pauza", servicePi(ACTION_PAUSE, 1))
        } else {
            b.addAction(R.drawable.ic_play, "Pokračovať", servicePi(ACTION_RESUME, 2))
        }
        b.addAction(R.drawable.ic_shot, "Snímka", shotPi)
        b.addAction(R.drawable.ic_stop, "Stop", servicePi(ACTION_STOP, 3))
        return b.build()
    }

    private fun shotTrampolinePi(): PendingIntent =
        PendingIntent.getActivity(
            this, 20,
            Intent(this, ShotTrampolineActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun sessionNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_REC)
            .setSmallIcon(R.drawable.ic_shot)
            .setContentTitle("Režim snímok je zapnutý")
            .setContentText("Ťukni na modré plávajúce tlačidlo")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stop, "Ukončiť", servicePi(ACTION_STOP, 3))
            .build()

    private fun shotNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_REC)
            .setSmallIcon(R.drawable.ic_shot)
            .setContentTitle("Robím snímku obrazovky…")
            .setSilent(true)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun updateOngoing() {
        val s = _state.value as? RecState.Recording ?: return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIF_ONGOING, ongoingNotification(starting = false, paused = s.paused))
        } catch (_: SecurityException) {
        }
    }

    private fun notifyDone(uri: Uri, isVideo: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val open = Intent(this, MainActivity::class.java)
            .setAction(if (isVideo) MainActivity.ACTION_OPEN_VIDEO else MainActivity.ACTION_OPEN_IMAGE)
            .setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, if (isVideo) 30 else 31, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(if (isVideo) R.drawable.ic_stat_rec else R.drawable.ic_shot)
            .setContentTitle(if (isVideo) "Video uložené" else "Snímka uložená")
            .setContentText("Ťukni pre úpravu")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify(if (isVideo) NOTIF_DONE_VIDEO else NOTIF_DONE_SHOT, n)
        } catch (_: SecurityException) {
        }
    }
}
