package sk.vespal.recshot.ui

import sk.vespal.recshot.tr
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Slider
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop as CropEffect
import androidx.media3.effect.OverlayEffect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.Crop
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sk.vespal.recshot.MediaStoreUtil
import sk.vespal.recshot.RecorderService
import java.io.File
import kotlin.math.max

private enum class VTool(private val sk: String, private val en: String) {
    CUT("Strih", "Trim"), CROP("Orezať", "Crop"), SPEED("Rýchlosť", "Speed"),
    TEXT("Text", "Text"), MUSIC("Hudba", "Music"), AUDIO("Zvuk", "Sound");

    val label: String get() = tr(sk, en)
}

private val speeds = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f)
private val textColors = listOf(Color.White, Color(0xFFFFC24A), Color(0xFFE8462A), Color(0xFF6EA8FF), Color.Black)

/** Všetky úpravy, ktoré sa použijú pri exporte. */
private data class EditParams(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val cutMiddle: Boolean,
    val removeAudio: Boolean,
    /** Hlasitosť pôvodného zvuku 0..2 (1 = originál). */
    val originalVolume: Float,
    /** Hlasitosť hudby 0..2. */
    val musicVolume: Float,
    val speed: Float,
    val text: String,
    val textPos: Int, // 0 hore, 1 stred, 2 dole
    val textColor: Int,
    val videoHeight: Int,
    val musicUri: Uri?,
    /** Orezanie obrazu v zlomkoch 0..1 (ľavo, hore, vpravo, dole). */
    val crop: Rect,
)

private class VideoInfo(val durationMs: Long, val width: Int, val height: Int, val frames: List<ImageBitmap>)

