package sk.vespal.recshot.ui

import sk.vespal.recshot.tr
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sk.vespal.recshot.AudioRecState
import sk.vespal.recshot.AudioRecorderService
import sk.vespal.recshot.CaptureMode
import sk.vespal.recshot.GalleryItem
import sk.vespal.recshot.MediaStoreUtil
import sk.vespal.recshot.Prefs
import sk.vespal.recshot.RecSettings
import sk.vespal.recshot.RecState
import sk.vespal.recshot.RecorderService

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onCapture: (CaptureMode) -> Unit,
    onOpen: (GalleryItem) -> Unit,
    onOpenUri: (Uri, Boolean) -> Unit,
    onShowAll: () -> Unit,
) {
    val ctx = LocalContext.current
    val rec by RecorderService.state.collectAsState()
    val saved by RecorderService.savedCounter.collectAsState()
    var settings by remember { mutableStateOf(Prefs.load(ctx)) }
    var recent by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }

    fun update(s: RecSettings) {
        settings = s
        Prefs.save(ctx, s)
    }

    LaunchedEffect(saved, rec) {
        recent = withContext(Dispatchers.IO) { MediaStoreUtil.list(ctx).take(6) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val type = ctx.contentResolver.getType(uri) ?: ""
            onOpenUri(uri, type.startsWith("video"))
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // hlavička
        Row(Modifier.padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(C.Accent),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White))
            }
            Spacer(Modifier.width(10.dp))
            Text("RecC8", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            LangSwitch()
        }

        // hlavné tlačidlá: červené (obrazovka) · fialové (zvuk) · modré (snímka)
        val recording = rec as? RecState.Recording
        val audio by AudioRecorderService.state.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (recording == null) {
                BigTile(
                    title = if (rec == RecState.Starting) tr("Spúšťam…", "Starting…") else tr("Nahrať", "Record"),
                    subtitle = tr("obrazovku", "screen"),
                    icon = Icons.Filled.FiberManualRecord,
                    modifier = Modifier.weight(1f),
                ) { onCapture(CaptureMode.RECORD) }
            } else {
                BigTile(
                    title = "Stop",
                    subtitle = if (recording.paused) tr("pozastavené", "paused") else tr("uložiť video", "save video"),
                    icon = Icons.Filled.Stop,
                    modifier = Modifier.weight(1f),
                ) { RecorderService.command(ctx, RecorderService.ACTION_STOP) }
            }
            BigTile(
                title = if (audio == null) tr("Nahrať", "Record") else "Stop",
                subtitle = if (audio == null) tr("zvuk", "audio") else tr("uložiť zvuk", "save audio"),
                icon = if (audio == null) Icons.Filled.Mic else Icons.Filled.Stop,
                color = C.PurpleFill,
                modifier = Modifier.weight(1f),
            ) { onCapture(CaptureMode.AUDIO) }
            BigTile(
                title = tr("Snímka", "Screenshot"),
                subtitle = if (rec == RecState.ShotSession) tr("režim zapnutý", "mode on") else tr("obrazovky", "screen"),
                icon = Icons.Filled.Screenshot,
                color = C.BlueFill,
                modifier = Modifier.weight(1f),
            ) { onCapture(CaptureMode.SHOT_SESSION) }
        }

        audio?.let { a ->
            Spacer(Modifier.height(12.dp))
            AudioBar(a)
        }
        if (recording != null) {
            Spacer(Modifier.height(12.dp))
            RecordingBar(recording)
        }
        if (rec == RecState.ShotSession) {
            Spacer(Modifier.height(12.dp))
            ShotSessionBar()
        }

        // import z galérie telefónu
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF132036))
                .border(1.dp, Color(0xFF24406A), RoundedCornerShape(18.dp))
                .clickable {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1D3352)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.FileDownload, contentDescription = null, tint = C.Blue, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Text(
                tr("Importovať z galérie telefónu", "Import from phone gallery"), color = Color(0xFFB9D3FF), fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color(0xFFB9D3FF))
        }

        // rýchle nastavenia (ťuknutím sa prepínajú) – farebné dlaždice 2×2
        Spacer(Modifier.height(12.dp))
        val resLabel = if (settings.resolution == 0) tr("Natívne", "Native") else "${settings.resolution}p"
        val audioLabel = when (settings.audio) {
            1 -> tr("Mikrofón", "Microphone")
            2 -> tr("Systém", "System")
            3 -> tr("Systém + mikrofón", "System + mic")
            else -> tr("Bez zvuku", "No sound")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingTile(tr("Rozlíšenie", "Resolution"), resLabel, Icons.Filled.Hd, TileViolet, Modifier.weight(1f)) {
                val next = when (settings.resolution) { 720 -> 1080; 1080 -> 1440; 1440 -> 0; else -> 720 }
                update(settings.copy(resolution = next))
            }
            SettingTile(tr("Snímky/s", "Frame rate"), "${settings.fps} FPS", Icons.Filled.Speed, TileTeal, Modifier.weight(1f)) {
                val next = when (settings.fps) { 24 -> 30; 30 -> 60; else -> 24 }
                update(settings.copy(fps = next))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingTile(
                tr("Zvuk", "Sound"), audioLabel,
                if (settings.anyAudio) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                TileAmber, Modifier.weight(1f)
            ) {
                val next = when (settings.audio) { 2 -> 3; 3 -> 1; 1 -> 0; else -> 2 }
                update(settings.copy(audio = next))
            }
            SettingTile(
                tr("Odpočet", "Countdown"), if (settings.countdown == 0) tr("Vypnutý", "Off") else if (settings.countdown == 5) tr("5 sekúnd", "5 seconds") else tr("${settings.countdown} sekundy", "${settings.countdown} seconds"),
                Icons.Filled.Timer, TilePink, Modifier.weight(1f)
            ) {
                val next = when (settings.countdown) { 0 -> 3; 3 -> 5; else -> 0 }
                update(settings.copy(countdown = next))
            }
        }

        // posledné
        Row(
            Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("Posledné", "Recent"), color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                tr("Zobraziť všetko", "Show all"), color = C.AccentText, fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onShowAll).padding(8.dp)
            )
        }
        if (recent.isEmpty()) {
            Text(
                tr("Zatiaľ žiadne nahrávky. Ťukni na Nahrať alebo Snímka.", "No recordings yet. Tap Record or Screenshot."),
                color = C.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 24.dp)
            )
        } else {
            recent.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { item ->
                        Thumb(item, Modifier.weight(1f).aspectRatio(0.66f)) { onOpen(item) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RecordingBar(s: RecState.Recording) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (s.paused) C.TextDim else C.Accent))
        Spacer(Modifier.width(10.dp))
        Text(
            if (s.paused) tr("Pozastavené", "Paused") else tr("Nahráva sa  ", "Recording  ") + formatDuration(now - s.startedAt),
            color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
        )
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(C.SurfaceHi)
                .clickable {
                    RecorderService.command(
                        ctx,
                        if (s.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (s.paused) tr("Pokračovať", "Resume") else tr("Pauza", "Pause"),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun BigTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    color: Color = C.Accent,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .height(104.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Color(0xE6FFFFFF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Fialový pruh počas nahrávania zvuku: čas, živá vlnovka, Pauza a Stop. */
@Composable
private fun AudioBar(s: AudioRecState) {
    val ctx = LocalContext.current
    val bars = remember { mutableStateListOf<Float>().apply { repeat(40) { add(0f) } } }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            bars.removeAt(0)
            bars.add(AudioRecorderService.level.value)
            delay(100)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(C.PurpleSoft)
            .border(1.dp, C.PurpleBorder, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (s.paused) C.TextDim else Color(0xFFFF5A5A)))
            Spacer(Modifier.width(10.dp))
            Text(
                if (s.paused) tr("Pozastavené · mikrofón", "Paused · microphone") else tr("Nahrávam zvuk · mikrofón", "Recording audio · microphone"),
                color = Color(0xFFD7C8FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            Text(formatDuration(s.elapsed(now)), color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bars.forEach { v ->
                val h = (4f + 32f * kotlin.math.sqrt(v)).dp
                Box(Modifier.weight(1f).height(h).clip(RoundedCornerShape(2.dp)).background(C.Purple))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A2244))
                    .clickable {
                        AudioRecorderService.command(
                            ctx, if (s.paused) AudioRecorderService.ACTION_RESUME else AudioRecorderService.ACTION_PAUSE
                        )
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null, tint = Color(0xFFD7C8FF), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (s.paused) tr("Pokračovať", "Resume") else tr("Pauza", "Pause"), color = Color(0xFFD7C8FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(C.PurpleFill)
                    .clickable { AudioRecorderService.command(ctx, AudioRecorderService.ACTION_STOP) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Stop a uložiť", "Stop & save"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ShotSessionBar() {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF132036))
            .border(1.dp, Color(0xFF24406A), RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr("Režim snímok je zapnutý", "Screenshot mode is on"), color = Color(0xFFB9D3FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                tr("Prejdi do inej aplikácie a ťukni na modré plávajúce tlačidlo.", "Go to another app and tap the blue floating button."),
                color = C.TextDim, fontSize = 12.sp, lineHeight = 16.sp
            )
        }
        Text(
            tr("Ukončiť", "End"), color = C.AccentText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { RecorderService.command(ctx, RecorderService.ACTION_STOP) }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(C.Surface)
            .border(1.dp, C.Border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xFFE3E5E9), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private class TileColors(val bg: Color, val border: Color, val iconBg: Color, val fg: Color)

private val TileViolet = TileColors(Color(0xFF1C1929), Color(0xFF2F2950), Color(0xFF2A2447), Color(0xFFC9B8FF))
private val TileTeal = TileColors(Color(0xFF142425), Color(0xFF21403F), Color(0xFF1C3636), Color(0xFF8FE0D2))
private val TileAmber = TileColors(Color(0xFF251F14), Color(0xFF43361C), Color(0xFF3A2F18), Color(0xFFFFD27A))
private val TilePink = TileColors(Color(0xFF261823), Color(0xFF45293C), Color(0xFF3A2234), Color(0xFFFFA3C8))

@Composable
private fun SettingTile(
    label: String,
    value: String,
    icon: ImageVector,
    colors: TileColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.bg)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(colors.iconBg),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = colors.fg, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = C.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(
                value, color = colors.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
