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
import com.risaina.app.core.domain.CalendarOccasions
import com.risaina.app.core.domain.Countdown
import com.risaina.app.core.domain.CountdownCalculator
import com.risaina.app.core.domain.OccasionKind
import com.risaina.app.core.model.Couple
import com.risaina.app.core.model.Memory
import com.risaina.app.core.model.Message
import com.risaina.app.core.model.MessageType
import com.risaina.app.core.model.Partner
import com.risaina.app.core.model.Ping
import com.risaina.app.core.model.PingStatus
import com.risaina.app.core.model.PingType
import com.risaina.app.core.model.unknownTypeText
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    val emailVerified: Boolean = true,
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
    /** My most recent ping in the last 10 minutes; its server status stays visible under the button. */
    val lastSent: Ping? = null,
    val cooldownSeconds: Long = 0,
    val burstKey: Int = 0,
    val selectedType: PingType = PingType.THINKING_OF_YOU,
    val incoming: Ping? = null,
    val showComposer: Boolean = false,
    val composerTab: Int = 0,
    val justPaired: Boolean = false,
    val pushConfigured: Boolean = true,
    val unread: Int = 0,
    val socket: SocketState = SocketState.DISCONNECTED,
    val anniversary: com.risaina.app.core.domain.AnniversaryInfo? = null,
    val partnerBirthday: com.risaina.app.core.domain.UpcomingBirthday? = null,
    val myBirthday: com.risaina.app.core.domain.UpcomingBirthday? = null,
    /** Partner's local time and an optional "tomorrow for them" note, when they share it and it is not this phone's time. */
    val partnerClock: Pair<String, String?>? = null,
    val pingsToday: Int = 0,
    val background: String = "GLOW",
    val partnerAvatar: File? = null,
    val askStartDate: Boolean = false,
    val partnerWeather: com.risaina.app.core.data.Weather? = null,
    val showPartnerTime: Boolean = false,
    /** "Coming up": due to-dos and the next week's entries (HomeCalendar). Hidden when empty. */
    val comingUp: List<com.risaina.app.feature.calendar.AgendaItem.Entry> = emptyList(),
    val pinnedChips: List<PinnedChip> = emptyList(),
    /** False on a server that predates the calendar: Home keeps the Timeline tile then. */
    val calendarAvailable: Boolean = true,
    val today: LocalDate = LocalDate.now(),
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
    val composerTab: Int = 0,
    val justPaired: Boolean = false,
    val favouriteImage: File? = null,
    val partnerAvatar: File? = null,
    val startDateDismissed: Boolean = false,
    val weather: com.risaina.app.core.data.Weather? = null,
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
    private val preferencesStore: com.risaina.app.core.datastore.PreferencesStore,
    private val weatherRepository: com.risaina.app.core.data.WeatherRepository,
    private val calendarRepository: com.risaina.app.core.data.CalendarRepository,
) : ViewModel() {

    private val local = MutableStateFlow(Local(justPaired = uiFlags.justPaired.also { uiFlags.justPaired = false }))
    /** Fast ticks only while a cooldown counts down; otherwise every 30 s (enough for the partner's clock). */
    private val fastTicks = MutableStateFlow(true)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ticker: Flow<Instant> = fastTicks.flatMapLatest { fast ->
        flow { while (true) { emit(time.now()); delay(if (fast) 1_000 else 30_000) } }
    }
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()
    private val pushConfigured = pushAvailability.configured

    private data class Content(
        val partner: Partner?, val couple: Couple?, val myName: String, val myId: String?, val emailVerified: Boolean = true, val pings: List<Ping>, val messages: List<Message>,
        val favourite: Memory?, val unread: Int, val myBirthday: String? = null, val background: String = "GLOW", val showPartnerTime: Boolean = false,
        val calendar: List<com.risaina.app.core.model.CalendarEntry> = emptyList(), val calendarAvailable: Boolean = true,
    )

    private val content: Flow<Content> = combine(
        coupleRepository.partner, coupleRepository.couple, coupleRepository.profile, pingRepository.recent, messageRepository.recent(30),
    ) { partner, couple, me, pings, messages -> Content(partner, couple, me?.displayName ?: "", me?.userId, me?.emailVerified ?: true, pings, messages, null, 0, me?.birthday) }
        .combine(memoryRepository.favourite) { c, fav -> c.copy(favourite = fav) }
        .combine(messageRepository.unreadCount) { c, n -> c.copy(unread = n) }
        .combine(preferencesStore.preferences) { c, p -> c.copy(background = p.background, showPartnerTime = p.showPartnerTime) }
        .combine(calendarRepository.all) { c, e -> c.copy(calendar = e) }
        .combine(calendarRepository.available) { c, a -> c.copy(calendarAvailable = a != false) }

    val state: StateFlow<HomeUiState> = combine(content, local, pingRepository.cooldownEndsAt, ticker, syncManager.socketState) { c, l, cooldownEnds, now, socket ->
        val cooldown = cooldownEnds?.let { java.time.Duration.between(now, it).seconds.coerceAtLeast(0) } ?: 0L
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
        fastTicks.value = cooldown > 0 || l.sending || l.settledUntil?.isAfter(now) == true
        // The same occasions the calendar and its reminders use. Milestones are left out: Home only
        // shows the birthdays (and the anniversary, with its "together" count, from the start date).
        val occasions = CalendarOccasions.derive(c.couple, c.myId, c.myBirthday, c.partner, emptyList())
        HomeUiState(
            myName = c.myName, emailVerified = c.emailVerified, myId = c.myId, partner = c.partner, couple = c.couple,
            countdown = countdownFor(c.couple, today),
            together = c.couple?.relationshipStartDate?.let { CountdownCalculator.formatTogether(CountdownCalculator.together(it, today)) },
            latestMoment = moment,
            favouriteMemory = c.favourite, favouriteImage = l.favouriteImage,
            latestVoice = c.messages.firstOrNull { it.type == MessageType.VOICE && it.deletedAt == null },
            recent = recentActivity(c.pings, c.messages, c.myId, c.partner?.displayName),
            button = button, burstKey = l.burstKey, selectedType = l.selectedType,
            lastSent = c.pings.firstOrNull { it.senderId == c.myId }?.takeIf { java.time.Duration.between(it.createdAt, now).toMinutes() < 10 },
            cooldownSeconds = cooldown,
            incoming = l.incoming, showComposer = l.showComposer, composerTab = l.composerTab, justPaired = l.justPaired,
            pushConfigured = pushConfigured, unread = c.unread, socket = socket,
            anniversary = c.couple?.relationshipStartDate?.let { com.risaina.app.core.domain.Occasions.anniversary(it, today) },
            partnerBirthday = CalendarOccasions.upcomingBirthday(occasions, OccasionKind.PARTNER_BIRTHDAY, today),
            myBirthday = CalendarOccasions.upcomingBirthday(occasions, OccasionKind.MY_BIRTHDAY, today),
            partnerClock = com.risaina.app.core.domain.Occasions.partnerClock(c.partner?.timeZone, now),
            pingsToday = c.pings.count { it.createdAt.atZone(ZoneId.systemDefault()).toLocalDate() == today && it.status != com.risaina.app.core.model.PingStatus.FAILED },
            background = c.background,
            partnerAvatar = l.partnerAvatar,
            partnerWeather = l.weather,
            showPartnerTime = c.showPartnerTime,
            askStartDate = c.couple != null && c.couple.relationshipStartDate == null && !l.startDateDismissed,
            comingUp = if (c.calendarAvailable) HomeCalendar.comingUp(c.calendar, today, ZoneId.systemDefault()) else emptyList(),
            pinnedChips = if (c.calendarAvailable) HomeCalendar.pinned(c.calendar, today, ZoneId.systemDefault()) else emptyList(),
            calendarAvailable = c.calendarAvailable,
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch { pingRepository.refresh(); messageRepository.refresh(); memoryRepository.refresh() }
        // Also learns whether this server has the calendar at all (the tile and the card hide if not).
        viewModelScope.launch { calendarRepository.refreshIfStale() }
        viewModelScope.launch {
            syncManager.incoming.collect { item ->
                if (item is Incoming.NewPing) local.update { it.copy(incoming = item.ping) }
            }
        }
        viewModelScope.launch {
            // Weather for the partner's shared city; refreshed when it changes and every 30 min.
            coupleRepository.partner.map { it?.city }.distinctUntilChanged().collectLatest { city ->
                while (true) {
                    val w = city?.let { c -> weatherRepository.current(c) }
                    local.update { it.copy(weather = w) }
                    // Retry soon after a failure (e.g. a network blip); otherwise every 30 minutes.
                    delay(if (city != null && w == null) 2 * 60 * 1000L else 30 * 60 * 1000L)
                }
            }
        }
        viewModelScope.launch {
            coupleRepository.partner.collect { p ->
                val file = p?.avatarMediaId?.let { mediaRepository.localFile(it) ?: mediaRepository.ensureCached(it, "image/jpeg").getOrNull() }
                local.update { it.copy(partnerAvatar = file) }
            }
        }
        viewModelScope.launch {
            memoryRepository.favourite.collect { fav ->
                val file = fav?.let { mediaRepository.localFile(it.mediaId) ?: mediaRepository.ensureCached(it.mediaId, "image/jpeg").getOrNull() }
                local.update { it.copy(favouriteImage = file) }
            }
        }
    }

    /** Asked once on Home after pairing; either partner can answer and it shows for both. */
    fun setStartDate(date: LocalDate?) {
        if (date == null) return
        viewModelScope.launch {
            val c = state.value.couple
            coupleRepository.updateCouple(date, c?.nextMeetingDate, c?.nextMeetingLabel).onFailure { effects.trySend(it.message ?: "Couldn't save") }
        }
    }
    fun dismissStartDate() = local.update { it.copy(startDateDismissed = true) }

    /** A to-do ticked on the "Coming up" card. */
    fun setDone(entry: com.risaina.app.core.model.CalendarEntry, done: Boolean) {
        viewModelScope.launch { calendarRepository.setDone(entry.id, done).onFailure { effects.trySend(it.message ?: "Couldn't save that") } }
    }

    private fun countdownFor(couple: Couple?, today: LocalDate): CountdownInfo? {
        couple ?: return null
        couple.nextMeetingDate?.takeIf { !it.isBefore(today) }?.let {
            return CountdownInfo(couple.nextMeetingLabel ?: "Until we meet", CountdownCalculator.until(it, today), it)
        }
        return null
    }

    private fun recentActivity(pings: List<Ping>, messages: List<Message>, myId: String?, partnerName: String?): List<ActivityItem> {
        val items = pings.map { p ->
            val gift = com.risaina.app.feature.ping.Expressions
            val label = if (gift.isGift(p.message)) "Gift" else p.type.label.takeIf { p.type != com.risaina.app.core.model.PingType.CUSTOM } ?: "Ping"
            val detail = if (gift.isGift(p.message)) gift.giftLine(p.message) else p.message
            ActivityItem(p.id, p.emoji, if (p.senderId == myId) "You · $label" else "${partnerName ?: "Partner"} · $label", detail, p.createdAt, p.senderId == myId, if (p.senderId == myId) p.status else null)
        } + messages.filter { it.deletedAt == null }.map { m ->
            val what = when (m.type) { MessageType.PHOTO -> "Photo"; MessageType.VOICE -> "Voice note"; MessageType.UNKNOWN -> m.unknownTypeText(); else -> m.text ?: "" }
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

    fun openComposer() = local.update { it.copy(showComposer = true, composerTab = 0) }
    fun openGifts() = local.update { it.copy(showComposer = true, composerTab = 2) }
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

    suspend fun shouldAskNotificationPermission(context: android.content.Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return false
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted || preferencesStore.notificationPromptShown.first()) return false
        preferencesStore.setNotificationPromptShown()
        return true
    }
}
