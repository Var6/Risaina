package com.risaina.app.feature.calendar

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.data.CalendarRepository
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.ListsRepository
import com.risaina.app.core.data.toLocalDateOrNull
import com.risaina.app.core.data.toLocalTimeOrNull
import com.risaina.app.core.database.MessageDao
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.domain.DateDetector
import com.risaina.app.core.domain.DateHit
import com.risaina.app.core.domain.DateOrder
import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.CalendarEntryDraft
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.core.model.CalendarSource
import com.risaina.app.core.model.CalendarSourceKind
import com.risaina.app.core.model.Partner
import com.risaina.app.core.reminders.ReminderScheduler
import com.risaina.app.core.safety.ReadinessFix
import com.risaina.app.core.safety.SosReadinessProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/** Who the entry reminds; for a to-do, who does it. */
enum class Who { BOTH, ME, PARTNER }

/** Everything the editor's fields hold. */
data class EntryForm(
    val kind: CalendarKind = CalendarKind.EVENT,
    val title: String = "",
    val emoji: String = "",
    val date: LocalDate? = null,
    val endDate: LocalDate? = null,
    val allDay: Boolean = true,
    val time: LocalTime? = null,
    val durationMinutes: Int? = null,
    /** The anchor zone of a timed entry; null is "each our own clock". */
    val timeZone: String? = null,
    val repeat: CalendarRepeat = CalendarRepeat.NONE,
    val repeatUntil: LocalDate? = null,
    val reminders: Set<Int> = EntryRules.defaultReminders(allDay = true),
    val who: Who = Who.BOTH,
    val pinned: Boolean = false,
    val notes: String = "",
    /** "Let Bea know": on for a new entry, and for an edit that moves it (until the person decides). */
    val notifyPartner: Boolean = true,
)

data class EntryUiState(
    val loading: Boolean = true,
    /** Opened from a link to an entry that no longer exists. */
    val missing: Boolean = false,
    val existing: CalendarEntry? = null,
    val form: EntryForm = EntryForm(),
    val source: CalendarSource? = null,
    /** "From chat: “…”", "From “Trip”"; null hides the chip (a deleted list). */
    val sourceLabel: String? = null,
    /** A date found in a title that came without one: offered as chips. */
    val suggestion: DateHit? = null,
    val myId: String? = null,
    val partner: Partner? = null,
    val myZone: ZoneId = ZoneId.systemDefault(),
    val partnerZone: ZoneId? = null,
    val saving: Boolean = false,
    val exactAllowed: Boolean = true,
    /** Made by a newer app (a kind or repeat this one does not know): shown, not changed. */
    val readOnly: Boolean = false,
    val titleError: String? = null,
)

/**
 * The entry editor (the CalendarEntryEdit route): a new entry, prefilled from the chat or a note, or
 * an existing one. Saves need a connection; a retried create reuses its clientKey, so a reply lost on
 * the way never makes a second entry.
 */
