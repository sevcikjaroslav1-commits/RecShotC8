package sk.vespal.recshot.ui

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
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
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

private enum class VTool(val label: String) { CUT("Strih"), SPEED("Rýchlosť"), TEXT("Text"), MUSIC("Hudba"), AUDIO("Zvuk") }

private val speeds = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f)
private val textColors = listOf(Color.White, Color(0xFFFFC24A), Color(0xFFE8462A), Color(0xFF6EA8FF), Color.Black)

/** Všetky úpravy, ktoré sa použijú pri exporte. */
private data class EditParams(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val cutMiddle: Boolean,
    val removeAudio: Boolean,
    val speed: Float,
    val text: String,
    val textPos: Int, // 0 hore, 1 stred, 2 dole
    val textColor: Int,
    val videoHeight: Int,
    val musicUri: Uri?,
)

private class VideoInfo(val durationMs: Long, val height: Int, val frames: List<ImageBitmap>)

@OptIn(UnstableApi::class)
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
    var thumbs by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var range by remember { mutableStateOf(0f..1f) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }

    var tool by remember { mutableStateOf(VTool.CUT) }
    var cutMiddle by remember { mutableStateOf(false) }
    var removeAudio by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var text by remember { mutableStateOf("") }
    var textPos by remember { mutableIntStateOf(2) }
    var textColor by remember { mutableStateOf(Color.White) }
    var musicUri by remember { mutableStateOf<Uri?>(null) }
    var musicName by remember { mutableStateOf("") }

    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }

    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) {
            musicUri = picked
            musicName = displayName(ctx, picked) ?: "Vybraná hudba"
        }
    }

    LaunchedEffect(uri) {
        val info = withContext(Dispatchers.IO) { loadInfo(ctx, uri, 8) }
        durationMs = info.durationMs
        videoHeight = info.height
        thumbs = info.frames
    }

    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    // pozícia, prehrávanie len vo výbere, priebeh exportu
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            playing = player.isPlaying
            if (durationMs > 0 && !cutMiddle) {
                val s = (range.start * durationMs).toLong()
                val e = (range.endInclusive * durationMs).toLong()
                if (player.isPlaying && positionMs >= e) {
                    player.pause()
                    player.seekTo(s)
                }
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

    val startMs = (range.start * durationMs).toLong()
    val endMs = (range.endInclusive * durationMs).toLong()
    val keptMs = if (cutMiddle) durationMs - (endMs - startMs) else endMs - startMs
    val resultMs = (keptMs / speed).toLong()

    Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().imePadding()) {
        // horná lišta
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť", tint = C.Text)
            }
            Text("Upraviť video", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { shareUri(ctx, uri, "video/*") }) {
                Icon(Icons.Filled.Share, contentDescription = "Zdieľať originál", tint = C.Text)
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
                        speed = speed,
                        text = text.trim(),
                        textPos = textPos,
                        textColor = textColor.toArgb(),
                        videoHeight = videoHeight,
                        musicUri = musicUri,
                    )
                    transformer = exportVideo(ctx, uri, params) { saved ->
                        exporting = false
                        transformer = null
                        if (saved != null) {
                            RecorderService.savedCounter.value = RecorderService.savedCounter.value + 1
                            Toast.makeText(ctx, "Video uložené do Galéria › RecShotC8", Toast.LENGTH_LONG).show()
                            onOpenGallery()
                        } else {
                            Toast.makeText(ctx, "Export sa nepodaril", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = C.Accent),
            ) { Text("Exportovať", fontWeight = FontWeight.SemiBold) }
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
                            if (durationMs > 0 && !cutMiddle && (positionMs < startMs || positionMs >= endMs)) {
                                player.seekTo(startMs)
                            }
                            player.play()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pauza" else "Prehrať",
                    tint = Color.White, modifier = Modifier.size(30.dp)
                )
            }
        }

        // časy
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
            Text(formatDuration(positionMs), color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "Výsledok: ${formatDuration(resultMs)} z ${formatDuration(durationMs)}",
                color = C.TextDim, fontSize = 13.sp
            )
        }

        // časová os
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(60.dp)) {
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
                    val moved = if (r.start != range.start) r.start else r.endInclusive
                    range = r
                    if (durationMs > 0) player.seekTo((moved * durationMs).toLong())
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = C.Trim,
                    activeTrackColor = C.Trim.copy(alpha = 0.55f),
                    inactiveTrackColor = Color(0x99000000),
                ),
                modifier = Modifier.fillMaxSize()
            )
        }

        // panel aktuálneho nástroja
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            when (tool) {
                VTool.CUT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("Ponechať výber", !cutMiddle, Modifier.weight(1f)) { cutMiddle = false }
                    ModeButton("Vystrihnúť výber", cutMiddle, Modifier.weight(1f)) { cutMiddle = true }
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
                        placeholder = { Text("Napíš text do videa") },
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
                        SmallChip("Hore", textPos == 0) { textPos = 0 }
                        SmallChip("Stred", textPos == 1) { textPos = 1 }
                        SmallChip("Dole", textPos == 2) { textPos = 2 }
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
                            Text(if (musicUri == null) "Vybrať hudbu" else "Zmeniť")
                        }
                        if (musicUri != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Odstrániť", color = C.AccentText, fontSize = 14.sp,
                                modifier = Modifier.clickable { musicUri = null; musicName = "" }.padding(8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (musicUri == null) "Hudba sa pridá pod video a opakuje sa až do konca."
                        else "♪ $musicName",
                        color = C.TextDim, fontSize = 13.sp, maxLines = 1
                    )
                }

                VTool.AUDIO -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null, tint = C.AccentText)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Odstrániť pôvodný zvuk", color = C.Text, fontSize = 14.sp)
                        Text(
                            if (musicUri != null) "Ostane iba vybraná hudba" else "Video bude bez zvuku",
                            color = C.TextDim, fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = removeAudio,
                        onCheckedChange = { removeAudio = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.Accent),
                    )
                }
            }

            if (exporting) {
                Spacer(Modifier.height(10.dp))
                Text("Exportujem… ${(progress * 100).toInt()} %", color = C.Text, fontSize = 13.sp)
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
                    VTool.SPEED -> Icons.Filled.Speed
                    VTool.TEXT -> Icons.Filled.TextFields
                    VTool.MUSIC -> Icons.Filled.MusicNote
                    VTool.AUDIO -> Icons.AutoMirrored.Filled.VolumeOff
                }
                val active = when (t) {
                    VTool.SPEED -> speed != 1f
                    VTool.TEXT -> text.isNotBlank()
                    VTool.MUSIC -> musicUri != null
                    VTool.AUDIO -> removeAudio
                    VTool.CUT -> range.start > 0f || range.endInclusive < 1f
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
        val frames = ArrayList<ImageBitmap>()
        if (d > 0) {
            for (i in 0 until count) {
                val tUs = (d * 1000L * (2 * i + 1)) / (2L * count)
                val bmp = mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                if (bmp != null) frames.add(bmp.asImageBitmap())
            }
        }
        VideoInfo(d, displayH, frames)
    } catch (e: Exception) {
        VideoInfo(0L, 1920, emptyList())
    } finally {
        try { mmr.release() } catch (_: Exception) {}
    }
}

@OptIn(UnstableApi::class)
private fun effectsFor(p: EditParams): Effects {
    val video = ArrayList<Effect>()
    val audio = ArrayList<AudioProcessor>()
    if (p.speed != 1f) {
        video.add(SpeedChangeEffect(p.speed))
        audio.add(SonicAudioProcessor().apply { setSpeed(p.speed) })
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
            val musicItem = EditedMediaItem.Builder(MediaItem.fromUri(music))
                .setRemoveVideo(true)
                .build()
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
