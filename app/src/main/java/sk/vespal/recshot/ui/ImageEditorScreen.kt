package sk.vespal.recshot.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.vespal.recshot.MediaStoreUtil
import sk.vespal.recshot.RecorderService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class Tool { CROP, DRAW, BLUR }
private enum class Action { STROKE, BLUR, CROP }

private class PenStroke(val points: List<Offset>, val color: Color, val width: Float)

private val penColors = listOf(
    Color(0xFFE8462A), Color(0xFFFFC24A), Color(0xFF2F6FE0),
    Color(0xFF1F9E74), Color.White, Color.Black,
)

@Composable
fun ImageEditorScreen(uri: Uri, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val b = withContext(Dispatchers.IO) { loadBitmap(ctx, uri) }
        if (b == null) failed = true else source = b
    }

    val bmp = source
    if (bmp == null) {
        Box(Modifier.fillMaxSize().background(C.Bg), contentAlignment = Alignment.Center) {
            if (failed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Obrázok sa nepodarilo otvoriť", color = C.Text)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBack) { Text("Späť") }
                }
            } else {
                CircularProgressIndicator(color = C.Accent)
            }
        }
    } else {
        Editor(bmp, uri, onBack)
    }
}

@Composable
private fun Editor(bmp: Bitmap, sourceUri: Uri, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val image = remember(bmp) { bmp.asImageBitmap() }
    val bw = bmp.width.toFloat()
    val bh = bmp.height.toFloat()

    var tool by remember { mutableStateOf(Tool.DRAW) }
    var color by remember { mutableStateOf(penColors[0]) }
    val strokes = remember { mutableStateListOf<PenStroke>() }
    val current = remember { mutableStateListOf<Offset>() }
    val blurs = remember { mutableStateListOf<Rect>() }
    var blurDraft by remember { mutableStateOf<Rect?>(null) }
    var crop by remember { mutableStateOf(Rect(0f, 0f, bw, bh)) }
    val cropHistory = remember { mutableStateListOf<Rect>() }
    val history = remember { mutableStateListOf<Action>() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }

    val penWidth = max(bw, bh) / 160f

    // prepočet medzi obrazovkou a pixelmi obrázka
    val k = if (viewSize.width == 0) 1f else min(viewSize.width / bw, viewSize.height / bh)
    val offX = (viewSize.width - bw * k) / 2f
    val offY = (viewSize.height - bh * k) / 2f
    fun toBmp(p: Offset) = Offset(
        ((p.x - offX) / k).coerceIn(0f, bw),
        ((p.y - offY) / k).coerceIn(0f, bh),
    )

    fun undo() {
        when (history.removeLastOrNull()) {
            Action.STROKE -> strokes.removeLastOrNull()
            Action.BLUR -> blurs.removeLastOrNull()
            Action.CROP -> cropHistory.removeLastOrNull()?.let { crop = it }
            null -> {}
        }
        savedUri = null
    }

    Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding()) {
        // horná lišta
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť", tint = C.Text)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { undo() }, enabled = history.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Späť krok", tint = if (history.isNotEmpty()) C.Text else C.Border)
            }
            IconButton(onClick = { shareUri(ctx, savedUri ?: sourceUri, "image/*") }) {
                Icon(Icons.Filled.Share, contentDescription = "Zdieľať", tint = C.Text)
            }
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    val s = strokes.toList()
                    val b = blurs.toList()
                    val c = crop
                    scope.launch {
                        val uri = withContext(Dispatchers.Default) {
                            val out = render(bmp, s, b, c)
                            MediaStoreUtil.saveBitmap(ctx, out, "RecShotC8_edit")
                        }
                        saving = false
                        savedUri = uri
                        if (uri != null) {
                            RecorderService.savedCounter.value = RecorderService.savedCounter.value + 1
                            Toast.makeText(ctx, "Uložené do Galéria › RecShotC8", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, "Uloženie sa nepodarilo", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = C.Accent),
            ) { Text(if (saving) "Ukladám…" else "Uložiť", fontWeight = FontWeight.SemiBold) }
        }

        // plátno
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(C.Surface)
                .border(1.dp, Color(0xFF22252D), RoundedCornerShape(20.dp))
                .padding(12.dp)
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(tool, viewSize, color) {
                        when (tool) {
                            Tool.DRAW -> detectDragGestures(
                                onDragStart = { p ->
                                    current.clear()
                                    current.add(toBmp(p))
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    current.add(toBmp(change.position))
                                },
                                onDragEnd = {
                                    if (current.isNotEmpty()) {
                                        strokes.add(PenStroke(current.toList(), color, penWidth))
                                        history.add(Action.STROKE)
                                        savedUri = null
                                    }
                                    current.clear()
                                },
                                onDragCancel = { current.clear() },
                            )

                            Tool.BLUR -> {
                                var start = Offset.Zero
                                detectDragGestures(
                                    onDragStart = { p ->
                                        start = toBmp(p)
                                        blurDraft = Rect(start, start)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val e = toBmp(change.position)
                                        blurDraft = Rect(
                                            min(start.x, e.x), min(start.y, e.y),
                                            max(start.x, e.x), max(start.y, e.y)
                                        )
                                    },
                                    onDragEnd = {
                                        val r = blurDraft
                                        if (r != null && r.width > 8 && r.height > 8) {
                                            blurs.add(r)
                                            history.add(Action.BLUR)
                                            savedUri = null
                                        }
                                        blurDraft = null
                                    },
                                    onDragCancel = { blurDraft = null },
                                )
                            }

                            Tool.CROP -> {
                                var handle = -1 // 0 ĽH, 1 PH, 2 ĽD, 3 PD, 4 posun
                                val grab = 48.dp.toPx() / k
                                val minSize = 60f
                                detectDragGestures(
                                    onDragStart = { p ->
                                        val b = toBmp(p)
                                        val corners = listOf(crop.topLeft, crop.topRight, crop.bottomLeft, crop.bottomRight)
                                        val idx = corners.indexOfFirst { abs(it.x - b.x) < grab && abs(it.y - b.y) < grab }
                                        handle = if (idx >= 0) idx else if (crop.contains(b)) 4 else -1
                                        if (handle >= 0) {
                                            cropHistory.add(crop)
                                            history.add(Action.CROP)
                                            savedUri = null
                                        }
                                    },
                                    onDrag = { change, drag ->
                                        if (handle < 0) return@detectDragGestures
                                        change.consume()
                                        val dx = drag.x / k
                                        val dy = drag.y / k
                                        val c = crop
                                        crop = when (handle) {
                                            0 -> Rect(
                                                (c.left + dx).coerceIn(0f, c.right - minSize),
                                                (c.top + dy).coerceIn(0f, c.bottom - minSize), c.right, c.bottom
                                            )
                                            1 -> Rect(
                                                c.left, (c.top + dy).coerceIn(0f, c.bottom - minSize),
                                                (c.right + dx).coerceIn(c.left + minSize, bw), c.bottom
                                            )
                                            2 -> Rect(
                                                (c.left + dx).coerceIn(0f, c.right - minSize), c.top,
                                                c.right, (c.bottom + dy).coerceIn(c.top + minSize, bh)
                                            )
                                            3 -> Rect(
                                                c.left, c.top,
                                                (c.right + dx).coerceIn(c.left + minSize, bw),
                                                (c.bottom + dy).coerceIn(c.top + minSize, bh)
                                            )
                                            else -> {
                                                val mx = dx.coerceIn(-c.left, bw - c.right)
                                                val my = dy.coerceIn(-c.top, bh - c.bottom)
                                                c.translate(mx, my)
                                            }
                                        }
                                    },
                                    onDragEnd = { handle = -1 },
                                    onDragCancel = { handle = -1 },
                                )
                            }
                        }
                    }
            ) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(offX.toInt(), offY.toInt()),
                    dstSize = IntSize((bw * k).toInt(), (bh * k).toInt()),
                )
                translate(left = offX, top = offY) {
                    scale(scale = k, pivot = Offset.Zero) {
                        // rozmazané oblasti (náhľad)
                        (blurs + listOfNotNull(blurDraft)).forEach { r ->
                            drawRect(Color(0xE0606060), topLeft = r.topLeft, size = r.size)
                            val step = max(r.width, r.height) / 6f
                            var x = r.left + step
                            while (x < r.right) {
                                drawLine(Color(0x40FFFFFF), Offset(x, r.top), Offset(x, r.bottom), strokeWidth = 1f / k)
                                x += step
                            }
                        }
                        // ťahy
                        strokes.forEach { s -> drawPen(s.points, s.color, s.width) }
                        if (current.isNotEmpty()) drawPen(current, color, penWidth)

                        // orezanie
                        val full = Rect(0f, 0f, bw, bh)
                        if (tool == Tool.CROP || crop != full) {
                            val dim = Color(0x8C000000)
                            drawRect(dim, Offset(0f, 0f), Size(bw, crop.top))
                            drawRect(dim, Offset(0f, crop.bottom), Size(bw, bh - crop.bottom))
                            drawRect(dim, Offset(0f, crop.top), Size(crop.left, crop.height))
                            drawRect(dim, Offset(crop.right, crop.top), Size(bw - crop.right, crop.height))
                            drawRect(Color.White, crop.topLeft, crop.size, style = Stroke(width = 2f / k))
                            if (tool == Tool.CROP) {
                                val h = 22.dp.toPx() / k
                                listOf(crop.topLeft, crop.topRight, crop.bottomLeft, crop.bottomRight).forEach { p ->
                                    drawCircle(Color.White, radius = h / 2f, center = p)
                                }
                            }
                        }
                    }
                }
            }
        }

        // farby (len pre kreslenie)
        if (tool == Tool.DRAW) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                penColors.forEach { c ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(if (c == color) 3.dp else 1.dp, if (c == color) C.Text else C.Border, CircleShape)
                            .clickable { color = c }
                    )
                }
            }
        } else {
            Text(
                if (tool == Tool.CROP) "Potiahni rohy alebo celý výrez" else "Potiahni prstom cez oblasť, ktorú chceš skryť",
                color = C.TextDim, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
            )
        }

        // nástroje
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolButton("Orezať", Icons.Filled.Crop, tool == Tool.CROP, Modifier.weight(1f)) { tool = Tool.CROP }
            ToolButton("Kresliť", Icons.Filled.Brush, tool == Tool.DRAW, Modifier.weight(1f)) { tool = Tool.DRAW }
            ToolButton("Rozmazať", Icons.Filled.BlurOn, tool == Tool.BLUR, Modifier.weight(1f)) { tool = Tool.BLUR }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPen(points: List<Offset>, color: Color, width: Float) {
    if (points.size == 1) {
        drawCircle(color, radius = width / 2f, center = points[0])
        return
    }
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun ToolButton(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) C.AccentSoft else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) C.AccentText else Color(0xFFC7CAD1))
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (selected) C.AccentText else Color(0xFFC7CAD1), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun loadBitmap(ctx: Context, uri: Uri): Bitmap? = try {
    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
    ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width
        val h = info.size.height
        val maxSide = max(w, h)
        if (maxSide > 2560) {
            val f = 2560f / maxSide
            decoder.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
        }
    }
} catch (e: Exception) {
    null
}

