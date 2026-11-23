package sk.vespal.recshot.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import sk.vespal.recshot.tr

/** Jednoduchý prehrávač zvukovej nahrávky: prehrať/pauza, posun, zdieľať, Wi-Fi/BT. */
@Composable
fun AudioPlayerDialog(uri: Uri, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var showNearby by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (true) {
            if (!dragging) pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
            delay(150)
        }
    }

    if (showNearby) NearbyDialog(listOf(uri), "audio/*") { showNearby = false }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(tr("Zvuková nahrávka", "Audio recording"), color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(C.PurpleFill)
                        .clickable {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            if (player.isPlaying) player.pause() else player.play()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) tr("Pauza", "Pause") else tr("Prehrať", "Play"),
                        tint = Color.White, modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                val frac = if (dur > 0) (if (dragging) dragValue else pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                Slider(
                    value = frac,
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = {
                        player.seekTo((dragValue * dur).toLong())
                        pos = (dragValue * dur).toLong()
                        dragging = false
                    },
                    colors = SliderDefaults.colors(thumbColor = C.Purple, activeTrackColor = C.Purple),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatDuration(if (dragging) (dragValue * dur).toLong() else pos), color = C.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(formatDuration(dur), color = C.TextDim, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction(tr("Zdieľať", "Share"), Icons.Filled.Share, Modifier.weight(1f)) {
                        shareUri(ctx, uri, "audio/*")
                    }
                    SmallAction("Wi-Fi / BT", Icons.Filled.Bluetooth, Modifier.weight(1f)) { showNearby = true }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Zavrieť", "Close"), color = C.Purple) }
        },
    )
}

@Composable
private fun SmallAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(C.PurpleSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = C.Purple, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, color = C.Purple, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