@OptIn(UnstableApi::class)
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen(uri: Uri, onBack: () -> Unit, onOpenGallery: () -> Unit) {
    val ctx = LocalContext.current

    val player = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var durationMs by remember { mutableLongStateOf(0L) }
    var videoHeight by remember { mutableIntStateOf(1920) }
    var videoWidth by remember { mutableIntStateOf(1080) }
    var crop by remember { mutableStateOf(FULL_CROP) }
    var thumbs by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var range by remember { mutableStateOf(0f..1f) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }

    var tool by remember { mutableStateOf(VTool.CUT) }
    var cutMiddle by remember { mutableStateOf(false) }
    var originalVolume by remember { mutableFloatStateOf(1f) }
    var musicVolume by remember { mutableFloatStateOf(1f) }
    val removeAudio = originalVolume < 0.01f
    var playheadMs by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var text by remember { mutableStateOf("") }
    var textPos by remember { mutableIntStateOf(2) }
    var textColor by remember { mutableStateOf(Color.White) }
    var musicUri by remember { mutableStateOf<Uri?>(null) }
    var musicName by remember { mutableStateOf("") }

    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }
    var showNearby by remember { mutableStateOf(false) }
    var fileSize by remember { mutableLongStateOf(0L) }
    LaunchedEffect(uri) { fileSize = withContext(Dispatchers.IO) { fileSizeOf(ctx, uri) } }
    var showCompress by remember { mutableStateOf(false) }
    if (showNearby) NearbyDialog(listOf(uri), "video/*") { showNearby = false }
    if (showCompress) CompressDialog(uri, onDismiss = { showCompress = false })

    // náhľad: true = prehráva sa celé pôvodné video (počas ťahania úchytov),
    // false = prehráva sa presne výsledok strihu (zostrihané úseky za sebou)
    var scrubbing by remember { mutableStateOf(false) }
    var segments by remember { mutableStateOf<List<LongRange>>(emptyList()) }

    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) {
            musicUri = picked
            musicName = displayName(ctx, picked) ?: tr("Vybraná hudba", "Selected music")
        }
    }

    LaunchedEffect(uri) {
        val info = withContext(Dispatchers.IO) { loadInfo(ctx, uri, 8) }
        durationMs = info.durationMs
        videoHeight = info.height
        videoWidth = info.width
        thumbs = info.frames
    }

    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    // pozícia, prehrávanie len vo výbere, priebeh exportu
    val startMs = (range.start * durationMs).toLong()
    val endMs = (range.endInclusive * durationMs).toLong()

    /** Úseky, ktoré ostanú vo výslednom videu. */
    fun keptSegments(): List<LongRange> {
        if (durationMs <= 0) return emptyList()
        return if (!cutMiddle) {
            listOf(startMs until endMs)
        } else {
            buildList {
                if (startMs > 200) add(0L until startMs)
                if (endMs < durationMs - 200) add(endMs until durationMs)
            }
        }
    }

    /** Nahrá do prehrávača presne výsledok strihu a nastaví sa na jeho začiatok. */
    fun showResult() {
        val segs = keptSegments()
        segments = segs
        scrubbing = false
        if (segs.isEmpty()) return
        val items = segs.map { r ->
            val c = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(r.first)
            if (r.last + 1 < durationMs) c.setEndPositionMs(r.last + 1)
            MediaItem.Builder().setUri(uri).setClippingConfiguration(c.build()).build()
        }
        player.pause()
        player.setMediaItems(items)
        player.prepare()
        player.seekTo(0, 0L)
    }

    /** Počas ťahania úchytov zobrazuje celé pôvodné video, aby bolo vidno presné miesto strihu. */
    fun showFull(atMs: Long) {
        if (!scrubbing) {
            scrubbing = true
            player.pause()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
        player.seekTo(atMs)
    }

    // po načítaní dĺžky a pri zmene režimu strihu ukáž výsledok
    LaunchedEffect(durationMs, cutMiddle) {
        if (durationMs > 0) showResult()
    }
    LaunchedEffect(originalVolume) { player.volume = originalVolume.coerceIn(0f, 1f) }

    // pozícia vo výslednom videu + priebeh exportu
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            positionMs = if (scrubbing) {
                player.currentPosition
            } else {
                val idx = player.currentMediaItemIndex
                val before = segments.take(idx).sumOf { it.last + 1 - it.first }
                before + player.currentPosition
            }
            // zelená čiara: miesto v pôvodnom videu
            playheadMs = if (scrubbing) {
                player.currentPosition
            } else {
                (segments.getOrNull(player.currentMediaItemIndex)?.first ?: 0L) + player.currentPosition
            }
            transformer?.let { t ->
                val holder = ProgressHolder()
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progress = holder.progress / 100f
                }
            }
            delay(100)
        }
    }

    val keptMs = if (cutMiddle) durationMs - (endMs - startMs) else endMs - startMs
    val resultMs = (keptMs / speed).toLong()

    Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().imePadding()) {
        // horná lišta
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = tr("Späť", "Back"), tint = C.Text, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(tr("Úprava videa", "Edit video"), color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val info = buildString {
                    if (durationMs > 0) append(formatDuration(durationMs))
                    if (videoWidth > 0) append(" · $videoWidth × $videoHeight")
                    if (fileSize > 0) append(" · " + formatMb(fileSize))
                }
                Text(info, color = C.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                enabled = !exporting && durationMs > 0 && keptMs > 300,
                onClick = {
                    player.pause()
                    exporting = true
                    progress = 0f
                    val params = EditParams(
                        startMs = startMs,
                        endMs = endMs,
                        durationMs = durationMs,
                        cutMiddle = cutMiddle,
                        removeAudio = removeAudio,
                        originalVolume = originalVolume,
                        musicVolume = musicVolume,
                        speed = speed,
                        text = text.trim(),
                        textPos = textPos,
                        textColor = textColor.toArgb(),
                        videoHeight = videoHeight,
                        musicUri = musicUri,
                        crop = crop,
                    )
                    transformer = exportVideo(ctx, uri, params) { saved ->
                        exporting = false
                        transformer = null
                        if (saved != null) {
                            RecorderService.savedCounter.value = RecorderService.savedCounter.value + 1
                            Toast.makeText(ctx, tr("Video uložené do Galéria › RecShotC8", "Video saved to Gallery › RecShotC8"), Toast.LENGTH_LONG).show()
                            onOpenGallery()
                        } else {
                            Toast.makeText(ctx, tr("Export sa nepodaril", "Export failed"), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = C.Accent),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Exportovať", "Export"), fontWeight = FontWeight.SemiBold)
            }
        }

        // farebné akcie: zdieľať, Wi-Fi/BT, zmenšiť
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionChip(tr("Zdieľať", "Share"), Icons.Filled.Share, Color(0xFF13261F), Color(0xFF21403A), Color(0xFF7FE0B0), Modifier.weight(1f)) {
                shareUri(ctx, uri, "video/*")
            }
            ActionChip("Wi-Fi / BT", Icons.Filled.Wifi, Color(0xFF132036), Color(0xFF24406A), Color(0xFF8FBEFF), Modifier.weight(1f)) {
                showNearby = true
            }
            ActionChip(tr("Zmenšiť", "Compress"), Icons.Filled.Compress, Color(0xFF1C1929), Color(0xFF2F2950), Color(0xFFC9B8FF), Modifier.weight(1f)) {
                player.pause()
                showCompress = true
            }
        }

        // náhľad
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(C.Surface)
                .border(1.dp, Color(0xFF22252D), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (tool == VTool.CROP || !isFullCrop(crop)) {
                CropOverlay(
                    crop = crop,
                    editable = tool == VTool.CROP,
                    videoW = videoWidth,
                    videoH = videoHeight,
                    onChange = { crop = it },
                )
            }
            if (text.isNotBlank()) {
                val align = when (textPos) {
                    0 -> Alignment.TopCenter
                    1 -> Alignment.Center
                    else -> Alignment.BottomCenter
                }
                Text(
                    text,
                    color = textColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(align)
                        .padding(28.dp)
                        .background(Color(0x80000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color(0xBF0E0F13))
                    .clickable {
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            if (scrubbing) showResult()
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
                            player.play()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) tr("Pauza", "Pause") else tr("Prehrať", "Play"),
                    tint = Color.White, modifier = Modifier.size(30.dp)
                )
            }
        }

        // časy
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
            Text(
                if (scrubbing) tr("Strih na ${formatPrecise(positionMs)}", "Cut at ${formatPrecise(positionMs)}")
                else "${formatDuration((positionMs / speed).toLong())} / ${formatDuration(resultMs)}",
                color = if (scrubbing) C.Trim else C.Text,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            Text(
                if (scrubbing) tr("Pusti ▶ pre náhľad výsledku", "Press ▶ to preview the result") else tr("Náhľad výsledku", "Result preview"),
                color = C.TextDim, fontSize = 13.sp
            )
        }

        // časová os – odsadená od krajov (mimo zóny gesta Späť) + gesto Späť tu vypnuté
        Box(
            Modifier
                .padding(horizontal = 36.dp)
                .fillMaxWidth()
                .height(64.dp)
                .systemGestureExclusion()
        ) {
            Row(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(C.SurfaceHi)) {
                thumbs.forEach { t ->
                    Image(
                        bitmap = t, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
            RangeSlider(
                value = range,
                onValueChange = { r ->
                    if (durationMs <= 0) {
                        range = r
                    } else {
                        // prichytenie na 0,1 s
                        fun snap(f: Float): Float = (Math.round(f * durationMs / 100.0) * 100.0 / durationMs).toFloat().coerceIn(0f, 1f)
                        val startChanged = r.start != range.start
                        val snapped = snap(r.start)..snap(r.endInclusive)
                        range = snapped
                        showFull(((if (startChanged) snapped.start else snapped.endInclusive) * durationMs).toLong())
                    }
                },
                onValueChangeFinished = { showResult() },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = C.Trim,
                    activeTrackColor = C.Trim.copy(alpha = 0.55f),
                    inactiveTrackColor = Color(0x99000000),
                ),
                startThumb = { TrimHandle(isStart = true) },
                endThumb = { TrimHandle(isStart = false) },
                modifier = Modifier.fillMaxSize()
            )
            // stmavenie vystrihnutých častí
            Box(Modifier.fillMaxSize().padding(horizontal = 9.dp)) {
                if (range.start > 0f) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(range.start)
                            .align(Alignment.CenterStart)
                            .background(Color(0x99000000))
                    )
                }
                if (range.endInclusive < 1f) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(1f - range.endInclusive)
                            .align(Alignment.CenterEnd)
                            .background(Color(0x99000000))
                    )
                }
            }
            // zelená čiara = aktuálne prehrávané miesto
            if (durationMs > 0) {
                val frac = (playheadMs.toFloat() / durationMs).coerceIn(0f, 1f)
                Box(Modifier.fillMaxSize().padding(horizontal = 9.dp)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac)) {
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(C.PlayGreen)
                        )
                    }
                }
            }
        }

        // panel aktuálneho nástroja
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            when (tool) {
                VTool.CUT -> Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeButton(tr("Ponechať výber", "Keep selection"), !cutMiddle, Modifier.weight(1f)) { cutMiddle = false }
                        ModeButton(tr("Vystrihnúť výber", "Cut out selection"), cutMiddle, Modifier.weight(1f)) { cutMiddle = true }
                    }
                    Spacer(Modifier.height(10.dp))
                    // jemné doladenie po 0,1 s
                    fun nudge(isStart: Boolean, deltaMs: Long) {
                        if (durationMs <= 0) return
                        val minGap = 200L
                        if (isStart) {
                            val ms = (startMs + deltaMs).coerceIn(0L, endMs - minGap)
                            range = (ms.toFloat() / durationMs)..range.endInclusive
                            showFull(ms)
                        } else {
                            val ms = (endMs + deltaMs).coerceIn(startMs + minGap, durationMs)
                            range = range.start..(ms.toFloat() / durationMs)
                            showFull(ms)
                        }
                    }
                    NudgeRow(tr("Začiatok", "Start"), formatPrecise(startMs), { nudge(true, -100) }, { nudge(true, 100) })
                    Spacer(Modifier.height(6.dp))
                    NudgeRow(tr("Koniec", "End"), formatPrecise(endMs), { nudge(false, -100) }, { nudge(false, 100) })
                }

                VTool.CROP -> Column {
                    Text(
                        tr("Potiahni rohy rámika na videu. Čo je mimo rámika, nebude vo výslednom videu.", "Drag the corners of the frame on the video. Anything outside the frame will be removed."),
                        color = C.TextDim, fontSize = 13.sp, lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SmallChip(tr("Celé", "Full"), isFullCrop(crop)) { crop = FULL_CROP }
                        SmallChip(tr("Bez hornej lišty", "No top bar"), false) { crop = Rect(crop.left, 0.045f, crop.right, crop.bottom) }
                        SmallChip(tr("Bez spodnej lišty", "No bottom bar"), false) { crop = Rect(crop.left, crop.top, crop.right, 0.955f) }
                        SmallChip("1:1", false) { crop = aspectCrop(1f, videoWidth, videoHeight) }
                        SmallChip("9:16", false) { crop = aspectCrop(9f / 16f, videoWidth, videoHeight) }
                        SmallChip("16:9", false) { crop = aspectCrop(16f / 9f, videoWidth, videoHeight) }
                    }
                    Spacer(Modifier.height(6.dp))
                    val outW = ((crop.right - crop.left) * videoWidth).roundToInt()
                    val outH = ((crop.bottom - crop.top) * videoHeight).roundToInt()
                    Text(tr("Výsledok: $outW × $outH px", "Result: $outW × $outH px"), color = C.Trim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                VTool.SPEED -> Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    speeds.forEach { s ->
                        val label = if (s == s.toInt().toFloat()) "${s.toInt()}×" else "$s×".replace('.', ',')
                        SmallChip(label, speed == s) { speed = s }
                    }
                }

                VTool.TEXT -> {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(80) },
                        placeholder = { Text(tr("Napíš text do videa", "Type text for the video")) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = C.Accent,
                            unfocusedBorderColor = C.Border,
                            focusedTextColor = C.Text,
                            unfocusedTextColor = C.Text,
                            cursorColor = C.Accent,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallChip(tr("Hore", "Top"), textPos == 0) { textPos = 0 }
                        SmallChip(tr("Stred", "Middle"), textPos == 1) { textPos = 1 }
                        SmallChip(tr("Dole", "Bottom"), textPos == 2) { textPos = 2 }
                        Spacer(Modifier.width(4.dp))
                        textColors.forEach { c ->
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(if (c == textColor) 3.dp else 1.dp, if (c == textColor) C.Accent else C.Border, CircleShape)
                                    .clickable { textColor = c }
                            )
                        }
                    }
                }

                VTool.MUSIC -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { musicPicker.launch("audio/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = C.SurfaceHi, contentColor = C.Text),
                        ) {
                            Icon(Icons.Filled.MusicNote, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (musicUri == null) tr("Vybrať hudbu", "Pick music") else tr("Zmeniť", "Change"))
                        }
                        if (musicUri != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tr("Odstrániť", "Remove"), color = C.AccentText, fontSize = 14.sp,
                                modifier = Modifier.clickable { musicUri = null; musicName = "" }.padding(8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (musicUri == null) tr("Hudba sa pridá pod video a opakuje sa až do konca.", "Music is added under the video and loops until the end.")
                        else "♪ $musicName",
                        color = C.TextDim, fontSize = 13.sp, maxLines = 1
                    )
                }

                VTool.AUDIO -> Column {
                    VolumeRow(
                        title = tr("Pôvodný zvuk videa", "Original video sound"),
                        value = originalVolume,
                        onChange = { originalVolume = it },
                    )
                    if (musicUri != null) {
                        Spacer(Modifier.height(8.dp))
                        VolumeRow(
                            title = tr("Hudba", "Music"),
                            value = musicVolume,
                            onChange = { musicVolume = it },
                        )
                    }
                }
            }

            if (exporting) {
                Spacer(Modifier.height(10.dp))
                Text(tr("Exportujem… ${(progress * 100).toInt()} %", "Exporting… ${(progress * 100).toInt()} %"), color = C.Text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = C.Accent,
                    trackColor = C.SurfaceHi,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // lišta nástrojov
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            VTool.entries.forEach { t ->
                val icon: ImageVector = when (t) {
                    VTool.CUT -> Icons.Filled.ContentCut
                    VTool.CROP -> Icons.Filled.Crop
                    VTool.SPEED -> Icons.Filled.Speed
                    VTool.TEXT -> Icons.Filled.TextFields
                    VTool.MUSIC -> Icons.Filled.MusicNote
                    VTool.AUDIO -> Icons.AutoMirrored.Filled.VolumeOff
                }
                val active = when (t) {
                    VTool.SPEED -> speed != 1f
                    VTool.TEXT -> text.isNotBlank()
                    VTool.MUSIC -> musicUri != null
                    VTool.AUDIO -> originalVolume != 1f || (musicUri != null && musicVolume != 1f)
                    VTool.CUT -> range.start > 0f || range.endInclusive < 1f
                    VTool.CROP -> !isFullCrop(crop)
                }
                ToolTab(t.label, icon, tool == t, active, Modifier.weight(1f)) { tool = t }
            }
        }
    }
}

