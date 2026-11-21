package sk.vespal.recshot.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.vespal.recshot.CaptureMode
import sk.vespal.recshot.GalleryItem
import sk.vespal.recshot.Screen

@Composable
fun AppRoot(
    screen: Screen,
    onNavigate: (Screen) -> Unit,
    onTab: (Screen) -> Unit,
    onBack: () -> Unit,
    onCapture: (CaptureMode) -> Unit,
) {
    when (screen) {
        is Screen.VideoEditor -> VideoEditorScreen(uri = screen.uri, onBack = onBack, onOpenGallery = { onTab(Screen.Gallery) })
        is Screen.ImageEditor -> ImageEditorScreen(uri = screen.uri, onBack = onBack)
        else -> Scaffold(
            containerColor = C.Bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF121318)) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = C.AccentText,
                        selectedTextColor = C.AccentText,
                        indicatorColor = C.AccentSoft,
                        unselectedIconColor = Color(0xFF8C909A),
                        unselectedTextColor = Color(0xFF8C909A),
                    )
                    NavigationBarItem(
                        selected = screen == Screen.Home,
                        onClick = { onTab(Screen.Home) },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Domov") },
                        colors = colors,
                    )
                    NavigationBarItem(
                        selected = screen == Screen.Gallery,
                        onClick = { onTab(Screen.Gallery) },
                        icon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                        label = { Text("Galéria") },
                        colors = colors,
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (screen == Screen.Gallery) {
                    GalleryScreen(onOpen = { openItem(it, onNavigate) }, onOpenUri = { uri, video ->
                        onNavigate(if (video) Screen.VideoEditor(uri) else Screen.ImageEditor(uri))
                    })
                } else {
                    HomeScreen(
                        onCapture = onCapture,
                        onOpen = { openItem(it, onNavigate) },
                        onOpenUri = { uri, video ->
                            onNavigate(if (video) Screen.VideoEditor(uri) else Screen.ImageEditor(uri))
                        },
                        onShowAll = { onTab(Screen.Gallery) },
                    )
                }
            }
        }
    }
}

private fun openItem(item: GalleryItem, onNavigate: (Screen) -> Unit) {
    onNavigate(if (item.isVideo) Screen.VideoEditor(item.uri) else Screen.ImageEditor(item.uri))
}

/** Náhľad položky galérie (načítaný z MediaStore na pozadí). */
@Composable
fun Thumb(item: GalleryItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(item.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.uri) {
        bmp = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.loadThumbnail(item.uri, Size(360, 360), null).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(C.SurfaceHi)
            .border(1.dp, Color(0xFF22252D), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        bmp?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x99000000))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (item.isVideo) Icons.Filled.PlayArrow else Icons.Filled.Image,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                if (item.isVideo) formatDuration(item.durationMs) else "PNG",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

fun shareUri(ctx: Context, uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Zdieľať").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
