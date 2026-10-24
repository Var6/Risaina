package com.risaina.app.feature.wallpaper

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.components.SectionHeader
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.feature.common.BackIcon

/** Compose a wallpaper from a shared photo, a note, an emoji and a date. Nothing is applied without confirmation. */
@Composable
fun WallpaperStudioScreen(memoryId: String?, onBack: () -> Unit, vm: WallpaperViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    var confirmApply by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val unused = memoryId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallpaper Studio", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            // Preview at the phone's aspect ratio
            Box(Modifier.fillMaxWidth(0.62f).align(Alignment.CenterHorizontally).aspectRatio(9f / 19.5f).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraLarge), contentAlignment = Alignment.Center) {
                s.preview?.let { Image(it.asImageBitmap(), contentDescription = "Wallpaper preview", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (s.rendering) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(20.dp))
            SectionHeader("Photo")
            Spacer(Modifier.height(8.dp))
            if (s.memories.isEmpty()) Text("No shared photos yet. The brand gradient is used instead.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer).border(if (s.selected == null) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium).clickable { vm.clearPhoto() }, contentAlignment = Alignment.Center) { Text("None", style = MaterialTheme.typography.labelSmall) }
                }
                items(s.memories, key = { it.id }) { m ->
                    val model by produceState<Any?>(null, m.mediaId) { value = m }
                    Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer).border(if (s.selected?.id == m.id) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium).clickable { vm.select(m) }) {
                        Thumb(m.mediaId)
                        @Suppress("UNUSED_EXPRESSION") model
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader("Words")
            Spacer(Modifier.height(8.dp))
            RisainaTextField(s.note, vm::onNote, "A line you both love", singleLine = false, minLines = 2)
            if (s.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(s.suggestions) { AssistChip(onClick = { vm.onNote(it) }, label = { Text(it.take(28) + if (it.length > 28) "…" else "") }) } }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RisainaTextField(s.emoji, vm::onEmoji, "Emoji", modifier = Modifier.width(110.dp))
                Spacer(Modifier.width(16.dp))
                Text("Show date", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = s.showDate, onCheckedChange = vm::onShowDate)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Save image", onClick = vm::save, enabled = !s.busy, modifier = Modifier.weight(1f))
                PrimaryButton("Set as wallpaper", onClick = { confirmApply = true }, loading = s.busy, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Saving writes a PNG to Pictures/Risaina. Setting the wallpaper asks you to confirm and uses Android's wallpaper service; some launchers and lock screens may apply their own cropping.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            Spacer(Modifier.height(40.dp))
        }
    }
    if (confirmApply) AlertDialog(
        onDismissRequest = { confirmApply = false },
        title = { Text("Set this as your wallpaper?") },
        text = { Text("Choose where to apply it. Your current wallpaper will be replaced.") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { confirmApply = false; vm.apply(ApplyTarget.BOTH) }) { Text("Home & lock screen") }
                TextButton(onClick = { confirmApply = false; vm.apply(ApplyTarget.HOME) }) { Text("Home screen only") }
                TextButton(onClick = { confirmApply = false; vm.apply(ApplyTarget.LOCK) }) { Text("Lock screen only") }
            }
        },
        dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Thumb(mediaId: String, vm: com.risaina.app.feature.memories.MemoriesViewModel = hiltViewModel()) {
    val model by produceState<Any?>(null, mediaId) {
        value = vm.state.value.memories.firstOrNull { it.mediaId == mediaId }?.let { vm.image(it) }
    }
    if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}
