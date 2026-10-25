package com.risaina.app.feature.theirwidget

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.ImageCompressor
import com.risaina.app.core.data.MediaRepository
import com.risaina.app.core.data.MemoryRepository
import com.risaina.app.core.data.WidgetBoardRepository
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.components.SectionHeader
import com.risaina.app.core.designsystem.theme.Palette
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.model.MediaCategory
import com.risaina.app.core.model.Memory
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.ping.Expressions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Draft of what my partner's widgets will show. */
data class BoardDraft(val note: String = "", val mood: String? = null, val emoji: String? = null, val photoMediaId: String? = null)

data class TheirWidgetUi(
    val partnerName: String = "them",
    val draft: BoardDraft = BoardDraft(),
    val photos: List<Memory> = emptyList(),
    val uploading: Float? = null,
    val saving: Boolean = false,
    val loaded: Boolean = false,
)

@HiltViewModel
class TheirWidgetViewModel @Inject constructor(
    private val boards: WidgetBoardRepository,
    private val media: MediaRepository,
    private val compressor: ImageCompressor,
    memories: MemoryRepository,
    couple: CoupleRepository,
) : ViewModel() {
    private val draft = MutableStateFlow(BoardDraft())
    private val busy = MutableStateFlow<Pair<Float?, Boolean>>(null to false)
    private val loaded = MutableStateFlow(false)
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()

    val state: StateFlow<TheirWidgetUi> = combine(draft, memories.all, couple.partner, busy, loaded) { d, mems, p, b, l ->
        TheirWidgetUi(p?.displayName ?: "them", d, mems.take(30), b.first, b.second, l)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TheirWidgetUi())

    init {
        viewModelScope.launch {
            boards.refresh()
            boards.mine.first()?.let { m -> draft.value = BoardDraft(m.note ?: "", m.mood, m.emoji, m.photoMediaId) }
            loaded.value = true
        }
    }

    fun setNote(v: String) = draft.update { it.copy(note = v.take(140)) }
    fun setMood(v: String?) = draft.update { it.copy(mood = if (it.mood == v) null else v) }
    fun setEmoji(v: String?) = draft.update { it.copy(emoji = if (it.emoji == v) null else v) }
    fun setPhoto(id: String?) = draft.update { it.copy(photoMediaId = if (it.photoMediaId == id) null else id) }
    suspend fun image(mediaId: String): Any? = media.localFile(mediaId) ?: media.ensureCached(mediaId, "image/jpeg").getOrNull()

    fun uploadPhoto(uri: Uri) {
        viewModelScope.launch {
            busy.value = 0f to false
            runCatching { compressor.compress(uri) }.onSuccess { c ->
                when (val r = media.upload(MediaCategory.PHOTO, c.file, c.mimeType, width = c.width, height = c.height) { p -> busy.value = p to false }) {
                    is AppResult.Success -> draft.update { it.copy(photoMediaId = r.value.id) }
                    is AppResult.Failure -> effects.trySend(r.error.message ?: "Upload failed")
                }
                c.file.delete()
            }.onFailure { effects.trySend("Couldn't read that photo") }
            busy.value = null to false
        }
    }

    fun publish(onDone: () -> Unit) {
        val d = draft.value
        viewModelScope.launch {
            busy.value = null to true
            when (val r = boards.publish(d.note, d.emoji, d.mood, null)) {
                is AppResult.Success -> { effects.trySend("Sent to ${state.value.partnerName}'s widgets"); onDone() }
                is AppResult.Failure -> effects.trySend(r.error.message ?: "Couldn't update")
            }
            busy.value = null to false
        }
    }

    fun clearAll() { draft.value = BoardDraft() }
}

private val MOODS = listOf("Missing you", "Thinking of you", "Can't wait to see you", "Proud of you", "Sleepy, love you", "Busy but yours", "Call me?", "Sending a hug")

@Composable
fun TheirWidgetScreen(onBack: () -> Unit, vm: TheirWidgetViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::uploadPhoto) }
    val d = s.draft
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${s.partnerName}'s widgets", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Your mood, a note and an emoji for ${s.partnerName}'s widgets and lock-screen card.", style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted)
            Spacer(Modifier.height(16.dp))
            // Live preview of their photo widget
            val preview by produceState<Any?>(null, d.photoMediaId) { value = d.photoMediaId?.let { vm.image(it) } }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(MaterialTheme.shapes.extraLarge).background(Brush.linearGradient(listOf(Risaina.colors.heroGradientStart, Risaina.colors.heroGradientEnd)))) {
                if (preview != null) AsyncImage(model = preview, contentDescription = "Widget photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    if (d.emoji != null) Text(d.emoji, fontSize = 34.sp)
                    Text(d.note.ifBlank { d.mood ?: "Your words appear here" }, style = RisainaTextStyles.noteSerif, color = Palette.WarmIvory)
                }
            }
            Spacer(Modifier.height(20.dp))
            SectionHeader("How are you feeling?")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MOODS.forEach { m -> FilterChip(selected = d.mood == m, onClick = { vm.setMood(m) }, label = { Text(m) }) } }
            Spacer(Modifier.height(16.dp))
            RisainaTextField(d.note, vm::setNote, "A little note (optional)", singleLine = false, minLines = 2, supporting = "${d.note.length}/140")
            Spacer(Modifier.height(16.dp))
            SectionHeader("An emoji")
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Expressions.groups.flatMap { it.emoji }.distinct()) { e ->
                    Box(Modifier.size(46.dp).clip(MaterialTheme.shapes.small).background(if (d.emoji == e) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh).clickable { vm.setEmoji(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Their photo widget shows the latest photo you send in chat, so just send one there.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Send to ${s.partnerName}'s widgets", onClick = { vm.publish(onBack) }, loading = s.saving, enabled = s.loaded && s.uploading == null, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { GhostButton("Clear everything", onClick = vm::clearAll) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