@Composable
private fun ToolTab(label: String, icon: ImageVector, selected: Boolean, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) C.AccentSoft else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = if (selected) C.AccentText else Color(0xFFC7CAD1))
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(C.Trim)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (selected) C.AccentText else Color(0xFFC7CAD1), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) C.Text else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(1.dp, C.Border, RoundedCornerShape(18.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) C.Bg else Color(0xFFC7CAD1), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) C.AccentSoft else C.Surface)
            .border(1.dp, if (selected) C.Accent else C.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) C.AccentText else C.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun displayName(ctx: Context, uri: Uri): String? = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (e: Exception) {
    null
}

private fun loadInfo(ctx: Context, uri: Uri, count: Int): VideoInfo {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(ctx, uri)
        val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
        val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val displayH = if (rot == 90 || rot == 270) w else h
        val displayW = if (rot == 90 || rot == 270) h else w
        val frames = ArrayList<ImageBitmap>()
        if (d > 0) {
            for (i in 0 until count) {
                val tUs = (d * 1000L * (2 * i + 1)) / (2L * count)
                val bmp = mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                if (bmp != null) frames.add(bmp.asImageBitmap())
            }
        }
        VideoInfo(d, displayW, displayH, frames)
    } catch (e: Exception) {
        VideoInfo(0L, 1080, 1920, emptyList())
    } finally {
        try { mmr.release() } catch (_: Exception) {}
    }
}

