package com.risaina.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.MediaRepository
import com.risaina.app.core.data.MemoryRepository
import com.risaina.app.core.data.MessageRepository
import com.risaina.app.core.data.PingRepository
import com.risaina.app.core.domain.Countdown
import com.risaina.app.core.domain.CountdownCalculator
import com.risaina.app.core.model.Couple
import com.risaina.app.core.model.Memory
import com.risaina.app.core.model.Message
import com.risaina.app.core.model.MessageType
import com.risaina.app.core.model.Partner
import com.risaina.app.core.model.Ping
import com.risaina.app.core.model.PingStatus
import com.risaina.app.core.model.PingType
import com.risaina.app.core.network.SocketState
import com.risaina.app.core.notifications.Haptics
import com.risaina.app.core.notifications.PushAvailability
import com.risaina.app.core.sync.EventSyncManager
import com.risaina.app.core.sync.Incoming
import com.risaina.app.feature.common.UiFlags
import com.risaina.app.feature.ping.PingButtonState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

sealed interface Moment {
    data class FromPing(val ping: Ping) : Moment
    data class FromMessage(val message: Message) : Moment
    val at: Instant get() = when (this) { is FromPing -> ping.createdAt; is FromMessage -> message.createdAt }
}

data class ActivityItem(val id: String, val emoji: String, val title: String, val subtitle: String?, val at: Instant, val mine: Boolean, val status: PingStatus?)

data class CountdownInfo(val label: String, val countdown: Countdown, val date: LocalDate)

data class HomeUiState(
    val myName: String = "",
    val myId: String? = null,
    val partner: Partner? = null,
    val couple: Couple? = null,
    val countdown: CountdownInfo? = null,
    val together: String? = null,
    val latestMoment: Moment? = null,
    val favouriteMemory: Memory? = null,
    val favouriteImage: File? = null,
    val latestVoice: Message? = null,
    val recent: List<ActivityItem> = emptyList(),
    val button: PingButtonState = PingButtonState.Idle,
    val burstKey: Int = 0,
    val selectedType: PingType = PingType.THINKING_OF_YOU,
    val incoming: Ping? = null,
    val showComposer: Boolean = false,
    val justPaired: Boolean = false,
    val pushConfigured: Boolean = true,
    val unread: Int = 0,
    val socket: SocketState = SocketState.DISCONNECTED,
)

