package sk.vespal.recshot.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sk.vespal.recshot.MediaStoreUtil
import sk.vespal.recshot.RecorderService
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ============================================================ zdieľanie

/** Bežné zdieľanie (Gmail, WhatsApp, Messenger, Drive…). */
fun shareMany(ctx: Context, uris: List<Uri>, mime: String) {
    if (uris.isEmpty()) return
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(send, "Zdieľať cez…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Zdieľanie nie je dostupné", Toast.LENGTH_SHORT).show()
    }
}

/** Cieľ na priamy prenos do blízkeho zariadenia. */
class NearbyTarget(val label: String, val component: ComponentName)

/**
 * Nájde v telefóne Quick Share (Google aj Samsung) a Bluetooth – prenos cez Wi-Fi/Bluetooth
 * bez internetu do blízkeho telefónu alebo počítača.
 */
fun findNearbyTargets(ctx: Context, mime: String): List<NearbyTarget> {
    val pm = ctx.packageManager
    val probe = Intent(Intent.ACTION_SEND).setType(mime)
    val list = try {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
    } catch (e: Exception) {
        emptyList()
    }
    val out = ArrayList<NearbyTarget>()
    for (ri in list) {
        val ai = ri.activityInfo ?: continue
        val pkg = ai.packageName
        val label = ri.loadLabel(pm)?.toString() ?: ""
        val lower = label.lowercase()
        val name = when {
            pkg == "com.google.android.gms" && (lower.contains("quick share") || lower.contains("nearby") || lower.contains("zdieľanie nablízku") || lower.contains("rýchle zdieľanie")) -> "Quick Share (Wi-Fi)"
            pkg.startsWith("com.samsung.android.app.sharelive") -> "Quick Share Samsung (Wi-Fi)"
            pkg == "com.android.bluetooth" || pkg.contains("bluetooth") -> "Bluetooth"
            else -> null
        } ?: continue
        if (out.none { it.label == name }) out.add(NearbyTarget(name, ComponentName(pkg, ai.name)))
    }
    return out.sortedBy { if (it.label.startsWith("Quick")) 0 else 1 }
}

fun sendNearby(ctx: Context, target: NearbyTarget, uris: List<Uri>, mime: String) {
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.setType(mime)
        .setComponent(target.component)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(send)
    } catch (e: ActivityNotFoundException) {
        shareMany(ctx, uris, mime)
    } catch (e: SecurityException) {
        shareMany(ctx, uris, mime)
    }
}

/** Dialóg „Poslať do blízkeho zariadenia“. */
@Composable
fun NearbyDialog(uris: List<Uri>, mime: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val targets = remember(mime) { findNearbyTargets(ctx, mime) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text("Poslať do blízkeho zariadenia", color = C.Text) },
        text = {
            Column {
                Text(
                    "Prenos cez Wi-Fi alebo Bluetooth bez internetu – do telefónu, tabletu alebo počítača vedľa teba.",
                    color = C.TextDim, fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                if (targets.isEmpty()) {
                    Text("V telefóne sa nenašiel Quick Share ani Bluetooth. Použi Zdieľať.", color = C.AccentText, fontSize = 13.sp)
                }
                targets.forEach { t ->
                    ActionRow(t.label) {
                        sendNearby(ctx, t, uris, mime)
                        onDismiss()
                    }
                    Spacer(Modifier.height(8.dp))
                }
                ActionRow("Iné možnosti zdieľania…") {
                    shareMany(ctx, uris, mime)
                    onDismiss()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zavrieť") } },
    )
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(C.SurfaceHi)
            .border(1.dp, C.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ============================================================ kompresia

private class SourceInfo(val sizeBytes: Long, val durationMs: Long, val width: Int, val height: Int, val bitrate: Int)

private fun sourceInfo(ctx: Context, uri: Uri): SourceInfo {
    var size = 0L
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
        }
    } catch (_: Exception) {
    }
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(ctx, uri)
        val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        var br = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
        if (br <= 0 && size > 0 && d > 0) br = (size * 8 * 1000 / d).toInt()
        val (dw, dh) = if (rot == 90 || rot == 270) h to w else w to h
        SourceInfo(size, d, dw, dh, br)
    } catch (e: Exception) {
        SourceInfo(size, 0, 0, 0, 0)
    } finally {
        try { mmr.release() } catch (_: Exception) {}
    }
}

private fun hasHevcEncoder(): Boolean = try {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, ignoreCase = true) }
    }
} catch (_: Exception) {
    false
}

private class Preset(val title: String, val note: String, val shortSide: Int, val videoBitrate: Int)

private fun presetsFor(info: SourceInfo, hevc: Boolean): List<Preset> {
    val short = min(info.width, info.height).takeIf { it > 0 } ?: 1080
    val f = if (hevc) 1f else 1.4f // H.264 potrebuje vyšší dátový tok na rovnakú kvalitu
    val origVideoBr = max(1_000_000, info.bitrate - 160_000)
    val high = min((origVideoBr * 0.5f).toInt(), (6_000_000 * f).toInt())
    return listOf(
        Preset("Vysoká kvalita", "Rovnaké rozlíšenie, menší súbor", short, max(1_500_000, high)),
        Preset("Vyvážená", "Do 1080p – ideálne na WhatsApp a e-mail", min(short, 1080), (3_000_000 * f).toInt()),
        Preset("Malý súbor", "720p – najmenšia veľkosť", min(short, 720), (1_500_000 * f).toInt()),
    )
}