@OptIn(UnstableApi::class)
private fun effectsFor(p: EditParams): Effects {
    val video = ArrayList<Effect>()
    if (!isFullCrop(p.crop)) {
        val c = p.crop
        // Crop v súradniciach -1..1 (y smeruje hore)
        video.add(CropEffect(c.left * 2f - 1f, c.right * 2f - 1f, 1f - c.bottom * 2f, 1f - c.top * 2f))
    }
    val audio = ArrayList<AudioProcessor>()
    if (p.speed != 1f) {
        video.add(SpeedChangeEffect(p.speed))
        audio.add(SonicAudioProcessor().apply { setSpeed(p.speed) })
    }
    if (p.originalVolume != 1f && !p.removeAudio) {
        audio.add(GainAudioProcessor(p.originalVolume))
    }
    if (p.text.isNotBlank()) {
        val span = SpannableString(p.text)
        val px = max(28, p.videoHeight / 22)
        span.setSpan(AbsoluteSizeSpan(px), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(ForegroundColorSpan(p.textColor), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(BackgroundColorSpan(0x80000000.toInt()), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val y = when (p.textPos) {
            0 -> 0.8f
            1 -> 0f
            else -> -0.8f
        }
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, y)
            .build()
        val overlay: TextureOverlay = TextOverlay.createStaticTextOverlay(span, settings)
        video.add(OverlayEffect(ImmutableList.of(overlay)))
    }
    return Effects(audio, video)
}

/** Export cez Media3 Transformer. Výsledok sa skopíruje do Movies/RecShotC8. */
@OptIn(UnstableApi::class)
private fun exportVideo(ctx: Context, uri: Uri, p: EditParams, onDone: (Uri?) -> Unit): Transformer {
    val app = ctx.applicationContext
    val main = Handler(Looper.getMainLooper())
    val out = File(app.cacheDir, "export_${System.currentTimeMillis()}.mp4")

    fun clip(s: Long, e: Long): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s)
        if (e < p.durationMs) clipping.setEndPositionMs(e)
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping.build()).build()
        return EditedMediaItem.Builder(item)
            .setRemoveAudio(p.removeAudio)
            .setEffects(effectsFor(p))
            .build()
    }

    val transformer = Transformer.Builder(app)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                Thread {
                    val saved = MediaStoreUtil.copyVideoToGallery(app, out)
                    out.delete()
                    main.post { onDone(saved) }
                }.start()
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                out.delete()
                onDone(null)
            }
        })
        .build()

    try {
        val parts = ArrayList<EditedMediaItem>()
        if (!p.cutMiddle) {
            parts.add(clip(p.startMs, p.endMs))
        } else {
            if (p.startMs > 200) parts.add(clip(0, p.startMs))
            if (p.endMs < p.durationMs - 200) parts.add(clip(p.endMs, p.durationMs))
        }
        if (parts.isEmpty()) {
            main.post { onDone(null) }
            return transformer
        }

        val sequences = ArrayList<EditedMediaItemSequence>()
        @Suppress("DEPRECATION")
        sequences.add(EditedMediaItemSequence(parts))

        val music = p.musicUri
        if (music != null) {
            val musicBuilder = EditedMediaItem.Builder(MediaItem.fromUri(music)).setRemoveVideo(true)
            if (p.musicVolume != 1f) {
                musicBuilder.setEffects(Effects(listOf<AudioProcessor>(GainAudioProcessor(p.musicVolume)), emptyList()))
            }
            val musicItem = musicBuilder.build()
            @Suppress("DEPRECATION")
            sequences.add(EditedMediaItemSequence(listOf(musicItem), /* isLooping= */ true))
        }

        val builder = Composition.Builder(sequences)
        if (music != null) builder.experimentalSetForceAudioTrack(true)
        transformer.start(builder.build(), out.absolutePath)
    } catch (e: Exception) {
        main.post { onDone(null) }
    }
    return transformer
}

