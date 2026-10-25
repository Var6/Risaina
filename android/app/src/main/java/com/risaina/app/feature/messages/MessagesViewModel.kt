package com.risaina.app.feature.messages

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.ImageCompressor
import com.risaina.app.core.data.MediaRepository
import com.risaina.app.core.data.MessageRepository
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.domain.MessageRules
import com.risaina.app.core.model.MediaCategory
import com.risaina.app.core.model.Message
import com.risaina.app.core.model.MessageType
import com.risaina.app.core.model.Partner
import com.risaina.app.core.network.SocketState
import com.risaina.app.core.sync.EventSyncManager
import com.risaina.app.feature.voice.PlaybackState
import com.risaina.app.feature.voice.VoicePlaybackController
import com.risaina.app.feature.voice.VoiceRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class MessagesUiState(
    /** Newest first: the screen uses reverseLayout, so index 0 sits at the bottom, under older ones. */
    val messages: List<Message> = emptyList(),
    val myId: String? = null,
    val partner: Partner? = null,
    val composer: String = "",
    val sendingMedia: Float? = null,                 // upload progress 0..1 while a photo/voice is uploading
    val canLoadMore: Boolean = true,
    val loadingMore: Boolean = false,
    val showRecorder: Boolean = false,
    val recorder: VoiceRecorder.State = VoiceRecorder.State.Idle,
    val playback: PlaybackState = PlaybackState(),
    val socket: SocketState = SocketState.DISCONNECTED,
    val wallpaper: String = "DEFAULT",
    /** Shared wallpaper photo (media id); loaded via the shared media cache. */
    val wallpaperMediaId: String? = null,
    val games: List<com.risaina.app.core.network.GameDto> = emptyList(),
    val wallpaperBusy: Boolean = false,
)