private data class Local(
    val sending: Boolean = false,
    val lastSentKey: String? = null,
    val settledUntil: Instant? = null,
    val failed: String? = null,
    val burstKey: Int = 0,
    val selectedType: PingType = PingType.THINKING_OF_YOU,
    val incoming: Ping? = null,
    val showComposer: Boolean = false,
    val justPaired: Boolean = false,
    val favouriteImage: File? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val pingRepository: PingRepository,
    private val messageRepository: MessageRepository,
    private val memoryRepository: MemoryRepository,
    private val mediaRepository: MediaRepository,
    private val coupleRepository: CoupleRepository,
    private val syncManager: EventSyncManager,
    private val haptics: Haptics,
    private val time: TimeProvider,
    pushAvailability: PushAvailability,
    uiFlags: UiFlags,
) : ViewModel() {

    private val local = MutableStateFlow(Local(justPaired = uiFlags.justPaired.also { uiFlags.justPaired = false }))
    private val ticker: Flow<Instant> = flow { while (true) { emit(time.now()); delay(1_000) } }
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()
    private val pushConfigured = pushAvailability.configured

    private data class Content(val partner: Partner?, val couple: Couple?, val myName: String, val myId: String?, val pings: List<Ping>, val messages: List<Message>, val favourite: Memory?, val unread: Int)

    private val content: Flow<Content> = combine(
        coupleRepository.partner, coupleRepository.couple, coupleRepository.profile, pingRepository.recent, messageRepository.recent(30),
    ) { partner, couple, me, pings, messages -> Content(partner, couple, me?.displayName ?: "", me?.userId, pings, messages, null, 0) }
        .combine(memoryRepository.favourite) { c, fav -> c.copy(favourite = fav) }
        .combine(messageRepository.unreadCount) { c, n -> c.copy(unread = n) }

    val state: StateFlow<HomeUiState> = combine(content, local, pingRepository.cooldownRemaining, ticker, syncManager.socketState) { c, l, cooldown, now, socket ->
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val lastSent = l.lastSentKey?.let { key -> c.pings.firstOrNull { it.localKey == key } }
        val button: PingButtonState = when {
            l.sending -> PingButtonState.Sending
            l.failed != null -> PingButtonState.Failed(l.failed)
            lastSent != null && l.settledUntil?.isAfter(now) == true -> PingButtonState.Settled(lastSent.status)
            cooldown > 0 -> PingButtonState.Cooldown(cooldown)
            else -> PingButtonState.Idle
        }
        val moment = listOfNotNull(
            c.pings.firstOrNull { it.senderId != c.myId }?.let { Moment.FromPing(it) },
            c.messages.firstOrNull { it.senderId != c.myId && it.deletedAt == null }?.let { Moment.FromMessage(it) },
        ).maxByOrNull { it.at }
        HomeUiState(
            myName = c.myName, myId = c.myId, partner = c.partner, couple = c.couple,
            countdown = countdownFor(c.couple, today),
            together = c.couple?.relationshipStartDate?.let { CountdownCalculator.formatTogether(CountdownCalculator.together(it, today)) },
            latestMoment = moment,
            favouriteMemory = c.favourite, favouriteImage = l.favouriteImage,
            latestVoice = c.messages.firstOrNull { it.type == MessageType.VOICE && it.deletedAt == null },
            recent = recentActivity(c.pings, c.messages, c.myId, c.partner?.displayName),
            button = button, burstKey = l.burstKey, selectedType = l.selectedType,
            incoming = l.incoming, showComposer = l.showComposer, justPaired = l.justPaired,
            pushConfigured = pushConfigured, unread = c.unread, socket = socket,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch { pingRepository.refresh(); messageRepository.refresh(); memoryRepository.refresh() }
        viewModelScope.launch {
            syncManager.incoming.collect { item ->
                if (item is Incoming.NewPing) { haptics.arrival(); local.update { it.copy(incoming = item.ping) } }
            }
        }
        viewModelScope.launch {
            memoryRepository.favourite.collect { fav ->
                val file = fav?.let { mediaRepository.localFile(it.mediaId) ?: mediaRepository.ensureCached(it.mediaId, "image/jpeg").getOrNull() }
                local.update { it.copy(favouriteImage = file) }
            }
        }
    }

    private fun countdownFor(couple: Couple?, today: LocalDate): CountdownInfo? {
        couple ?: return null
        couple.nextMeetingDate?.takeIf { !it.isBefore(today) }?.let {
            return CountdownInfo(couple.nextMeetingLabel ?: "Until we meet", CountdownCalculator.until(it, today), it)
        }
        couple.relationshipStartDate?.let {
            val next = CountdownCalculator.nextAnniversary(it, today)
            return CountdownInfo("Anniversary", CountdownCalculator.until(next, today), next)
        }
        return null
    }

    private fun recentActivity(pings: List<Ping>, messages: List<Message>, myId: String?, partnerName: String?): List<ActivityItem> {
        val items = pings.map { p ->
            ActivityItem(p.id, p.emoji, if (p.senderId == myId) "You · ${p.type.label}" else "${partnerName ?: "Partner"} · ${p.type.label}", p.message, p.createdAt, p.senderId == myId, if (p.senderId == myId) p.status else null)
        } + messages.filter { it.deletedAt == null }.map { m ->
            val what = when (m.type) { MessageType.PHOTO -> "Photo"; MessageType.VOICE -> "Voice note"; else -> m.text ?: "" }
            ActivityItem(m.id, when (m.type) { MessageType.PHOTO -> "🖼"; MessageType.VOICE -> "🎙"; MessageType.EMOJI -> m.text ?: "✉️"; else -> "✉️" }, if (m.senderId == myId) "You" else partnerName ?: "Partner", what, m.createdAt, m.senderId == myId, null)
        }
        return items.sortedByDescending { it.at }.take(8)
    }

    // ---- Touch Ping ----
    fun selectType(type: PingType) = local.update { it.copy(selectedType = type) }

    fun tapPing() {
        val type = local.value.selectedType
        send(type, type.defaultEmoji, null)
    }

    fun openComposer() = local.update { it.copy(showComposer = true) }
    fun closeComposer() = local.update { it.copy(showComposer = false) }

    fun sendCustom(type: PingType, emoji: String, message: String?) {
        local.update { it.copy(showComposer = false, selectedType = type) }
        send(type, emoji, message)
    }

    private fun send(type: PingType, emoji: String, message: String?) {
        if (local.value.sending) return
        viewModelScope.launch {
            haptics.tap()
            local.update { it.copy(sending = true, failed = null, burstKey = it.burstKey + 1) }
            when (val r = pingRepository.send(type, emoji, message)) {
                is AppResult.Success -> {
                    haptics.confirm()
                    local.update { it.copy(sending = false, lastSentKey = r.value.localKey, settledUntil = time.now().plusSeconds(8)) }
                }
                is AppResult.Failure -> {
                    val reason = when (val e = r.error) {
                        is AppError.RateLimited -> "Give it ${e.retryAfterSeconds ?: 15}s"
                        is AppError.Offline -> "You're offline"
                        is AppError.Timeout -> "Timed out"
                        else -> e.message ?: "Couldn't send"
                    }
                    if (r.error !is AppError.RateLimited) haptics.error()
                    local.update { it.copy(sending = false, failed = reason) }
                    effects.trySend(reason)
                    delay(2_500)
                    local.update { it.copy(failed = null) }
                }
            }
        }
    }

    fun retryLast() {
        val key = local.value.lastSentKey ?: return tapPing()
        viewModelScope.launch {
            local.update { it.copy(sending = true, failed = null, burstKey = it.burstKey + 1) }
            val r = pingRepository.retry(key)
            local.update { it.copy(sending = false, failed = (r as? AppResult.Failure)?.error?.message, settledUntil = if (r.isSuccess) time.now().plusSeconds(8) else null) }
        }
    }

    fun dismissIncoming() = local.update { it.copy(incoming = null) }

    fun replyToIncoming(ping: Ping) {
        viewModelScope.launch { pingRepository.markSeen(ping.id) }
        local.update { it.copy(incoming = null) }
        send(ping.type, ping.type.defaultEmoji, null)
    }

    fun consumeJustPaired() = local.update { it.copy(justPaired = false) }
}
