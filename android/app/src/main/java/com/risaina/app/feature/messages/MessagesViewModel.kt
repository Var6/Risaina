package com.risaina.app.feature.messages

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    val gameUi: GameUiState = GameUiState(),
)

/** Games stream: the picker and in-flight game requests (cards ignore taps while their request runs). */
data class GameUiState(
    val showPicker: Boolean = false,
    val catalog: com.risaina.app.core.network.GameCatalogDto? = null,
    /** The kind being started, for the spinner ("Getting questions…" for trivia). */
    val startingKind: String? = null,
    val busyIds: Set<String> = emptySet(),
    /** This or That picks made but not sent yet, per game. */
    val drafts: Map<String, List<Int>> = emptyMap(),
)

private data class Local(
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
    private val notifier: com.risaina.app.core.notifications.RisainaNotifier,
    private val calendarRepository: com.risaina.app.core.data.CalendarRepository,
    private val listsRepository: com.risaina.app.core.data.ListsRepository,
    private val time: com.risaina.app.core.common.TimeProvider,
) : ViewModel() {
    private val wallpaperBusy = MutableStateFlow(false)
    private val gameRepository = gameRepo
    private val gameLocal = MutableStateFlow(GameUiState())

    private val local = MutableStateFlow(Local())
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()

    val state: StateFlow<MessagesUiState> = combine(
        messageRepository.recent(300), sessionStore.session, coupleRepository.partner, local,
        combine(recorder.state, player.state, syncManager.socketState) { r, p, s -> Triple(r, p, s) },
        coupleRepository.couple, combine(gameRepo.games, wallpaperBusy, gameLocal, gameRepo.drafts) { g, b, ui, d -> Triple(g, b, ui.copy(drafts = d)) },
    ) { arr ->
        @Suppress("UNCHECKED_CAST") val messages = arr[0] as List<Message>
        val session = arr[1] as com.risaina.app.core.model.Session?
        val partner = arr[2] as Partner?
        val l = arr[3] as Local
        @Suppress("UNCHECKED_CAST") val (rec, play, socket) = arr[4] as Triple<VoiceRecorder.State, PlaybackState, SocketState>
        val couple = arr[5] as com.risaina.app.core.model.Couple?
        @Suppress("UNCHECKED_CAST") val (games, wpBusy, gameUi) = arr[6] as Triple<List<com.risaina.app.core.network.GameDto>, Boolean, GameUiState>
        MessagesUiState(
            messages = messages, myId = session?.userId, partner = partner,
            sendingMedia = l.sendingMedia, canLoadMore = l.canLoadMore, loadingMore = l.loadingMore,
            showRecorder = l.showRecorder, recorder = rec, playback = play, socket = socket,
            wallpaper = couple?.chatWallpaperStyle ?: "DEFAULT", wallpaperMediaId = couple?.chatWallpaperMediaId,
            games = games, wallpaperBusy = wpBusy, gameUi = gameUi,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    init { viewModelScope.launch { messageRepository.refresh() }; viewModelScope.launch { gameRepository.refresh() } }

    // ---- calendar and to-dos (message-actions.md §5a) ----

    /** False on a server that predates the calendar: its actions and chips hide. */
    val calendarAvailable: StateFlow<Boolean> = calendarRepository.available
        .map { it != false }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * The entries made from messages on screen, by message id (the soonest one each), for the "In
     * your calendar" chip under a bubble. Text messages only, at most 300: under SQLite's 999.
     */
    val calendarLinks: StateFlow<Map<String, com.risaina.app.core.model.CalendarEntry>> = messageRepository.recent(300)
        .map { l -> l.filter { it.type == MessageType.TEXT && it.sendState == com.risaina.app.core.model.MessageSendState.SENT }.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(emptyList()) else calendarRepository.forMessages(ids) }
        .map { entries -> entries.groupBy { it.source?.messageId }.mapNotNull { (id, e) -> id?.let { it to e.minBy { x -> x.date } } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val order get() = com.risaina.app.core.domain.DateOrder.forLocale(java.util.Locale.getDefault())
    private fun partnerFallbackTitle() = "Plan with ${partnerFirst().replaceFirstChar { it.uppercase() }}"

    /** A message's words are read in its author's day: "tomorrow" in yesterday's message means today. */
    private fun referenceFor(m: Message): java.time.ZonedDateTime {
        val zone = if (m.senderId != state.value.myId) state.value.partner?.timeZone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } else null
        return m.createdAt.atZone(zone ?: time.zone())
    }

    private fun now() = time.now().atZone(time.zone())

    /** "Add to calendar" on a message: its title and first date, linked back to it. */
    fun prefillFor(m: Message): com.risaina.app.feature.calendar.CalendarPrefill =
        com.risaina.app.feature.calendar.CalendarPrefills.fromText(m.text.orEmpty(), referenceFor(m), order, partnerFallbackTitle(), messageId = m.id)

    /** "Add to calendar" from the composer: the draft's title and date, or an empty editor. */
    fun prefillForDraft(kind: com.risaina.app.core.model.CalendarKind = com.risaina.app.core.model.CalendarKind.EVENT): com.risaina.app.feature.calendar.CalendarPrefill =
        if (composer.isBlank()) com.risaina.app.feature.calendar.CalendarPrefill(kind = kind)
        else com.risaina.app.feature.calendar.CalendarPrefills.fromText(composer, now(), order, partnerFallbackTitle(), kind = kind)

    /** The composer's chip: only a date the parser is sure of. */
    fun draftHit(text: String): com.risaina.app.core.domain.DateHit? =
        if (text.isBlank() || !calendarAvailable.value) null else com.risaina.app.feature.calendar.CalendarPrefills.confidentHit(text, now(), order)

    private val todos = TodoQueue(listsRepository::ensureTodoList, listsRepository::appendItems)

    /**
     * "Make it a to-do" (D5): with a date the parser is sure of, a dated to-do in the calendar (the
     * editor opens, via [onDated]); without one, a line in the shared "To-dos" checklist.
     */
    fun makeTodo(m: Message, onDated: (com.risaina.app.feature.calendar.CalendarPrefill) -> Unit) {
        val text = m.text?.trim().orEmpty()
        if (text.isEmpty()) return
        val hit = com.risaina.app.feature.calendar.CalendarPrefills.confidentHit(text, referenceFor(m), order)
        if (hit != null && calendarAvailable.value) {
            onDated(prefillFor(m).copy(kind = com.risaina.app.core.model.CalendarKind.TODO))
            return
        }
        appendTodo(text, clearDraft = false)
    }

    /**
     * The composer's "Add a to-do": the draft as a to-do (dated or not, as above), or with nothing
     * typed the To-dos list itself, through [onOpenList].
     */
    fun addTodoFromComposer(onDated: (com.risaina.app.feature.calendar.CalendarPrefill) -> Unit, onOpenList: (String) -> Unit) {
        val text = composer.trim()
        if (text.isEmpty()) {
            viewModelScope.launch {
                todos.list().onSuccess { onOpenList(it.id) }.onFailure { effects.trySend(it.message ?: "Couldn't open your To-dos") }
            }
            return
        }
        val hit = com.risaina.app.feature.calendar.CalendarPrefills.confidentHit(text, now(), order)
        if (hit != null && calendarAvailable.value) {
            onDated(prefillForDraft(com.risaina.app.core.model.CalendarKind.TODO))
            return
        }
        appendTodo(text, clearDraft = true)
    }

    // One at a time (TodoQueue): a second to-do waits for the first, so there is only ever one "To-dos" list.
    private fun appendTodo(text: String, clearDraft: Boolean) {
        viewModelScope.launch {
            val r = todos.add(text) ?: run { effects.trySend("Still adding that one…"); return@launch }
            r.onSuccess { list -> if (clearDraft && composer.trim() == text) composer = ""; effects.trySend("Added to ${list.title}") }
                .onFailure { effects.trySend(it.message ?: "Couldn't add the to-do") }
        }
    }

    fun say(message: String) { effects.trySend(message) }

    /**
     * What the person is typing. Kept in Compose snapshot state and read by the text field directly.
     * It used to live in the combined [state] flow, which is rebuilt from about ten sources and lags a
     * frame or more behind each keystroke, so an older value could land after a newer one and put old
     * text back into the box while typing (and the sent message back after sending).
     */
    var composer by androidx.compose.runtime.mutableStateOf("")
        private set

    fun onComposer(v: String) { composer = v }

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

    // ---- games: stone–paper–scissors–thread, tic-tac-toe, trivia duel, This or That ----
    private fun partnerFirst() = state.value.partner?.displayName?.substringBefore(" ")?.ifBlank { null } ?: "your partner"

    private fun gameError(error: com.risaina.app.core.common.AppError, gameId: String?, fallback: String) {
        val kind = gameId?.let { id -> gameRepository.games.value.firstOrNull { it.id == id }?.kind }
        com.risaina.app.feature.messages.games.gameErrorMessage(error, kind, partnerFirst(), fallback)?.let { effects.trySend(it) }
    }

    /** One request at a time per game: the card ignores taps while [id] is busy. */
    private fun gameWrite(id: String, fallback: String, block: suspend () -> AppResult<Unit>) {
        viewModelScope.launch {
            gameLocal.update { it.copy(busyIds = it.busyIds + id) }
            try {
                block().onFailure { gameError(it, id, fallback) }
            } finally {
                gameLocal.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }

    fun openGamePicker() {
        gameLocal.update { it.copy(showPicker = true) }
        viewModelScope.launch { gameRepository.catalog().let { c -> gameLocal.update { it.copy(catalog = c) } } }
    }
    fun closeGamePicker() = gameLocal.update { it.copy(showPicker = false) }

    fun startGame(opts: com.risaina.app.core.data.GameStartOptions) { viewModelScope.launch { start(opts) } }

    private suspend fun start(opts: com.risaina.app.core.data.GameStartOptions) {
        gameLocal.update { it.copy(startingKind = opts.kind) }
        val r = gameRepository.start(opts)
        gameLocal.update { it.copy(startingKind = null, showPicker = it.showPicker && !r.isSuccess) }
        r.onFailure { gameError(it, null, "Couldn't start a game") }
    }

    /** One open game per couple: end the current one (for both), then start the new one. */
    fun endAndStart(openId: String, opts: com.risaina.app.core.data.GameStartOptions) {
        viewModelScope.launch {
            gameLocal.update { it.copy(startingKind = opts.kind) }
            val ended = gameRepository.cancel(openId)
            if (ended is AppResult.Failure) {
                gameLocal.update { it.copy(startingKind = null) }
                gameError(ended.error, openId, "Couldn't end that game")
                return@launch
            }
            start(opts)
        }
    }

    /** Same kind, same options. */
    fun playAgain(g: com.risaina.app.core.network.GameDto) = startGame(com.risaina.app.core.data.GameStartOptions.from(g))

    fun gameMove(id: String, move: String) = gameWrite(id, "Couldn't play") { gameRepository.move(id, move) }
    fun tttPlay(id: String, cell: Int) = gameWrite(id, "Couldn't play") {
        gameRepository.play(id, com.risaina.app.core.network.GamePlayRequest(com.risaina.app.core.data.GameKinds.TICTACTOE, cell = cell))
    }
    fun triviaAnswer(id: String, item: Int, choice: Int) = gameWrite(id, "Couldn't send your answer") {
        gameRepository.play(id, com.risaina.app.core.network.GamePlayRequest(com.risaina.app.core.data.GameKinds.TRIVIA, item = item, choice = choice))
    }

    /** This or That: picks build up locally; the last one sends them all. */
    fun thisOrThatPick(id: String, choice: Int) {
        val total = gameRepository.games.value.firstOrNull { it.id == id }?.quiz?.items?.size ?: return
        val draft = (gameRepository.drafts.value[id].orEmpty() + choice).take(total)
        gameRepository.setDraft(id, draft)
        if (draft.size == total) thisOrThatSubmit(id)
    }
    fun thisOrThatUndo(id: String) {
        val draft = gameRepository.drafts.value[id].orEmpty()
        if (draft.isNotEmpty()) gameRepository.setDraft(id, draft.dropLast(1))
    }
    fun thisOrThatSubmit(id: String) {
        val picks = gameRepository.drafts.value[id] ?: return
        gameWrite(id, "Couldn't send your picks") {
            val r = gameRepository.play(id, com.risaina.app.core.network.GamePlayRequest(com.risaina.app.core.data.GameKinds.THIS_OR_THAT, picks = picks))
            // Sent, or the server already has picks (a retry after a lost answer): the draft is done.
            if (r.isSuccess || (r.errorOrNull() as? com.risaina.app.core.common.AppError.Conflict)?.code == "already_moved") gameRepository.clearDraft(id)
            r
        }
    }

    fun gameEnd(id: String) = gameWrite(id, "Couldn't end it") { gameRepository.end(id) }
    fun gameRematch(id: String) = gameWrite(id, "Couldn't start a rematch") { gameRepository.rematch(id) }
    fun gameChallenge(id: String, truth: String, dare: String) = gameWrite(id, "Couldn't send") { gameRepository.challenge(id, truth, dare) }
    fun gameChoose(id: String, kind: String) = gameWrite(id, "Couldn't choose") { gameRepository.choose(id, kind) }
    fun gameConfirm(id: String) = gameWrite(id, "Couldn't confirm") { gameRepository.confirm(id) }
    fun gameReject(id: String, note: String?) = gameWrite(id, "Couldn't send") { gameRepository.reject(id, note) }
    fun gameComplete(id: String, answer: String?) = gameWrite(id, "Couldn't mark done") { gameRepository.complete(id, answer) }
    fun gameCancel(id: String) = gameWrite(id, "Couldn't cancel the game") { gameRepository.cancel(id) }
    suspend fun gameSuggestions() = gameRepository.suggestions()

    fun send() {
        val text = composer.trim()
        if (text.isEmpty()) return
        composer = ""
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

    /** The repository also takes the message's line out of the chat notification once the read is stored. */
    fun markRead(message: Message) {
        if (message.senderId == state.value.myId || message.readAt != null) return
        viewModelScope.launch { messageRepository.markRead(message.id) }
    }

    /**
     * The chat is on screen (opened, or back from the background): the chat notification is rebuilt
     * from what is still unread, or removed, so it never lingers with messages read on another phone
     * or deleted meanwhile. The messages that come into view are then marked read one by one.
     */
    fun onChatVisible() {
        viewModelScope.launch { notifier.refreshConversation() }
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
