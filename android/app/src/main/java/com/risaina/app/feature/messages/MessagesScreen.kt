package com.risaina.app.feature.messages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.Avatar
import com.risaina.app.core.designsystem.components.EmptyState
import com.risaina.app.core.designsystem.theme.FrauncesText
import com.risaina.app.core.designsystem.theme.PillShape
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.model.Message
import com.risaina.app.core.model.MessageSendState
import com.risaina.app.core.model.MessageType
import com.risaina.app.core.network.SocketState
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.Format
import com.risaina.app.feature.common.ImageIcon
import com.risaina.app.feature.common.MicIcon
import com.risaina.app.feature.common.PauseIcon
import com.risaina.app.feature.common.PlayIcon
import com.risaina.app.feature.common.SendIcon
import com.risaina.app.feature.voice.VoiceRecorderSheet
import kotlinx.coroutines.delay
import java.time.ZoneId

@Composable
fun MessagesScreen(onBack: () -> Unit, openRecorder: Boolean = false, vm: MessagesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(openRecorder) { if (openRecorder) vm.openRecorder() }
    LaunchedEffect(s.playback.id, s.playback.playing) { while (s.playback.playing) { vm.tickPlayback(); delay(250) } }

    val listState = rememberLazyListState()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::sendPhoto) }
    var toDelete by remember { mutableStateOf<Message?>(null) }

    // Mark partner messages read as they become visible.
    LaunchedEffect(s.messages.size, listState.firstVisibleItemIndex) {
        listState.layoutInfo.visibleItemsInfo.mapNotNull { info -> s.messages.getOrNull(info.index) }.forEach(vm::markRead)
    }
    // Infinite scroll towards the past.
    LaunchedEffect(listState.firstVisibleItemIndex, s.messages.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (s.messages.isNotEmpty() && last >= s.messages.size - 5) vm.loadOlder()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name = s.partner?.displayName, model = null, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(s.partner?.displayName ?: "—", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when (s.socket) { SocketState.LIVE -> "Connected"; SocketState.SYNCING, SocketState.CONNECTING -> "Connecting…"; SocketState.DISCONNECTED -> "Will sync when online" },
                                style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted,
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Composer(
                text = s.composer, onText = vm::onComposer, onSend = vm::send,
                onPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onMic = vm::openRecorder, uploading = s.sendingMedia,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (s.messages.isEmpty()) {
            EmptyState(
                title = "Nothing here yet", body = "Write the first line. Short is fine — it's the showing up that counts.",
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        } else {
            LazyColumn(
                state = listState, reverseLayout = true,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(s.messages, key = { _, m -> m.id }) { index, m ->
                    val mine = m.senderId == s.myId
                    val prev = s.messages.getOrNull(index + 1) // older (reverse layout)
                    val showDay = prev == null || prev.createdAt.atZone(ZoneId.systemDefault()).toLocalDate() != m.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()
                    Column {
                        if (showDay) DaySeparator(Format.day(m.createdAt))
                        Bubble(
                            m = m, mine = mine, playing = s.playback.id == m.id && s.playback.playing,
                            progress = if (s.playback.id == m.id) s.playback.progress else 0f,
                            resolve = { vm.resolveImage(m) },
                            onPlay = { vm.togglePlay(m) },
                            onLongPress = { if (mine && m.sendState == MessageSendState.SENT) toDelete = m },
                            onRetry = { vm.retry(m) }, onDiscard = { vm.discard(m) },
                        )
                    }
                }
                if (s.loadingMore) item { Text("Loading earlier…", style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            }
        }
    }

    if (s.showRecorder) {
        VoiceRecorderSheet(
            recorderState = s.recorder, supportsPause = vm.recorder.supportsPause, playback = s.playback, uploading = s.sendingMedia,
            onStart = vm::startRecording, onPause = vm::pauseRecording, onResume = vm::resumeRecording, onStop = vm::stopRecording,
            onCancel = vm::cancelRecording, onPreviewToggle = vm::togglePreview, onReRecord = vm::reRecord, onSend = vm::sendRecording, onTick = vm::tickRecording,
        )
    }
    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this message?") },
            text = { Text("It will be removed for both of you. Attached photos or voice notes are deleted too.") },
            confirmButton = { TextButton(onClick = { vm.delete(m); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label.uppercase(), style = RisainaTextStyles.overline, color = Risaina.colors.textMuted)
    }
}

@Composable
private fun Bubble(
    m: Message, mine: Boolean, playing: Boolean, progress: Float,
    resolve: suspend () -> Any?, onPlay: () -> Unit, onLongPress: () -> Unit, onRetry: () -> Unit, onDiscard: () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (mine) 20.dp else 6.dp, bottomEnd = if (mine) 6.dp else 20.dp)
    val container = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val onContainer = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Box(
            Modifier.widthIn(max = 300.dp).clip(shape).background(container)
                .combinedClickable(onClick = { if (m.type == MessageType.VOICE) onPlay() }, onLongClick = onLongPress)
                .padding(if (m.type == MessageType.PHOTO) 4.dp else 14.dp),
        ) {
            when (m.type) {
                MessageType.TEXT -> Text(m.text ?: "", style = MaterialTheme.typography.bodyLarge, color = onContainer)
                MessageType.EMOJI -> Text(m.text ?: "", style = RisainaTextStyles.emojiLarge)
                MessageType.PHOTO -> {
                    val model by produceState<Any?>(initialValue = m.localMediaPath, key1 = m.id) { value = resolve() ?: m.localMediaPath }
                    Box(Modifier.width(260.dp).aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        if (model != null) AsyncImage(model = model, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(ImageIcon, null, Modifier.align(Alignment.Center).size(28.dp), tint = Risaina.colors.textMuted)
                    }
                }
                MessageType.VOICE -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(220.dp)) {
                    Box(Modifier.size(40.dp).clip(PillShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(if (playing) PauseIcon else PlayIcon, contentDescription = if (playing) "Pause" else "Play voice note", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(PillShape), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(6.dp))
                        Text("Voice note", style = RisainaTextStyles.timestamp, color = onContainer.copy(alpha = 0.8f))
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Format.time(m.createdAt), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
            if (mine) {
                Spacer(Modifier.width(6.dp))
                when (m.sendState) {
                    MessageSendState.PENDING -> Text("Sending", style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
                    MessageSendState.FAILED -> Row {
                        Text("Failed · ", style = RisainaTextStyles.timestamp, color = MaterialTheme.colorScheme.error)
                        Text("Retry", style = RisainaTextStyles.timestamp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onRetry))
                        Text(" · Discard", style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted, modifier = Modifier.clickable(onClick = onDiscard))
                    }
                    MessageSendState.SENT -> if (m.readAt != null) Text("Read", style = RisainaTextStyles.timestamp, color = Risaina.colors.success)
                }
            }
        }
    }
}

@Composable
private fun Composer(text: String, onText: (String) -> Unit, onSend: () -> Unit, onPhoto: () -> Unit, onMic: () -> Unit, uploading: Float?) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding()) {
        if (uploading != null) LinearProgressIndicator(progress = { uploading }, modifier = Modifier.fillMaxWidth().height(2.dp))
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onPhoto, enabled = uploading == null) { Icon(ImageIcon, contentDescription = "Send a photo") }
            TextField(
                value = text, onValueChange = onText, modifier = Modifier.weight(1f),
                placeholder = { Text("Write something small", fontFamily = FrauncesText) },
                shape = RoundedCornerShape(24.dp), maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                ),
            )
            if (text.isBlank()) {
                IconButton(onClick = onMic, enabled = uploading == null) { Icon(MicIcon, contentDescription = "Record a voice note") }
            } else {
                IconButton(onClick = onSend) { Icon(SendIcon, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}
