package com.risaina.app.feature.memories

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.EmptyState
import com.risaina.app.core.designsystem.components.LoadingState
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.components.SecondaryButton
import com.risaina.app.core.designsystem.theme.Palette
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.model.Memory
import com.risaina.app.feature.common.AddIcon
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.DeleteIcon
import com.risaina.app.feature.common.Format
import com.risaina.app.feature.common.PushPinIcon
import com.risaina.app.feature.common.WallpaperIcon
import java.time.Instant

@Composable
fun MemoriesScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit, vm: MemoriesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memories", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onAdd, containerColor = MaterialTheme.colorScheme.primary) { Icon(AddIcon, contentDescription = "Add a memory") } },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            !s.loaded && s.memories.isEmpty() -> LoadingState(Modifier.padding(padding))
            s.memories.isEmpty() -> EmptyState(
                title = "A private album, for two", body = "Add a photo you both love. Pin one and it becomes the picture on your home screen and widget.",
                modifier = Modifier.padding(padding).fillMaxSize(), action = { SecondaryButton("Add the first photo", onClick = onAdd) },
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(s.memories, key = { it.id }) { m -> MemoryTile(m, image = { vm.image(m) }, onClick = { onOpen(m.id) }) }
            }
        }
    }
}

@Composable
private fun MemoryTile(m: Memory, image: suspend () -> Any?, onClick: () -> Unit) {
    val model by produceState<Any?>(initialValue = null, key1 = m.mediaId) { value = image() }
    Box(Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick)) {
        if (model != null) AsyncImage(model = model, contentDescription = m.caption ?: "Memory", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (m.pinned) Icon(PushPinIcon, contentDescription = "Pinned", tint = Palette.WarmIvory, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp))
        if (!m.caption.isNullOrBlank()) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))).padding(10.dp)) {
                Text(m.caption, style = MaterialTheme.typography.labelMedium, color = Palette.WarmIvory, maxLines = 2)
            }
        }
    }
}

@Composable
fun MemoryDetailScreen(id: String, onBack: () -> Unit, onWallpaper: (String) -> Unit, vm: MemoriesViewModel = hiltViewModel()) {
    val memory by vm.memory(id).collectAsStateWithLifecycle(initialValue = null)
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var editCaption by remember { mutableStateOf<String?>(null) }
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    val m = memory
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                actions = {
                    if (m != null) {
                        IconButton(onClick = { vm.setPinned(m, !m.pinned) }) { Icon(PushPinIcon, contentDescription = if (m.pinned) "Unpin" else "Pin as favourite", tint = if (m.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { onWallpaper(m.id) }) { Icon(WallpaperIcon, contentDescription = "Use in Wallpaper Studio") }
                        if (m.createdBy == s.myId) IconButton(onClick = { confirmDelete = true }) { Icon(DeleteIcon, contentDescription = "Delete") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (m == null) { LoadingState(Modifier.padding(padding)); return@Scaffold }
        val model by produceState<Any?>(initialValue = null, key1 = m.mediaId) { value = vm.image(m) }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).padding(horizontal = 16.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                if (model != null) AsyncImage(model = model, contentDescription = m.caption ?: "Memory", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text(m.caption?.ifBlank { null } ?: "Add a caption", style = RisainaTextStyles.noteSerif.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize),
                    color = if (m.caption.isNullOrBlank()) Risaina.colors.textMuted else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { editCaption = m.caption ?: "" })
                Spacer(Modifier.height(8.dp))
                Text(Format.date((m.takenAt ?: m.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
                if (m.pinned) Text("Pinned · shows on home and the photo widget", style = RisainaTextStyles.timestamp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (confirmDelete && m != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this memory?") }, text = { Text("The photo is removed for both of you and deleted from the server.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(m) { onBack() } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
    editCaption?.let { current ->
        var text by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editCaption = null },
            title = { Text("Caption") },
            text = { RisainaTextField(text, { text = it.take(200) }, "Caption", singleLine = false, minLines = 2) },
            confirmButton = { TextButton(onClick = { m?.let { vm.setCaption(it, text) }; editCaption = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editCaption = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun AddMemoryScreen(onBack: () -> Unit, onDone: () -> Unit, vm: MemoriesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var uri by remember { mutableStateOf<android.net.Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri = it }
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New memory", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) {
                if (uri != null) AsyncImage(model = uri, contentDescription = "Selected photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text("Choose a photo", style = MaterialTheme.typography.titleMedium, color = Risaina.colors.textMuted)
            }
            Spacer(Modifier.height(16.dp))
            RisainaTextField(caption, { caption = it.take(200) }, "Caption (optional)", singleLine = false, minLines = 2)
            Spacer(Modifier.height(16.dp))
            if (s.uploading != null) { LinearProgressIndicator(progress = { s.uploading ?: 0f }, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(12.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Change photo", onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = s.uploading == null)
                PrimaryButton("Save", enabled = uri != null, loading = s.uploading != null, onClick = { uri?.let { vm.add(it, caption.ifBlank { null }, Instant.now(), onDone) } }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Photos are resized to 2048 px and stripped of location data before upload.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
    }
}
