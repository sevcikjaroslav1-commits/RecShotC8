package sk.vespal.recshot.ui

import sk.vespal.recshot.tr
import android.app.RecoverableSecurityException
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.withContext
import sk.vespal.recshot.GalleryItem
import sk.vespal.recshot.MediaStoreUtil
import sk.vespal.recshot.RecorderService

@Composable
fun GalleryScreen(
    onOpen: (GalleryItem) -> Unit,
    onOpenUri: (Uri, Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = Nahrávky, 1 = Z telefónu
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 všetko, 1 videá, 2 fotky
    var allItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    val saved by RecorderService.savedCounter.collectAsState()
    val selected = remember { mutableStateListOf<Uri>() }
    val selecting = selected.isNotEmpty()
    val scope = rememberCoroutineScope()

    fun refresh() {
        RecorderService.savedCounter.value = RecorderService.savedCounter.value + 1
    }

    // systémové potvrdenie mazania (Android 11+) alebo povolenie pre cudzí súbor (Android 10)
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(ctx, tr("Vymazané", "Deleted"), Toast.LENGTH_SHORT).show()
            selected.clear()
        }
        refresh()
    }

    fun deleteSelected() {
        val uris = selected.toList()
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pi = MediaStore.createDeleteRequest(ctx.contentResolver, uris)
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                Toast.makeText(ctx, tr("Mazanie sa nepodarilo", "Delete failed"), Toast.LENGTH_SHORT).show()
            }
        } else {
            scope.launch {
                var pending: RecoverableSecurityException? = null
                var count = 0
                withContext(Dispatchers.IO) {
                    for (u in uris) {
                        try {
                            count += ctx.contentResolver.delete(u, null, null)
                        } catch (e: RecoverableSecurityException) {
                            if (pending == null) pending = e
                        } catch (_: Exception) {
                        }
                    }
                }
                val p = pending
                if (p != null) {
                    deleteLauncher.launch(IntentSenderRequest.Builder(p.userAction.actionIntent.intentSender).build())
                } else {
                    Toast.makeText(ctx, tr("Vymazané: $count", "Deleted: $count"), Toast.LENGTH_SHORT).show()
                    selected.clear()
                    refresh()
                }
            }
        }
    }

    BackHandler(enabled = selecting) { selected.clear() }

    fun selectedMime(): String {
        val sel = allItems.filter { it.uri in selected }
        return when {
            sel.isNotEmpty() && sel.all { it.isVideo } -> "video/*"
            sel.isNotEmpty() && sel.all { it.isAudio } -> "audio/*"
            sel.isNotEmpty() && sel.none { it.isVideo || it.isAudio } -> "image/*"
            else -> "*/*"
        }
    }
    var showNearby by remember { mutableStateOf(false) }
    var compressUri by remember { mutableStateOf<Uri?>(null) }
    if (showNearby) NearbyDialog(selected.toList(), selectedMime()) { showNearby = false }
    compressUri?.let { u ->
        val isVid = allItems.any { it.uri == u && it.isVideo }
        if (isVid) CompressDialog(u, onDismiss = { compressUri = null; selected.clear() })
        else ImageCompressDialog(u, onDismiss = { compressUri = null; selected.clear() })
    }

    LaunchedEffect(saved) {
        allItems = withContext(Dispatchers.IO) { MediaStoreUtil.list(ctx) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val type = ctx.contentResolver.getType(uri) ?: ""
            onOpenUri(uri, type.startsWith("video"))
        }
    }
    fun pick(kind: ActivityResultContracts.PickVisualMedia.VisualMediaType) {
        picker.launch(PickVisualMediaRequest(kind))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            if (!selecting) {
                Text(
                    tr("Galéria", "Gallery"), color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 14.dp)
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selected.clear() }) {
                        Icon(Icons.Filled.Close, contentDescription = tr("Zrušiť výber", "Clear selection"), tint = C.Text)
                    }
                    Text(
                        "${selected.size}", color = C.Text, fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val visible = allItems.filter { matchesFilter(it, filter) }
                        visible.forEach { if (it.uri !in selected) selected.add(it.uri) }
                    }) {
                        Icon(Icons.Filled.SelectAll, contentDescription = tr("Vybrať všetko", "Select all"), tint = C.Text)
                    }
                    IconButton(onClick = { shareMany(ctx, selected.toList(), selectedMime()) }) {
                        Icon(Icons.Filled.Share, contentDescription = tr("Zdieľať", "Share"), tint = C.Text)
                    }
                    IconButton(onClick = { showNearby = true }) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = tr("Poslať cez Wi-Fi / Bluetooth", "Send via Wi-Fi / Bluetooth"), tint = C.Text)
                    }
                    val singleShrinkable = selected.size == 1 && allItems.any { it.uri == selected[0] && !it.isAudio }
                    if (singleShrinkable) {
                        IconButton(onClick = { compressUri = selected[0] }) {
                            Icon(Icons.Filled.Compress, contentDescription = tr("Komprimovať", "Compress"), tint = C.Text)
                        }
                    }
                    IconButton(onClick = { deleteSelected() }) {
                        Icon(Icons.Filled.Delete, contentDescription = tr("Vymazať", "Delete"), tint = C.AccentText)
                    }
                }
            }

            // záložky
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.Surface)
                    .border(1.dp, Color(0xFF22252D), RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Segment(tr("Nahrávky", "Recordings"), tab == 0, Modifier.weight(1f)) { tab = 0 }
                Segment(tr("Z telefónu", "From phone"), tab == 1, Modifier.weight(1f)) { tab = 1 }
            }

            if (tab == 0) {
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(tr("Všetko", "All"), filter == 0) { filter = 0 }
                    Pill(tr("Videá", "Videos"), filter == 1) { filter = 1 }
                    Pill(tr("Fotky", "Photos"), filter == 2) { filter = 2 }
                    Pill(tr("Zvuk", "Audio"), filter == 3) { filter = 3 }
                }
                val shown = allItems.filter { matchesFilter(it, filter) }
                if (shown.isNotEmpty() && !selecting) {
                    Text(
                        tr("Podrž prst na položke pre označenie a vymazanie", "Long-press an item to select, share or delete"),
                        color = C.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)
                    )
                }
                if (shown.isEmpty()) {
                    Text(
                        tr("Tu sa zobrazia tvoje nahrávky, snímky a zvuky.", "Your recordings, screenshots and audio will appear here."),
                        color = C.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp)
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(top = 14.dp, bottom = 96.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        items(shown, key = { it.uri.toString() }) { item ->
                            val isSel = item.uri in selected
                            Thumb(
                                item,
                                Modifier.aspectRatio(0.66f),
                                selected = isSel,
                                selecting = selecting,
                                onLongClick = { if (!isSel) selected.add(item.uri) },
                            ) {
                                if (selecting) {
                                    if (isSel) selected.remove(item.uri) else selected.add(item.uri)
                                } else {
                                    onOpen(item)
                                }
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF16212F))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFFB9D3FF))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        tr("Videá a fotky z fotoaparátu, WhatsAppu či stiahnuté – vyber súbor a otvorí sa editor.", "Videos and photos from the camera, WhatsApp or downloads – pick a file to open the editor."),
                        color = Color(0xFFB9D3FF), fontSize = 13.sp, lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.height(14.dp))
                PickCard(tr("Vybrať video", "Pick a video"), tr("Strih, vystrihnutie úseku, stlmenie zvuku", "Trim, cut out a part, mute"), Icons.Filled.Videocam) {
                    pick(ActivityResultContracts.PickVisualMedia.VideoOnly)
                }
                Spacer(Modifier.height(10.dp))
                PickCard(tr("Vybrať fotku", "Pick a photo"), tr("Orezanie, kreslenie, rozmazanie", "Crop, draw, blur"), Icons.Filled.Image) {
                    pick(ActivityResultContracts.PickVisualMedia.ImageOnly)
                }
            }
        }

        if (!selecting) ExtendedFloatingActionButton(
            onClick = { pick(ActivityResultContracts.PickVisualMedia.ImageAndVideo) },
            containerColor = C.Accent,
            contentColor = Color.White,
            icon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
            text = { Text(tr("Importovať", "Import"), fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }
}

private fun matchesFilter(it: GalleryItem, filter: Int): Boolean = when (filter) {
    1 -> it.isVideo
    2 -> !it.isVideo && !it.isAudio
    3 -> it.isAudio
    else -> true
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF2A2D36) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else C.TextDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
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
        Text(label, color = if (selected) C.Bg else Color(0xFFC7CAD1), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PickCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(C.Surface)
            .border(1.dp, C.Border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.height(48.dp).width(48.dp).clip(RoundedCornerShape(14.dp)).background(C.BlueSoft),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = C.Blue) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = C.TextDim, fontSize = 12.sp)
        }
    }
}
