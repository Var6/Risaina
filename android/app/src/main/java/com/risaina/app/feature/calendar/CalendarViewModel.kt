package com.risaina.app.feature.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.data.CalendarRepository
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.MilestoneRepository
import com.risaina.app.core.datastore.ReminderSettingsStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.domain.CalendarOccasions
import com.risaina.app.core.domain.Occasion
import com.risaina.app.core.domain.OccasionKind
import com.risaina.app.core.domain.OccasionReminders
import com.risaina.app.core.domain.ReminderSettings
import com.risaina.app.core.domain.TimeZones
import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.Partner
import com.risaina.app.core.reminders.ReminderScheduler
import com.risaina.app.core.safety.ReadinessFix
import com.risaina.app.core.safety.SosReadinessProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

data class CalendarUiState(
    val today: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val myId: String? = null,
    val partner: Partner? = null,
    /** The partner's shared zone: rows show their clock when it differs. */
    val partnerZone: ZoneId? = null,
    val entries: List<CalendarEntry> = emptyList(),
    val occasions: List<Occasion> = emptyList(),
    val sections: List<AgendaSection> = emptyList(),
    val firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
    /** False on a server that predates the calendar. */
    val available: Boolean? = null,
    val refreshing: Boolean = false,
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
    coupleRepository: CoupleRepository,
    private val milestoneRepository: MilestoneRepository,
    sessionStore: SessionStore,
    private val savedState: SavedStateHandle,
    private val time: TimeProvider,
) : ViewModel() {
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()

    /** Upcoming (0) or Month (1), kept across rotation and process death. */
    val tab: StateFlow<Int> = savedState.getStateFlow(KEY_TAB, 0)
    fun setTab(i: Int) { savedState[KEY_TAB] = i }

    private val refreshing = MutableStateFlow(false)
    /** "Today" moves at midnight while the screen is open. */
    private val today: Flow<LocalDate> = flow { while (true) { emit(LocalDate.now(time.zone())); delay(60_000) } }.distinctUntilChanged()

    private data class Sources(val entries: List<CalendarEntry>, val partner: Partner?, val myId: String?, val occasions: List<Occasion>)

    private val sources: Flow<Sources> = combine(
        calendarRepository.all, coupleRepository.couple, coupleRepository.partner, sessionStore.profile, milestoneRepository.all,
    ) { entries, couple, partner, me, milestones ->
        Sources(entries, partner, me?.userId, CalendarOccasions.derive(couple, me?.userId, me?.birthday, partner, milestones))
    }

    val state: StateFlow<CalendarUiState> = combine(sources, today, calendarRepository.available, refreshing) { src, day, available, busy ->
        val zone = time.zone()
        val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        CalendarUiState(
            today = day, zone = zone, myId = src.myId, partner = src.partner,
            partnerZone = src.partner?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() },
            entries = src.entries, occasions = src.occasions,
            sections = CalendarAgenda.upcoming(src.entries, src.occasions, day, zone, firstDay),
            firstDayOfWeek = firstDay, available = available, refreshing = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    init { refresh() }

    /** Calendar events can reach a phone late (calendar.md §10): opening the calendar asks the server. */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            calendarRepository.refresh()
            milestoneRepository.refresh()
            refreshing.value = false
        }
    }

    fun setDone(entry: CalendarEntry, done: Boolean) {
        viewModelScope.launch {
            calendarRepository.setDone(entry.id, done).onFailure { effects.trySend(it.message ?: "Couldn't save that") }
        }
    }

    fun say(message: String) { effects.trySend(message) }

    private companion object {
        const val KEY_TAB = "tab"
    }
}

/** The reminder settings sheet, opened from the calendar and from Settings. */
@HiltViewModel
class ReminderSettingsViewModel @Inject constructor(
    private val store: ReminderSettingsStore,
    private val scheduler: ReminderScheduler,
    private val probe: SosReadinessProbe,
    coupleRepository: CoupleRepository,
    time: TimeProvider,
) : ViewModel() {
    val settings: StateFlow<ReminderSettings> = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderSettings())
    val partner: StateFlow<Partner?> = coupleRepository.partner.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** "Also when it turns midnight for Bea": only when their clock tells another time at some point in the year. */
    val offersPartnerMidnight: StateFlow<Boolean> = coupleRepository.partner
        .map { p -> !TimeZones.sameClockAllYear(time.zone(), TimeZones.zoneOf(p?.timeZone), time.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _exact = MutableStateFlow(scheduler.exactAlarmsAllowed())
    /** Whether reminders arrive on time; read again each time the sheet is shown (the person may have just allowed it). */
    val exactAllowed: StateFlow<Boolean> = _exact
    fun recheck() { _exact.value = scheduler.exactAlarmsAllowed() }
    fun allowExact() = probe.open(ReadinessFix.EXACT_ALARMS)

    fun setEnabled(on: Boolean) = edit { it.copy(enabled = on) }
    fun setAllDayTime(t: LocalTime) = edit { it.copy(allDayTime = t) }
    fun setPartnerMidnight(on: Boolean) = edit { it.copy(partnerMidnight = on) }
    fun setOccasion(kind: OccasionKind, value: OccasionReminders) = edit {
        when (kind) {
            OccasionKind.ANNIVERSARY -> it.copy(anniversary = value)
            OccasionKind.PARTNER_BIRTHDAY -> it.copy(partnerBirthday = value)
            OccasionKind.MY_BIRTHDAY -> it.copy(myBirthday = value)
            OccasionKind.NEXT_VISIT -> it.copy(nextVisit = value)
            OccasionKind.MILESTONE -> it.copy(milestones = value)
        }
    }

    // The scheduler follows the store and works the reminders out again by itself.
    private fun edit(transform: (ReminderSettings) -> ReminderSettings) {
        viewModelScope.launch { store.update(transform = transform) }
    }
}
