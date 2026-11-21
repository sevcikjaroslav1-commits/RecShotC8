package sk.vespal.recshot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val Bg = Color(0xFF0E0F13)
    val Surface = Color(0xFF16181E)
    val SurfaceHi = Color(0xFF22252D)
    val Border = Color(0xFF262932)
    val Text = Color(0xFFF2F3F5)
    val TextDim = Color(0xFFA3A7B0)
    val Accent = Color(0xFFE8462A)
    val AccentSoft = Color(0xFF2A1C18)
    val AccentText = Color(0xFFFF8A6E)
    val Blue = Color(0xFF6EA8FF)
    val BlueSoft = Color(0xFF1F2A3D)
    val Trim = Color(0xFFFFC24A)
}

private val scheme = darkColorScheme(
    primary = C.Accent,
    onPrimary = Color.White,
    secondary = C.Blue,
    background = C.Bg,
    onBackground = C.Text,
    surface = C.Surface,
    onSurface = C.Text,
    surfaceVariant = C.SurfaceHi,
    onSurfaceVariant = C.TextDim,
    outline = C.Border,
    secondaryContainer = C.AccentSoft,
    onSecondaryContainer = C.AccentText,
)

@Composable
fun RecShotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