@Composable
private fun VolumeRow(title: String, value: Float, onChange: (Float) -> Unit) {
    val pct = (value * 100).roundToInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (value < 0.01f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = if (value < 0.01f) C.AccentText else C.Text
        )
        Spacer(Modifier.width(10.dp))
        Text(title, color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            if (value < 0.01f) tr("Vypnutý", "Off") else "$pct %",
            color = if (value < 0.01f) C.AccentText else C.Trim,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold
        )
    }
    Slider(
        value = value,
        onValueChange = { v -> onChange(if (v in 0.95f..1.05f) 1f else v) },
        valueRange = 0f..2f,
        colors = SliderDefaults.colors(thumbColor = C.Trim, activeTrackColor = C.Trim),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallChip(tr("Vypnúť", "Mute"), value < 0.01f) { onChange(0f) }
        SmallChip("50 %", pct == 50) { onChange(0.5f) }
        SmallChip(tr("Originál", "Original"), pct == 100) { onChange(1f) }
        SmallChip("150 %", pct == 150) { onChange(1.5f) }
    }
}

/** Zosilnenie / zoslabenie zvuku (PCM 16-bit) pri exporte. */
@UnstableApi
private class GainAudioProcessor(private val gain: Float) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C_PCM16) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (gain == 1f) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        while (inputBuffer.remaining() >= 2) {
            val v = (inputBuffer.short * gain).roundToInt().coerceIn(-32768, 32767)
            out.putShort(v.toShort())
        }
        out.flip()
    }
}