private fun mb(bytes: Long): String {
    val m = bytes / 1_048_576.0
    return if (m >= 10) "${m.roundToInt()} MB" else "%.1f MB".format(m).replace('.', ',')
}

/** Dialóg kompresie videa: výber kvality → export → uloženie do galérie. */
@OptIn(UnstableApi::class)
@Composable
fun CompressDialog(uri: Uri, onDismiss: () -> Unit, onDone: (Uri) -> Unit = {}) {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<SourceInfo?>(null) }
    val hevc = remember { hasHevcEncoder() }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableIntStateOf(-1) }

    LaunchedEffect(uri) { info = withContext(Dispatchers.IO) { sourceInfo(ctx, uri) } }
    LaunchedEffect(transformer) {
        val t = transformer ?: return@LaunchedEffect
        while (true) {
            val h = ProgressHolder()
            if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) progress = h.progress / 100f
            delay(200)
        }
    }

    val inf = info
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        containerColor = C.Surface,
        title = { Text("Komprimovať video", color = C.Text) },
        text = {
            Column {
                when {
                    inf == null -> Text("Načítavam…", color = C.TextDim)
                    resultText != null -> Text(resultText!!, color = C.Text, fontSize = 15.sp, lineHeight = 21.sp)
                    running -> {
                        Text("Komprimujem… ${(progress * 100).toInt()} %", color = C.Text)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progress }, color = C.Accent, trackColor = C.SurfaceHi,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    else -> {
                        Text(
                            "Teraz: ${mb(inf.sizeBytes)} · ${min(inf.width, inf.height)}p" + if (hevc) " · kodek H.265" else "",
                            color = C.TextDim, fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        presetsFor(inf, hevc).forEachIndexed { i, p ->
                            val est = ((p.videoBitrate + 128_000L) * inf.durationMs / 8000L)
                            val estText = if (inf.sizeBytes > 0 && est >= inf.sizeBytes) "≈ bez zmeny" else "≈ ${mb(est)}"
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(C.SurfaceHi)
                                    .border(1.dp, if (chosen == i) C.Accent else C.Border, RoundedCornerShape(12.dp))
                                    .clickable {
                                        chosen = i
                                        running = true
                                        transformer = compressVideo(ctx, uri, inf, p, hevc) { saved ->
                                            transformer = null
                                            running = false
                                            if (saved != null) {
                                                val newSize = sourceInfo(ctx, saved).sizeBytes
                                                resultText = "Hotovo ✓\n${mb(inf.sizeBytes)} → ${mb(newSize)}\nUložené v Galéria › RecShotC8"
                                                RecorderService.savedCounter.value = RecorderService.savedCounter.value + 1
                                                onDone(saved)
                                            } else {
                                                resultText = "Kompresia sa nepodarila."
                                            }
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text(estText, color = C.Trim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text(p.note, color = C.TextDim, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = { transformer?.cancel(); transformer = null; running = false; onDismiss() }) { Text("Zrušiť") }
            } else {
                TextButton(onClick = onDismiss) { Text(if (resultText != null) "OK" else "Zavrieť") }
            }
        },
    )
}

@OptIn(UnstableApi::class)
private fun compressVideo(
    ctx: Context,
    uri: Uri,
    info: SourceInfo,
    preset: Preset,
    hevc: Boolean,
    onDone: (Uri?) -> Unit,
): Transformer {
    val app = ctx.applicationContext
    val main = Handler(Looper.getMainLooper())
    val out = File(app.cacheDir, "compress_${System.currentTimeMillis()}.mp4")

    val encoderFactory = DefaultEncoderFactory.Builder(app)
        .setRequestedVideoEncoderSettings(
            VideoEncoderSettings.Builder().setBitrate(preset.videoBitrate).build()
        )
        .build()

    val effects = ArrayList<androidx.media3.common.Effect>()
    val short = min(info.width, info.height)
    if (short > 0 && preset.shortSide < short) {
        // výška výsledku tak, aby kratšia strana mala preset.shortSide
        val targetH = if (info.height <= info.width) preset.shortSide
        else ((preset.shortSide.toLong() * info.height / info.width).toInt() / 2) * 2
        effects.add(Presentation.createForHeight(targetH))
    }

    val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(Effects(emptyList(), effects))
        .build()

    val transformer = Transformer.Builder(app)
        .setVideoMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
        .setEncoderFactory(encoderFactory)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                Thread {
                    val saved = MediaStoreUtil.copyVideoToGallery(app, out, "RecShotC8_mensie")
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
        transformer.start(item, out.absolutePath)
    } catch (e: Exception) {
        main.post { onDone(null) }
    }
    return transformer
}
