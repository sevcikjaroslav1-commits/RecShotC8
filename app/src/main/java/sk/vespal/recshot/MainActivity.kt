package sk.vespal.recshot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import sk.vespal.recshot.ui.AppRoot
import sk.vespal.recshot.ui.RecShotTheme

/** SCREENSHOT = okamžitá snímka (dlaždica), SHOT_SESSION = režim snímok (tlačidlo v aplikácii). */
enum class CaptureMode { RECORD, SCREENSHOT, SHOT_SESSION }

sealed class Screen {
    data object Home : Screen()
    data object Gallery : Screen()
    data class VideoEditor(val uri: Uri) : Screen()
    data class ImageEditor(val uri: Uri) : Screen()
}

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_QUICK_RECORD = "sk.vespal.recshot.QUICK_RECORD"
        const val ACTION_QUICK_SHOT = "sk.vespal.recshot.QUICK_SHOT"
        const val ACTION_OPEN_VIDEO = "sk.vespal.recshot.OPEN_VIDEO"
        const val ACTION_OPEN_IMAGE = "sk.vespal.recshot.OPEN_IMAGE"
        private const val KEY_PENDING = "pending_mode"
    }

    /** Jednoduchý zásobník obrazoviek (navigácia bez knižnice). */
    private val backStack = mutableStateListOf<Screen>(Screen.Home)

    private var pendingMode: CaptureMode? = null

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val mode = pendingMode ?: return@registerForActivityResult
            pendingMode = null
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                startRecorderService(mode, res.resultCode, data)
                // Aplikácia ide do pozadia, aby nebola na zázname ani na snímke.
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, tr("Zrušené", "Cancelled"), Toast.LENGTH_SHORT).show()
            }
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, tr("Bez povolenia na zvuk – video bude bez zvuku", "No audio permission – video will have no sound"), Toast.LENGTH_SHORT).show()
            }
            launchProjection(CaptureMode.RECORD)
        }

    private val overlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                launchProjection(CaptureMode.SHOT_SESSION)
            } else {
                Toast.makeText(this, tr("Bez povolenia sa plávajúce tlačidlo nedá zobraziť", "Without permission the floating button cannot be shown"), Toast.LENGTH_LONG).show()
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Lang.init(this)
        pendingMode = savedInstanceState?.getString(KEY_PENDING)?.let { CaptureMode.valueOf(it) }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            RecShotTheme {
                BackHandler(enabled = backStack.size > 1) { goBack() }
                AppRoot(
                    screen = backStack.last(),
                    onNavigate = { navigate(it) },
                    onTab = { tab ->
                        backStack.clear()
                        backStack.add(Screen.Home)
                        if (tab == Screen.Gallery) backStack.add(Screen.Gallery)
                    },
                    onBack = { goBack() },
                    onCapture = { requestCapture(it) },
                )
            }
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingMode?.let { outState.putString(KEY_PENDING, it.name) }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_QUICK_RECORD -> requestCapture(CaptureMode.RECORD)
            ACTION_QUICK_SHOT -> requestCapture(CaptureMode.SCREENSHOT)
            ACTION_OPEN_VIDEO -> intent.data?.let { navigate(Screen.VideoEditor(it)) }
            ACTION_OPEN_IMAGE -> intent.data?.let { navigate(Screen.ImageEditor(it)) }
            Intent.ACTION_EDIT -> intent.data?.let { openExternal(it, intent.type) }
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { openExternal(it, intent.type) }
            }
        }
        // aby sa akcia nezopakovala po otočení obrazovky
        intent.action = Intent.ACTION_MAIN
    }

    private fun openExternal(uri: Uri, typeHint: String?) {
        val type = typeHint ?: contentResolver.getType(uri) ?: ""
        if (type.startsWith("video")) navigate(Screen.VideoEditor(uri)) else navigate(Screen.ImageEditor(uri))
    }

    private fun navigate(s: Screen) {
        if (backStack.lastOrNull() != s) backStack.add(s)
    }

    private fun goBack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else finish()
    }

    // ------------------------------------------------------------ zachytávanie obrazovky

    fun requestCapture(mode: CaptureMode) {
        val st = RecorderService.state.value
        if (mode != CaptureMode.RECORD && (st is RecState.Recording || st == RecState.ShotSession)) {
            if (st == RecState.ShotSession && mode == CaptureMode.SHOT_SESSION) {
                Toast.makeText(this, tr("Režim snímok beží – ťukni na modré plávajúce tlačidlo", "Screenshot mode is on – tap the blue floating button"), Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
                return
            }
            moveTaskToBack(true)
            RecorderService.command(this, RecorderService.ACTION_SHOT_DURING)
            return
        }
        if (st == RecState.ShotSession) {
            Toast.makeText(this, tr("Najprv ukonči režim snímok", "Stop screenshot mode first"), Toast.LENGTH_SHORT).show()
            return
        }
        if (st != RecState.Idle) {
            Toast.makeText(this, tr("Nahrávanie už beží", "Recording is already running"), Toast.LENGTH_SHORT).show()
            return
        }
        if (mode == CaptureMode.SHOT_SESSION && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, tr("Povoľ RecC8: Zobrazovať cez iné aplikácie", "Allow RecC8: Display over other apps"), Toast.LENGTH_LONG).show()
            try {
                overlayPermission.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
            return
        }
        if (mode == CaptureMode.RECORD && Prefs.load(this).anyAudio &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchProjection(mode)
    }

    private fun launchProjection(mode: CaptureMode) {
        val mpm = getSystemService(MediaProjectionManager::class.java) ?: return
        pendingMode = mode
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // vždy celá obrazovka (nie len jedna aplikácia)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            projectionLauncher.launch(intent)
        } catch (e: Exception) {
            pendingMode = null
            Toast.makeText(this, tr("Zachytávanie obrazovky nie je dostupné", "Screen capture is not available"), Toast.LENGTH_LONG).show()
        }
    }

    private fun startRecorderService(mode: CaptureMode, resultCode: Int, data: Intent) {
        val (w, h) = screenSize()
        val i = Intent(this, RecorderService::class.java)
            .setAction(if (mode == CaptureMode.RECORD) RecorderService.ACTION_RECORD else RecorderService.ACTION_SCREENSHOT)
            .putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(RecorderService.EXTRA_DATA, data)
            .putExtra(RecorderService.EXTRA_WIDTH, w)
            .putExtra(RecorderService.EXTRA_HEIGHT, h)
            .putExtra(RecorderService.EXTRA_DPI, resources.displayMetrics.densityDpi)
            .putExtra(RecorderService.EXTRA_SESSION, mode == CaptureMode.SHOT_SESSION)
        startForegroundService(i)
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }
}