@HiltViewModel
class CalendarEntryViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val calendar: CalendarRepository,
    private val coupleRepository: CoupleRepository,
    private val sessionStore: SessionStore,
    private val messageDao: MessageDao,
    private val listsRepository: ListsRepository,
    private val scheduler: ReminderScheduler,
    private val probe: SosReadinessProbe,
    private val time: TimeProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(EntryUiState(myZone = time.zone()))
    val state: StateFlow<EntryUiState> = _state.asStateFlow()
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()
    private val closes = Channel<String>(Channel.BUFFERED)
    /** The editor is done: go back and say this ("Added to your calendar"). */
    val closed: Flow<String> = closes.receiveAsFlow()
    private var notifyTouched = false

    // The keys are CalendarEntryEdit's property names: type-safe navigation keeps the route's
    // arguments (and a risaina://app/calendar/{id} link's) under them.
    private val clientKey: String = savedState.get<String>(KEY_CLIENT) ?: UUID.randomUUID().toString().also { savedState[KEY_CLIENT] = it }

    init { viewModelScope.launch { load() } }

    private suspend fun load() {
        val me = sessionStore.current()?.userId
        val partner = coupleRepository.partner.first()
        val partnerZone = partner?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val myZone = time.zone()
        _state.update { it.copy(myId = me, partner = partner, partnerZone = partnerZone, myZone = myZone, exactAllowed = scheduler.exactAlarmsAllowed()) }
        val id = savedState.get<String>("id")
        if (id != null) {
            val entry = calendar.byId(id) ?: run { calendar.refresh(); calendar.byId(id) }
            if (entry == null) {
                _state.update { it.copy(loading = false, missing = true) }
                return
            }
            show(entry)
            return
        }
        val title = savedState.get<String>("title")
        val time = savedState.get<String>("time").toLocalTimeOrNull()
        val date = savedState.get<String>("date").toLocalDateOrNull()
        val kind = savedState.get<String>("kind")?.let(CalendarKind::fromWire)?.takeIf { it != CalendarKind.UNKNOWN } ?: CalendarKind.EVENT
        val source = CalendarPrefills.source(
            savedState["sourceKind"], savedState["sourceMessageId"], savedState["sourceListId"], savedState["sourceItemId"],
        )
        // A time read from the partner's message is on their clock.
        val author = source?.messageId?.let { messageDao.byId(it) }?.senderId
        val authorZone = partnerZone.takeIf { author != null && author != me && author == partner?.id }
        val allDay = time == null
        val form = EntryForm(
            kind = kind, title = title.orEmpty().trim().take(120), date = date, allDay = allDay, time = time,
            timeZone = CalendarPrefills.zoneFor(time, date, myZone, authorZone), reminders = EntryRules.defaultReminders(allDay),
        )
        // Only a title came (the composer's "Add to calendar" with a draft, a note's line): offer its date.
        val suggestion = if (!title.isNullOrBlank() && date == null) {
            DateDetector.find(title, ZonedDateTime.now(myZone), DateOrder.forLocale(Locale.getDefault())).firstOrNull()
        } else null
        _state.update { it.copy(loading = false, form = form, source = source, sourceLabel = labelFor(source), suggestion = suggestion) }
    }

    private suspend fun show(entry: CalendarEntry) {
        val me = _state.value.myId
        val partnerId = _state.value.partner?.id
        val who = when (entry.remindUserIds.toSet()) {
            setOfNotNull(me) -> Who.ME
            setOfNotNull(partnerId) -> Who.PARTNER
            else -> Who.BOTH
        }
        val form = EntryForm(
            kind = entry.kind.takeIf { it != CalendarKind.UNKNOWN } ?: CalendarKind.EVENT, title = entry.title, emoji = entry.emoji.orEmpty(),
            date = entry.date, endDate = entry.endDate, allDay = entry.allDay, time = entry.time, durationMinutes = entry.durationMinutes,
            timeZone = entry.timeZone, repeat = entry.repeat, repeatUntil = entry.repeatUntil, reminders = entry.reminders.toSet(),
            who = who, pinned = entry.pinned, notes = entry.notes.orEmpty(), notifyPartner = false,
        )
        notifyTouched = false
        _state.update {
            it.copy(
                loading = false, existing = entry, form = form, source = entry.source, sourceLabel = labelFor(entry.source),
                readOnly = entry.kind == CalendarKind.UNKNOWN || entry.repeat == CalendarRepeat.UNKNOWN,
            )
        }
    }

    /** The source chip: the message's words (gone with a deleted message), or the note's title. */
    private suspend fun labelFor(source: CalendarSource?): String? = when (source?.kind) {
        CalendarSourceKind.MESSAGE -> {
            val text = source.messageId?.let { messageDao.byId(it) }?.takeIf { it.deletedAt == null && it.type == "text" }?.text
            if (text.isNullOrBlank()) "From chat" else "From chat: “${text.trim().take(60)}${if (text.trim().length > 60) "…" else ""}”"
        }
        CalendarSourceKind.LIST, CalendarSourceKind.LIST_ITEM -> source.listId?.let { listsRepository.observe(it).first() }?.let { list ->
            val item = source.itemId?.let { id -> list.items.firstOrNull { it.id == id } }
            if (item != null) "From “${list.title}”: ${item.text.take(40)}" else "From “${list.title}”"
        }
        else -> null
    }

    // ---- edits ----

    private fun edit(moves: Boolean = false, transform: (EntryForm) -> EntryForm) = _state.update { s ->
        var f = transform(s.form)
        // An edit that moves the entry tells the partner by default; a new one already does.
        if (moves && s.existing != null && !notifyTouched) f = f.copy(notifyPartner = true)
        s.copy(form = f, titleError = null)
    }

    fun setKind(k: CalendarKind) = edit { it.copy(kind = k, repeat = if (k == CalendarKind.TODO) CalendarRepeat.NONE else it.repeat, repeatUntil = if (k == CalendarKind.TODO) null else it.repeatUntil) }
    fun setTitle(t: String) = edit { it.copy(title = t.take(120)) }
    fun setEmoji(e: String) = edit { it.copy(emoji = EntryRules.emoji(e)) }
    fun setDate(d: LocalDate) = edit(moves = true) { f ->
        f.copy(date = d, endDate = f.endDate?.takeIf { !it.isBefore(d) && !it.isAfter(d.plusDays(366)) }, repeatUntil = f.repeatUntil?.takeIf { !it.isBefore(d) })
    }
    fun setEndDate(d: LocalDate?) = edit(moves = true) { it.copy(endDate = d?.takeIf { e -> it.date == null || (e.isAfter(it.date) && !e.isAfter(it.date.plusDays(366))) }) }
    fun setAllDay(allDay: Boolean) = edit(moves = true) { f ->
        if (allDay == f.allDay) f
        else if (allDay) f.copy(allDay = true, time = null, durationMinutes = null, timeZone = null, reminders = EntryRules.defaultReminders(true))
        else f.copy(allDay = false, endDate = null, time = f.time ?: LocalTime.of(19, 0), timeZone = f.timeZone ?: _state.value.myZone.id, reminders = EntryRules.defaultReminders(false))
    }
    fun setTime(t: LocalTime) = edit(moves = true) { it.copy(time = t, allDay = false, timeZone = it.timeZone ?: _state.value.myZone.id) }
    fun setDuration(m: Int?) = edit { it.copy(durationMinutes = m) }
    fun setTimeZone(zone: String?) = edit(moves = true) { it.copy(timeZone = zone) }
    fun setRepeat(r: CalendarRepeat) = edit { it.copy(repeat = r, repeatUntil = if (r == CalendarRepeat.NONE) null else it.repeatUntil) }
    fun setRepeatUntil(d: LocalDate?) = edit { it.copy(repeatUntil = d?.takeIf { u -> it.date == null || !u.isBefore(it.date) }) }
    fun toggleReminder(minutes: Int) = edit { f ->
        val next = if (minutes in f.reminders) f.reminders - minutes else f.reminders + minutes
        f.copy(reminders = if (next.size > EntryRules.MAX_REMINDERS) f.reminders else next)
    }
    fun setWho(w: Who) = edit { it.copy(who = w) }
    fun setPinned(p: Boolean) = edit { it.copy(pinned = p) }
    fun setNotes(n: String) = edit { it.copy(notes = n.take(2000)) }
    fun setNotify(on: Boolean) { notifyTouched = true; edit { it.copy(notifyPartner = on) } }

    /** A suggested reading of the title's date: taken as the date (and time), and the title loses the date words. */
    fun applySuggestion(date: LocalDate, time: LocalTime?) {
        val hit = _state.value.suggestion
        edit { f ->
            val title = if (hit != null) DateDetector.suggestTitle(f.title, listOf(hit)).ifBlank { f.title } else f.title
            f.copy(
                title = title, date = date, time = time, allDay = time == null,
                timeZone = if (time != null) f.timeZone ?: _state.value.myZone.id else null,
                reminders = if ((time == null) != f.allDay) EntryRules.defaultReminders(time == null) else f.reminders,
            )
        }
        _state.update { it.copy(suggestion = null) }
    }

    fun recheckExact() = _state.update { it.copy(exactAllowed = scheduler.exactAlarmsAllowed()) }
    fun allowExact() = probe.open(ReadinessFix.EXACT_ALARMS)

    // ---- save, delete, phone calendar ----

    fun save() {
        val s = _state.value
        if (s.saving || s.readOnly) return
        val f = s.form
        if (f.title.isBlank()) { _state.update { it.copy(titleError = "Give it a name") }; return }
        val date = f.date ?: run { effects.trySend("Pick a date first"); return }
        val draft = CalendarEntryDraft(
            kind = f.kind, title = f.title.trim(), date = date, notes = f.notes.trim().ifEmpty { null }, emoji = f.emoji.trim().ifEmpty { null },
            endDate = f.endDate.takeIf { f.allDay }, time = f.time.takeIf { !f.allDay }, durationMinutes = f.durationMinutes.takeIf { !f.allDay },
            timeZone = f.timeZone.takeIf { !f.allDay }, repeat = if (f.kind == CalendarKind.TODO) CalendarRepeat.NONE else f.repeat,
            repeatUntil = f.repeatUntil.takeIf { f.kind != CalendarKind.TODO && f.repeat != CalendarRepeat.NONE },
            reminders = f.reminders.sorted(), remindUserIds = remindIds(f.who), pinned = f.pinned, source = s.source,
        )
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val base = s.existing
            var r = if (base == null) calendar.create(draft, f.notifyPartner, clientKey) else calendar.update(base, draft, f.notifyPartner)
            // A new entry's 404 is the message or list it came from, deleted meanwhile: it goes in
            // without the link (the same clientKey: nothing was stored by the refused request).
            if (base == null && draft.source != null && r is AppResult.Failure && r.error is AppError.NotFound) {
                r = calendar.create(draft.copy(source = null), f.notifyPartner, clientKey)
            }
            _state.update { it.copy(saving = false) }
            when (r) {
                is AppResult.Success -> closes.send(if (base == null) "Added to your calendar" else "Saved")
                is AppResult.Failure -> when (val e = r.error) {
                    // Room already holds the partner's copy: show it, and say so.
                    is AppError.VersionConflict -> { base?.let { calendar.byId(it.id) }?.let { show(it) }; effects.trySend(e.message ?: "Changed meanwhile") }
                    is AppError.NotFound ->
                        if (base != null) closes.send("That was deleted meanwhile")
                        // Only a server from before the calendar answers a create with 404.
                        else effects.trySend("The calendar isn't on the server yet. Try again later.")
                    else -> effects.trySend(e.message ?: "Couldn't save")
                }
            }
        }
    }

    private fun remindIds(who: Who): List<String>? {
        val me = _state.value.myId
        val partner = _state.value.partner?.id
        return when (who) {
            Who.BOTH -> listOfNotNull(me, partner).takeIf { it.isNotEmpty() }
            Who.ME -> listOfNotNull(me)
            Who.PARTNER -> listOfNotNull(partner)
        }
    }

    fun delete() {
        val id = _state.value.existing?.id ?: return
        viewModelScope.launch {
            when (val r = calendar.delete(id)) {
                is AppResult.Success -> closes.send("Deleted")
                is AppResult.Failure -> effects.trySend(r.error.message ?: "Couldn't delete")
            }
        }
    }

    /** "Also add to my phone's calendar": the phone's own new-event screen, filled in. */
    fun addToPhoneCalendar(context: Context) {
        val s = _state.value
        val f = s.form
        val date = f.date ?: run { effects.trySend("Pick a date first"); return }
        val zone = f.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: s.myZone
        val x = CalendarInsert.extras(f.title.trim().ifEmpty { "Plan" }, date, f.endDate.takeIf { f.allDay }, f.time.takeIf { !f.allDay }, f.durationMinutes, zone, s.partner?.displayName)
        if (!CalendarInsert.launch(context, x)) effects.trySend("There's no calendar app on this phone")
    }

    private companion object {
        const val KEY_CLIENT = "clientKey"
    }
}