/** Vykreslí úpravy do novej bitmapy v plnom rozlíšení. */
private fun render(src: Bitmap, strokes: List<PenStroke>, blurs: List<Rect>, crop: Rect): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)

    // rozmazanie = pixelizácia oblasti
    blurs.forEach { r ->
        val l = r.left.toInt().coerceIn(0, out.width - 1)
        val t = r.top.toInt().coerceIn(0, out.height - 1)
        val w = (r.right.toInt() - l).coerceIn(1, out.width - l)
        val h = (r.bottom.toInt() - t).coerceIn(1, out.height - t)
        if (w > 2 && h > 2) {
            val region = Bitmap.createBitmap(out, l, t, w, h)
            val small = Bitmap.createScaledBitmap(region, max(1, w / 24), max(1, h / 24), true)
            val big = Bitmap.createScaledBitmap(small, w, h, false)
            canvas.drawBitmap(big, l.toFloat(), t.toFloat(), null)
        }
    }

    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    strokes.forEach { s ->
        paint.color = s.color.toArgb()
        paint.strokeWidth = s.width
        if (s.points.size == 1) {
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(s.points[0].x, s.points[0].y, s.width / 2f, paint)
            paint.style = android.graphics.Paint.Style.STROKE
        } else if (s.points.isNotEmpty()) {
            val path = android.graphics.Path()
            path.moveTo(s.points[0].x, s.points[0].y)
            for (i in 1 until s.points.size) path.lineTo(s.points[i].x, s.points[i].y)
            canvas.drawPath(path, paint)
        }
    }

    val l = crop.left.toInt().coerceIn(0, out.width - 1)
    val t = crop.top.toInt().coerceIn(0, out.height - 1)
    val w = (crop.right.toInt() - l).coerceIn(1, out.width - l)
    val h = (crop.bottom.toInt() - t).coerceIn(1, out.height - t)
    return if (l == 0 && t == 0 && w == out.width && h == out.height) out else Bitmap.createBitmap(out, l, t, w, h)
}
