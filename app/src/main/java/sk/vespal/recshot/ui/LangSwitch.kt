package sk.vespal.recshot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sk.vespal.recshot.Lang

/** Prepínač jazyka SK / EN s vlajkami. */
@Composable
fun LangSwitch() {
    val ctx = LocalContext.current
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .border(1.dp, C.Border, RoundedCornerShape(14.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        LangButton("SK", Lang.code == "sk", { slovakFlag() }) { Lang.set(ctx, "sk") }
        LangButton("EN", Lang.code == "en", { ukFlag() }) { Lang.set(ctx, "en") }
    }
}

@Composable
private fun LangButton(code: String, selected: Boolean, flag: DrawScope.() -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Color(0xFF2A2D36) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 21.dp, height = 14.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(0.5.dp, Color(0x40FFFFFF), RoundedCornerShape(3.dp))
        ) {
            Canvas(Modifier.size(width = 21.dp, height = 14.dp)) { flag() }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            code,
            color = if (selected) Color.White else Color(0xFF8C909A),
            fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
    }
}

/** Slovenská vlajka: biela, modrá, červená + zjednodušený štít s dvojkrížom. */
private fun DrawScope.slovakFlag() {
    val w = size.width
    val h = size.height
    drawRect(Color.White, Offset.Zero, Size(w, h / 3f))
    drawRect(Color(0xFF0B4EA2), Offset(0f, h / 3f), Size(w, h / 3f))
    drawRect(Color(0xFFEE1C25), Offset(0f, 2 * h / 3f), Size(w, h / 3f))
    // štít
    val sx = w * 0.22f
    val sw = w * 0.26f
    val sy = h * 0.2f
    val sh = h * 0.62f
    val shield = Path().apply {
        moveTo(sx, sy)
        lineTo(sx + sw, sy)
        lineTo(sx + sw, sy + sh * 0.6f)
        quadraticTo(sx + sw, sy + sh * 0.9f, sx + sw / 2f, sy + sh)
        quadraticTo(sx, sy + sh * 0.9f, sx, sy + sh * 0.6f)
        close()
    }
    drawPath(shield, Color.White)
    val inset = w * 0.015f
    val inner = Path().apply {
        moveTo(sx + inset, sy + inset)
        lineTo(sx + sw - inset, sy + inset)
        lineTo(sx + sw - inset, sy + sh * 0.6f)
        quadraticTo(sx + sw - inset, sy + sh * 0.88f, sx + sw / 2f, sy + sh - inset * 1.5f)
        quadraticTo(sx + inset, sy + sh * 0.88f, sx + inset, sy + sh * 0.6f)
        close()
    }
    drawPath(inner, Color(0xFFEE1C25))
    // dvojkríž
    val cx = sx + sw / 2f
    val cw = sw * 0.12f
    drawRect(Color.White, Offset(cx - cw / 2f, sy + sh * 0.12f), Size(cw, sh * 0.62f))
    drawRect(Color.White, Offset(cx - sw * 0.28f, sy + sh * 0.25f), Size(sw * 0.56f, cw))
    drawRect(Color.White, Offset(cx - sw * 0.36f, sy + sh * 0.43f), Size(sw * 0.72f, cw))
    // modré vrchy
    drawCircle(Color(0xFF0B4EA2), radius = sw * 0.17f, center = Offset(cx, sy + sh * 0.86f))
}

/** Britská vlajka (zjednodušená). */
private fun DrawScope.ukFlag() {
    val w = size.width
    val h = size.height
    drawRect(Color(0xFF012169))
    val white = Color.White
    val red = Color(0xFFC8102E)
    drawLine(white, Offset(0f, 0f), Offset(w, h), strokeWidth = h * 0.2f)
    drawLine(white, Offset(w, 0f), Offset(0f, h), strokeWidth = h * 0.2f)
    drawLine(red, Offset(0f, 0f), Offset(w, h), strokeWidth = h * 0.07f)
    drawLine(red, Offset(w, 0f), Offset(0f, h), strokeWidth = h * 0.07f)
    drawRect(white, Offset(w / 2f - h * 0.17f, 0f), Size(h * 0.34f, h))
    drawRect(white, Offset(0f, h / 2f - h * 0.17f), Size(w, h * 0.34f))
    drawRect(red, Offset(w / 2f - h * 0.1f, 0f), Size(h * 0.2f, h))
    drawRect(red, Offset(0f, h / 2f - h * 0.1f), Size(w, h * 0.2f))
}
