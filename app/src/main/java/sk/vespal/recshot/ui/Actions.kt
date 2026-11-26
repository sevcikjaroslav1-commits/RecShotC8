package sk.vespal.recshot.ui

import sk.vespal.recshot.tr
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableLongStateOf
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
        ctx.startActivity(Intent.createChooser(send, tr("Zdieľať cez…", "Share via…")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, tr("Zdieľanie nie je dostupné", "Sharing is not available"), Toast.LENGTH_SHORT).show()
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
        title = { Text(tr("Poslať do blízkeho zariadenia", "Send to a nearby device"), color = C.Text) },
        text = {
            Column {
                Text(
                    tr("Prenos cez Wi-Fi alebo Bluetooth bez internetu – do telefónu, tabletu alebo počítača vedľa teba.", "Transfer over Wi-Fi or Bluetooth without internet – to a phone, tablet or computer next to you."),
                    color = C.TextDim, fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                if (targets.isEmpty()) {
                    Text(tr("V telefóne sa nenašiel Quick Share ani Bluetooth. Použi Zdieľať.", "Quick Share or Bluetooth was not found on this phone. Use Share."), color = C.AccentText, fontSize = 13.sp)
                }
                targets.forEach { t ->
                    ActionRow(t.label) {
                        sendNearby(ctx, t, uris, mime)
                        onDismiss()
                    }
                    Spacer(Modifier.height(8.dp))
                }
                ActionRow(tr("Iné možnosti zdieľania…", "Other sharing options…")) {
                    shareMany(ctx, uris, mime)
                    onDismiss()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Zavrieť", "Close")) } },
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
    // skutočný dátový tok videa (nahrávky obrazovky bývajú vďaka statickému obrazu veľmi úsporné,
    // preto sa cieľ počíta vždy z pôvodného toku – aby bol výsledok naozaj menší)
    val orig = max(400_000, info.bitrate - 128_000)
    val codec = if (hevc) 1f else 1.25f
    return listOf(
        Preset(
            tr("Vysoká kvalita", "High quality"), tr("Rovnaké rozlíšenie, menší súbor", "Same resolution, smaller file"),
            short, max(300_000, (orig * 0.6f * codec).toInt().coerceAtMost((orig * 0.7f).toInt()))
        ),
        Preset(
            tr("Vyvážená", "Balanced"), tr("Do 1080p – ideálne na WhatsApp a e-mail", "Up to 1080p – ideal for WhatsApp and e-mail"),
            min(short, 1080), max(250_000, (orig * 0.4f).toInt())
        ),
        Preset(
            tr("Malý súbor", "Small file"), tr("Do 720p – najmenšia veľkosť", "Up to 720p – smallest size"),
            min(short, 720), max(200_000, (orig * 0.22f).toInt())
        ),
    )
}

fun formatMb(bytes: Long): String {
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
        title = { Text(tr("Komprimovať video", "Compress video"), color = C.Text) },
        text = {
            Column {
                when {
                    inf == null -> Text(tr("Načítavam…", "Loading…"), color = C.TextDim)
                    resultText != null -> Text(resultText!!, color = C.Text, fontSize = 15.sp, lineHeight = 21.sp)
                    running -> {
                        Text(tr("Komprimujem… ${(progress * 100).toInt()} %", "Compressing… ${(progress * 100).toInt()} %"), color = C.Text)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progress }, color = C.Accent, trackColor = C.SurfaceHi,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    else -> {
                        Text(
                            tr("Teraz: ${formatMb(inf.sizeBytes)} · ${min(inf.width, inf.height)}p", "Now: ${formatMb(inf.sizeBytes)} · ${min(inf.width, inf.height)}p") + if (hevc) tr(" · kodek H.265", " · H.265 codec") else "",
                            color = C.TextDim, fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        presetsFor(inf, hevc).forEachIndexed { i, p ->
                            val est = ((p.videoBitrate + 128_000L) * inf.durationMs / 8000L)
                            val estText = if (inf.sizeBytes > 0 && est >= inf.sizeBytes) tr("≈ bez zmeny", "≈ no change") else "≈ ${formatMb(est)}"
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(C.SurfaceHi)
                                    .border(1.dp, if (chosen == i) C.Accent else C.Border, RoundedCornerShape(12.dp))
                                    .clickable {
                                        chosen = i
                                        running = true
                                        transformer = compressVideo(ctx, uri, inf, p, hevc, onNotSmaller = {
                                            transformer = null
                                            running = false
                                            resultText = tr(
                                                "Video je už dobre skomprimované – pri tejto voľbe by nebolo menšie.\nSkús „Malý súbor“.",
                                                "The video is already well compressed – this option would not make it smaller.\nTry “Small file”."
                                            )
                                        }) { saved ->
                                            transformer = null
                                            running = false
                                            if (saved != null) {
                                                val newSize = sourceInfo(ctx, saved).sizeBytes
                                                resultText = tr("Hotovo ✓\n${formatMb(inf.sizeBytes)} → ${formatMb(newSize)}\nUložené v Galéria › RecShotC8", "Done ✓\n${formatMb(inf.sizeBytes)} → ${formatMb(newSize)}\nSaved in Gallery › RecShotC8")
                                                RecorderService.savedCounter.value = RecorderService.savedCounter.value + 1
                                                onDone(saved)
                                            } else {
                                                resultText = tr("Kompresia sa nepodarila.", "Compression failed.")
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
                TextButton(onClick = { transformer?.cancel(); transformer = null; running = false; onDismiss() }) { Text(tr("Zrušiť", "Cancel")) }
            } else {
                TextButton(onClick = onDismiss) { Text(if (resultText != null) "OK" else tr("Zavrieť", "Close")) }
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
    onNotSmaller: () -> Unit,
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
                    // ak by výsledok nebol menší, neukladáme zbytočnú kópiu
                    if (info.sizeBytes > 0 && out.length() >= info.sizeBytes * 97 / 100) {
                        out.delete()
                        main.post { onNotSmaller() }
                        return@Thread
                    }
                    val saved = MediaStoreUtil.copyVideoToGallery(app, out, "RecC8_mensie")
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

fun fileSizeOf(ctx: Context, uri: Uri): Long = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
    } ?: 0L
} catch (_: Exception) {
    0L
}


// ============================================================ zmenšenie fotky

private class ImgPreset(val title: String, val note: String, val maxSide: Int, val quality: Int)

/** Dialóg zmenšenia fotky: uloží menšiu kópiu ako JPEG do Pictures/RecShotC8. */
@Composable
fun ImageCompressDialog(uri: Uri, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var size by remember { mutableLongStateOf(0L) }
    var dims by remember { mutableStateOf(0 to 0) }
    var running by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            size = fileSizeOf(ctx, uri)
            try {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                dims = o.outWidth to o.outHeight
            } catch (_: Exception) {
            }
        }
    }
    val long = max(dims.first, dims.second)
    val presets = listOf(
        ImgPreset(tr("Vysoká kvalita", "High quality"), tr("Rovnaké rozlíšenie, JPEG 90 %", "Same resolution, JPEG 90%"), long.takeIf { it > 0 } ?: 4096, 90),
        ImgPreset(tr("Vyvážená", "Balanced"), tr("Max. 1920 px – na WhatsApp a e-mail", "Max 1920 px – for WhatsApp and e-mail"), 1920, 80),
        ImgPreset(tr("Malý súbor", "Small file"), tr("Max. 1280 px – najmenšia veľkosť", "Max 1280 px – smallest size"), 1280, 70),
    )

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        containerColor = C.Surface,
        title = { Text(tr("Zmenšiť fotku", "Shrink photo"), color = C.Text) },
        text = {
            Column {
                when {
                    resultText != null -> Text(resultText!!, color = C.Text, fontSize = 15.sp, lineHeight = 21.sp)
                    running -> {
                        Text(tr("Zmenšujem…", "Shrinking…"), color = C.Text)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(color = C.Accent, trackColor = C.SurfaceHi, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text(
                            tr("Teraz: ", "Now: ") + formatMb(size) + if (long > 0) " · ${dims.first} × ${dims.second}" else "",
                            color = C.TextDim, fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        presets.forEach { p ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(C.SurfaceHi)
                                    .border(1.dp, C.Border, RoundedCornerShape(12.dp))
                                    .clickable {
                                        running = true
                                        scope.launch {
                                            val saved = withContext(Dispatchers.IO) { shrinkImage(ctx, uri, p.maxSide, p.quality, size) }
                                            running = false
                                            resultText = when {
                                                saved == null -> tr("Zmenšenie sa nepodarilo.", "Shrinking failed.")
                                                saved.second -> tr("Fotka je už malá – pri tejto voľbe by nebola menšia.\nSkús „Malý súbor“.", "The photo is already small – this option would not make it smaller.\nTry “Small file”.")
                                                else -> {
                                                    RecorderService.savedCounter.value = RecorderService.savedCounter.value + 1
                                                    tr("Hotovo ✓\n${formatMb(size)} → ${formatMb(saved.third)}\nUložené v Galéria › RecShotC8",
                                                        "Done ✓\n${formatMb(size)} → ${formatMb(saved.third)}\nSaved in Gallery › RecShotC8")
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(p.title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(p.note, color = C.TextDim, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!running) TextButton(onClick = onDismiss) { Text(if (resultText != null) "OK" else tr("Zavrieť", "Close")) }
        },
    )
}

/** Vráti (uri, nebolaMenšia, novúVeľkosť) alebo null pri chybe. */
private fun shrinkImage(ctx: Context, uri: Uri, maxSide: Int, quality: Int, origSize: Long): Triple<Uri?, Boolean, Long>? {
    return try {
        val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longSide = max(w, h)
            if (longSide > maxSide) {
                val f = maxSide.toFloat() / longSide
                decoder.setTargetSize((w * f).roundToInt().coerceAtLeast(1), (h * f).roundToInt().coerceAtLeast(1))
            }
        }
        val buf = java.io.ByteArrayOutputStream()
        // priehľadnosť JPEG nepozná – podklad vyplníme bielou
        val flat = if (bmp.hasAlpha()) {
            android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                android.graphics.Canvas(b).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            }
        } else bmp
        flat.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, buf)
        val bytes = buf.toByteArray()
        if (origSize > 0 && bytes.size >= origSize * 97 / 100) return Triple(null, true, bytes.size.toLong())
        val saved = MediaStoreUtil.saveJpeg(ctx, bytes, "RecC8_mensie") ?: return null
        Triple(saved, false, bytes.size.toLong())
    } catch (_: Exception) {
        null
    }
}