private data class Local(
    val composer: String = "",
    val sendingMedia: Float? = null,
    val canLoadMore: Boolean = true,
    val loadingMore: Boolean = false,
    val showRecorder: Boolean = false,
)

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val mediaRepository: MediaRepository,
    private val imageCompressor: ImageCompressor,
    private val coupleRepository: CoupleRepository,
    private val sessionStore: SessionStore,
    private val syncManager: EventSyncManager,
    val recorder: VoiceRecorder,
    val player: VoicePlaybackController,
    private val preferencesStore: com.risaina.app.core.datastore.PreferencesStore,
    gameRepo: com.risaina.app.core.data.GameRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    private val wallpaperBusy = MutableStateFlow(false)
    private val gameRepository = gameRepo

    private val local = MutableStateFlow(Local())
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()

    val state: StateFlow<MessagesUiState> = combine(
        messageRepository.recent(300), sessionStore.session, coupleRepository.partner, local,
        combine(recorder.state, player.state, syncManager.socketState) { r, p, s -> Triple(r, p, s) },
        coupleRepository.couple, combine(gameRepo.games, wallpaperBusy) { g, b -> g to b },
    ) { arr ->
        @Suppress("UNCHECKED_CAST") val messages = arr[0] as List<Message>
        val session = arr[1] as com.risaina.app.core.model.Session?
        val partner = arr[2] as Partner?
        val l = arr[3] as Local
        @Suppress("UNCHECKED_CAST") val (rec, play, socket) = arr[4] as Triple<VoiceRecorder.State, PlaybackState, SocketState>
        val couple = arr[5] as com.risaina.app.core.model.Couple?
        @Suppress("UNCHECKED_CAST") val (games, wpBusy) = arr[6] as Pair<List<com.risaina.app.core.network.GameDto>, Boolean>
        MessagesUiState(
            messages = messages, myId = session?.userId, partner = partner,
            composer = l.composer, sendingMedia = l.sendingMedia, canLoadMore = l.canLoadMore, loadingMore = l.loadingMore,
            showRecorder = l.showRecorder, recorder = rec, playback = play, socket = socket,
            wallpaper = couple?.chatWallpaperStyle ?: "DEFAULT", wallpaperMediaId = couple?.chatWallpaperMediaId,
            games = games, wallpaperBusy = wpBusy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    init { viewModelScope.launch { messageRepository.refresh() }; viewModelScope.launch { gameRepository.refresh() } }

    fun onComposer(v: String) = local.update { it.copy(composer = v) }

    /** Shared with the partner: both chats change. */
    fun setWallpaper(style: String) {
        viewModelScope.launch {
            wallpaperBusy.value = true
            coupleRepository.setChatWallpaper(if (style == "DEFAULT") null else style)
                .onSuccess { effects.trySend("Chat wallpaper changed for both of you") }
                .onFailure { effects.trySend(it.message ?: "Couldn't change the wallpaper") }
            wallpaperBusy.value = false
        }
    }

    fun setWallpaperPhoto(uri: Uri) {
        viewModelScope.launch {
            wallpaperBusy.value = true
            try {
                val c = imageCompressor.compress(uri, maxEdge = 1600, quality = 82)
                when (val up = mediaRepository.upload(com.risaina.app.core.model.MediaCategory.WALLPAPER, c.file, c.mimeType, width = c.width, height = c.height)) {
                    is AppResult.Success -> coupleRepository.setChatWallpaper("PHOTO", up.value.id)
                        .onSuccess { effects.trySend("Chat wallpaper changed for both of you") }
                        .onFailure { effects.trySend(it.message ?: "Couldn't change the wallpaper") }
                    is AppResult.Failure -> effects.trySend(up.error.message ?: "Upload failed")
                }
                c.file.delete()
            } catch (e: Exception) { effects.trySend("Couldn't use that photo") }
            wallpaperBusy.value = false
        }
    }

    // ---- rock–paper–scissors ----
    fun startGame() {
        viewModelScope.launch {
            when (val r = gameRepository.start()) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> effects.trySend(
                    if ((r.error as? com.risaina.app.core.common.AppError.Conflict)?.code == "game_in_progress") "Finish the current game first ✊✋✌️" else r.error.message ?: "Couldn't start a game",
                )
            }
        }
    }
    fun gameMove(id: String, move: String) { viewModelScope.launch { gameRepository.move(id, move).onFailure { effects.trySend(it.message ?: "Couldn't play") } } }
    fun gameRematch(id: String) { viewModelScope.launch { gameRepository.rematch(id).onFailure { effects.trySend(it.message ?: "Couldn't start a rematch") } } }
    fun gameChallenge(id: String, truth: String, dare: String) { viewModelScope.launch { gameRepository.challenge(id, truth, dare).onFailure { effects.trySend(it.message ?: "Couldn't send") } } }
    fun gameChoose(id: String, kind: String) { viewModelScope.launch { gameRepository.choose(id, kind).onFailure { effects.trySend(it.message ?: "Couldn't choose") } } }
    fun gameConfirm(id: String) { viewModelScope.launch { gameRepository.confirm(id).onFailure { effects.trySend(it.message ?: "Couldn't confirm") } } }
    fun gameReject(id: String, note: String?) { viewModelScope.launch { gameRepository.reject(id, note).onFailure { effects.trySend(it.message ?: "Couldn't send") } } }
    fun gameComplete(id: String, answer: String?) { viewModelScope.launch { gameRepository.complete(id, answer).onFailure { effects.trySend(it.message ?: "Couldn't mark done") } } }
    fun gameCancel(id: String) { viewModelScope.launch { gameRepository.cancel(id) } }
    suspend fun gameSuggestions() = gameRepository.suggestions()

    fun send() {
        val text = local.value.composer.trim()
        if (text.isEmpty()) return
        local.update { it.copy(composer = "") }
        viewModelScope.launch {
            val type = if (isOnlyEmoji(text)) MessageType.EMOJI else MessageType.TEXT
            when (val r = messageRepository.sendText(text, type)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> if (!r.error.isRetryable) effects.trySend(r.error.message ?: "Couldn't send")
            }
        }
    }

    fun sendEmoji(emoji: String) {
        viewModelScope.launch {
            val r = messageRepository.sendText(emoji, MessageType.EMOJI)
            if (r is AppResult.Failure && !r.error.isRetryable) effects.trySend(r.error.message ?: "Couldn't send")
        }
    }

    fun sendPhoto(uri: Uri, viewOnce: Boolean = false) {
        viewModelScope.launch {
            local.update { it.copy(sendingMedia = 0f) }
            try {
                val compressed = imageCompressor.compress(uri)
                mediaRepository.sizeError(MediaCategory.PHOTO, compressed.file.length())?.let { effects.trySend(it.message ?: "Too large"); return@launch }
                val up = mediaRepository.upload(MediaCategory.PHOTO, compressed.file, compressed.mimeType, width = compressed.width, height = compressed.height) { p ->
                    local.update { it.copy(sendingMedia = p) }
                }
                when (up) {
                    is AppResult.Success -> {
                        val local = mediaRepository.localFile(up.value.id)
                        messageRepository.sendMedia(MessageType.PHOTO, up.value.id, local?.absolutePath, viewOnce)
                    }
                    is AppResult.Failure -> effects.trySend(up.error.message ?: "Upload failed")
                }
                compressed.file.delete()
            } catch (e: Exception) {
                effects.trySend("Couldn't read that photo")
            } finally {
                local.update { it.copy(sendingMedia = null) }
            }
        }
    }

    // ---- voice ----
    fun openRecorder() = local.update { it.copy(showRecorder = true) }
    fun startRecording() = recorder.start()
    fun pauseRecording() = recorder.pause()
    fun resumeRecording() = recorder.resume()
    fun stopRecording() = recorder.stop()
    fun tickRecording() = recorder.tick()
    fun cancelRecording() { player.stop(); recorder.cancel(); local.update { it.copy(showRecorder = false) } }
    fun reRecord() { player.stop(); recorder.cancel(); recorder.start() }
    fun togglePreview() { (recorder.state.value as? VoiceRecorder.State.Recorded)?.let { player.toggle("preview", it.file) } }

    fun sendRecording() {
        val rec = recorder.state.value as? VoiceRecorder.State.Recorded ?: return
        when (val v = MessageRules.validateVoice(rec.durationMs, rec.sizeBytes)) {
            is MessageRules.Validation.Invalid -> { effects.trySend(v.reason); return }
            MessageRules.Validation.Ok -> Unit
        }
        viewModelScope.launch {
            player.stop()
            local.update { it.copy(sendingMedia = 0f) }
            val up = mediaRepository.upload(MediaCategory.VOICE, rec.file, "audio/mp4", durationMs = rec.durationMs) { p -> local.update { it.copy(sendingMedia = p) } }
            when (up) {
                is AppResult.Success -> {
                    messageRepository.sendMedia(MessageType.VOICE, up.value.id, mediaRepository.localFile(up.value.id)?.absolutePath)
                    rec.file.delete(); recorder.reset()
                    local.update { it.copy(showRecorder = false, sendingMedia = null) }
                }
                is AppResult.Failure -> { effects.trySend(up.error.message ?: "Upload failed"); local.update { it.copy(sendingMedia = null) } }
            }
        }
    }

    fun togglePlay(message: Message) {
        viewModelScope.launch {
            val file: File? = message.localMediaPath?.let { File(it).takeIf(File::exists) }
                ?: message.mediaId?.let { mediaRepository.ensureCached(it, "audio/mp4").getOrNull() }
            if (file == null) { effects.trySend("Couldn't load that voice note"); return@launch }
            player.toggle(message.id, file)
        }
    }
    fun tickPlayback() = player.refresh()

    // ---- misc ----
    suspend fun resolveImage(message: Message): Any? {
        message.localMediaPath?.let { p -> File(p).takeIf(File::exists)?.let { return it } }
        val id = message.mediaId ?: return null
        return mediaRepository.ensureCached(id, "image/jpeg").getOrNull() ?: mediaRepository.resolveUrl(id).getOrNull()
    }

    /** View-once: returns a one-time URL (or an error message) for the full-screen viewer. */
    suspend fun openViewOnce(message: Message): Result<String> = when (val r = messageRepository.openViewOnce(message.id)) {
        is AppResult.Success -> Result.success(r.value)
        is AppResult.Failure -> Result.failure(IllegalStateException(
            if ((r.error as? com.risaina.app.core.common.AppError.Conflict)?.code == "view_once_consumed") "Already opened" else r.error.message ?: "Couldn't open",
        ))
    }

    fun markRead(message: Message) {
        if (message.senderId == state.value.myId || message.readAt != null) return
        viewModelScope.launch { messageRepository.markRead(message.id) }
    }

    fun retry(message: Message) { viewModelScope.launch { messageRepository.retry(message.localKey) } }
    fun discard(message: Message) { viewModelScope.launch { messageRepository.discard(message.localKey) } }
    fun delete(message: Message) { viewModelScope.launch { messageRepository.delete(message.id).onFailure { effects.trySend(it.message ?: "Couldn't delete") } } }

    fun loadOlder() {
        if (local.value.loadingMore || !local.value.canLoadMore) return
        viewModelScope.launch {
            local.update { it.copy(loadingMore = true) }
            val more = messageRepository.loadOlder().getOrNull() ?: true
            local.update { it.copy(loadingMore = false, canLoadMore = more) }
        }
    }

    override fun onCleared() { player.stop(); recorder.cancel() }

    private fun isOnlyEmoji(s: String): Boolean {
        if (s.length > 16) return false
        return s.codePoints().allMatch { cp ->
            val t = Character.getType(cp)
            t == Character.SURROGATE.toInt() || t == Character.OTHER_SYMBOL.toInt() || t == Character.NON_SPACING_MARK.toInt() ||
                t == Character.FORMAT.toInt() || cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || Character.isWhitespace(cp)
        } && s.isNotBlank()
    }
}