private const val C_PCM16 = androidx.media3.common.C.ENCODING_PCM_16BIT

/** Čas s desatinou sekundy, napr. 0:12,4 */
fun formatPrecise(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    val totalTenths = (t + 50) / 100
    val sec = totalTenths / 10
    return "%d:%02d,%d".format(sec / 60, sec % 60, totalTenths % 10)
}

@Composable
private fun NudgeRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.TextDim, fontSize = 13.sp, modifier = Modifier.width(70.dp))
        Text(value, color = C.Trim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        NudgeButton("−0,1 s", onMinus)
        Spacer(Modifier.width(8.dp))
        NudgeButton("+0,1 s", onPlus)
    }
}

@Composable
private fun NudgeButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(C.SurfaceHi)
            .border(1.dp, C.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val FULL_CROP = Rect(0f, 0f, 1f, 1f)

private fun isFullCrop(c: Rect): Boolean =
    c.left <= 0.001f && c.top <= 0.001f && c.right >= 0.999f && c.bottom >= 0.999f

/** Vycentrovaný výrez s daným pomerom strán (šírka / výška). */
private fun aspectCrop(target: Float, videoW: Int, videoH: Int): Rect {
    if (videoW <= 0 || videoH <= 0) return FULL_CROP
    val videoAr = videoW.toFloat() / videoH
    return if (videoAr > target) {
        val w = target / videoAr
        Rect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
    } else {
        val h = videoAr / target
        Rect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
    }
}

/** Rámik na orezanie obrazu nad náhľadom videa. */
@Composable
private fun CropOverlay(
    crop: Rect,
    editable: Boolean,
    videoW: Int,
    videoH: Int,
    onChange: (Rect) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val current by rememberUpdatedState(crop)
    val change by rememberUpdatedState(onChange)
    val k = if (size.width == 0 || videoW <= 0 || videoH <= 0) 0f
    else min(size.width / videoW.toFloat(), size.height / videoH.toFloat())
    val dw = videoW * k
    val dh = videoH * k
    val ox = (size.width - dw) / 2f
    val oy = (size.height - dh) / 2f

    var mod = Modifier.fillMaxSize().onSizeChanged { size = it }
    if (editable) {
        mod = mod.pointerInput(size, videoW, videoH) {
            var handle = -1 // 0 ĽH, 1 PH, 2 ĽD, 3 PD, 4 posun
            val grab = 40.dp.toPx()
            detectDragGestures(
                onDragStart = { p ->
                    val c = current
                    val corners = listOf(
                        Offset(ox + c.left * dw, oy + c.top * dh),
                        Offset(ox + c.right * dw, oy + c.top * dh),
                        Offset(ox + c.left * dw, oy + c.bottom * dh),
                        Offset(ox + c.right * dw, oy + c.bottom * dh),
                    )
                    val idx = corners.indexOfFirst { (it - p).getDistance() < grab }
                    val inside = p.x in (ox + c.left * dw)..(ox + c.right * dw) &&
                        p.y in (oy + c.top * dh)..(oy + c.bottom * dh)
                    handle = if (idx >= 0) idx else if (inside) 4 else -1
                },
                onDrag = { ch, d ->
                    if (handle < 0 || dw <= 0f || dh <= 0f) return@detectDragGestures
                    ch.consume()
                    val fx = d.x / dw
                    val fy = d.y / dh
                    val c = current
                    val m = 0.1f
                    val n = when (handle) {
                        0 -> Rect((c.left + fx).coerceIn(0f, c.right - m), (c.top + fy).coerceIn(0f, c.bottom - m), c.right, c.bottom)
                        1 -> Rect(c.left, (c.top + fy).coerceIn(0f, c.bottom - m), (c.right + fx).coerceIn(c.left + m, 1f), c.bottom)
                        2 -> Rect((c.left + fx).coerceIn(0f, c.right - m), c.top, c.right, (c.bottom + fy).coerceIn(c.top + m, 1f))
                        3 -> Rect(c.left, c.top, (c.right + fx).coerceIn(c.left + m, 1f), (c.bottom + fy).coerceIn(c.top + m, 1f))
                        else -> c.translate(fx.coerceIn(-c.left, 1f - c.right), fy.coerceIn(-c.top, 1f - c.bottom))
                    }
                    change(n)
                },
                onDragEnd = { handle = -1 },
                onDragCancel = { handle = -1 },
            )
        }
    }

    Canvas(mod) {
        if (dw <= 0f) return@Canvas
        val l = ox + crop.left * dw
        val t = oy + crop.top * dh
        val r = ox + crop.right * dw
        val b = oy + crop.bottom * dh
        val dim = Color(0xA6000000)
        drawRect(dim, Offset(ox, oy), Size(dw, t - oy))
        drawRect(dim, Offset(ox, b), Size(dw, oy + dh - b))
        drawRect(dim, Offset(ox, t), Size(l - ox, b - t))
        drawRect(dim, Offset(r, t), Size(ox + dw - r, b - t))
        if (editable) {
            drawRect(C.Trim, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 2.dp.toPx()))
            val len = 18.dp.toPx()
            val w = 4.dp.toPx()
            // rohové úchyty
            listOf(
                Triple(Offset(l, t), 1f, 1f), Triple(Offset(r, t), -1f, 1f),
                Triple(Offset(l, b), 1f, -1f), Triple(Offset(r, b), -1f, -1f),
            ).forEach { (p, sx, sy) ->
                drawLine(C.Trim, p, Offset(p.x + len * sx, p.y), strokeWidth = w)
                drawLine(C.Trim, p, Offset(p.x, p.y + len * sy), strokeWidth = w)
            }
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: ImageVector,
    bg: Color,
    border: Color,
    fg: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}


/** Žltý úchyt strihu: 18 dp široký, s dvoma čiarkami (rukoväť). */
@Composable
private fun TrimHandle(isStart: Boolean) {
    val shape = if (isStart) RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp, topEnd = 3.dp, bottomEnd = 3.dp)
    else RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 7.dp, bottomEnd = 7.dp)
    Box(
        Modifier.width(18.dp).height(64.dp).clip(shape).background(C.Trim),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(2) {
                Box(Modifier.width(2.dp).height(20.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFF5A3E00)))
            }
        }
    }
}
