package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.database.CalendarDao
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.CalendarEntryDraft
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.core.network.CalendarEntryDto
import com.risaina.app.core.network.CreateCalendarRequest
import com.risaina.app.core.network.PatchCalendarRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import com.risaina.app.core.network.entry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The shared calendar. Screens read Room; writes need a connection (as lists do) and store the
 * server's reply, and a retried create is made safe by its clientKey. Occasions (anniversary,
 * birthdays) are not stored here: they are derived on the phone.
 */
@Singleton
class CalendarRepository @Inject constructor(
    private val api: RisainaApi,
    private val dao: CalendarDao,
    private val coupleDao: CoupleDao,
    private val json: Json,
    private val deletions: CalendarDeletions,
) {
    val all: Flow<List<CalendarEntry>> = dao.all().map { l -> l.mapNotNull { it.toDomain() } }
    fun observe(id: String): Flow<CalendarEntry?> = dao.observe(id).map { it?.toDomain() }
    fun forMessages(messageIds: List<String>): Flow<List<CalendarEntry>> = dao.forMessages(messageIds).map { l -> l.mapNotNull { it.toDomain() } }
    fun forList(listId: String): Flow<List<CalendarEntry>> = dao.forList(listId).map { l -> l.mapNotNull { it.toDomain() } }
    suspend fun byId(id: String): CalendarEntry? = dao.byId(id)?.toDomain()

    private val _available = MutableStateFlow<Boolean?>(null)
    /**
     * False once GET /calendar answered 404: the server predates the calendar, so its entry points
     * hide. Null until the first refresh has answered.
     */
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    /**
     * Every entry (the server caps a couple at 1000), so the table becomes the server's list, except
     * for what changed while the GET was on its way: an entry saved or received meanwhile is kept
     * (CalendarDao.syncFromServer), and one deleted meanwhile is not written back from the older answer.
     */
    suspend fun refresh(): AppResult<Unit> {
        val requestedAt = System.currentTimeMillis()
        val r = apiCall { api.calendar() }
        when (r) {
            is AppResult.Success -> {
                _available.value = true
                dao.syncFromServer(r.value.items.filterNot { deletions.deletedSince(it.id, requestedAt) }.map { it.toEntity() }, requestedAt)
                onChanged()
            }
            is AppResult.Failure -> if (r.error is AppError.NotFound) _available.value = false
        }
        return r.map { }
    }

    /**
     * [notifyPartner]: the partner's phone posts "… added to your calendar". [clientKey] stays the
     * same across retries of one save, so a reply lost on the way never makes a second entry.
     */
    suspend fun create(draft: CalendarEntryDraft, notifyPartner: Boolean, clientKey: String = UUID.randomUUID().toString()): AppResult<CalendarEntry> =
        when (val r = apiCall { api.createCalendar(CalendarRequests.create(draft, clientKey, notifyPartner)) }) {
            is AppResult.Success -> store(r.value.entry)
            is AppResult.Failure -> r
        }

    /**
     * Saves the editor's [draft] over [base], the entry it was opened on: only what changed, at
     * base's version. If the partner saved in between, Room gets their copy and the result is
     * [AppError.VersionConflict] ("Raina changed this meanwhile"), so the editor can reload.
     */
    suspend fun update(base: CalendarEntry, draft: CalendarEntryDraft, notifyPartner: Boolean): AppResult<CalendarEntry> {
        val req = CalendarRequests.patch(base, draft, notifyPartner) ?: return AppResult.Success(base)
        return patch(base.id, req)
    }

    /**
     * Ticks or unticks a to-do. Sent without an expected version: a tick should not fail because
     * the partner renamed the to-do meanwhile, and the server applies it to the current version.
     */
    suspend fun setDone(id: String, done: Boolean): AppResult<CalendarEntry> = patch(id, PatchCalendarRequest(done = done))

    /** Already gone (the partner deleted it first) counts as done. */
    suspend fun delete(id: String): AppResult<Unit> {
        val r = apiCall { api.deleteCalendar(id) }
        if (r is AppResult.Failure && r.error !is AppError.NotFound) return r
        deletions.record(id)
        dao.delete(id)
        onChanged()
        return AppResult.Success(Unit)
    }

    private suspend fun patch(id: String, req: PatchCalendarRequest): AppResult<CalendarEntry> =
        when (val r = apiCall { api.patchCalendar(id, req) }) {
            is AppResult.Success -> store(r.value.entry)
            is AppResult.Failure -> when (val e = r.error) {
                is AppError.VersionConflict -> {
                    // Reload: the partner's copy from the error, or the whole calendar if it was not sent.
                    e.entry(json)?.let { dao.upsertIfNewer(it.toEntity()); onChanged() } ?: refresh()
                    AppResult.Failure(AppError.VersionConflict(changedMeanwhile(), e.details))
                }
                is AppError.NotFound -> { deletions.record(id); dao.delete(id); onChanged(); r }
                else -> r
            }
        }

    private suspend fun store(dto: CalendarEntryDto): AppResult<CalendarEntry> {
        val row = dto.toEntity()
        dao.upsertIfNewer(row)
        onChanged()
        return row.toDomain()?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.Server("The calendar sent an entry this version can't read"))
    }

    private suspend fun changedMeanwhile(): String = "${coupleDao.partner()?.displayName ?: "Your partner"} changed this meanwhile"

    /** After every change to the stored entries. */
    private fun onChanged() {
        // TODO(P2): ask the ReminderScheduler to re-arm (debounced, local only, no network).
    }
}

/**
 * Entries deleted lately (here, or by the partner's calendar.deleted), with when. A GET /calendar
 * that started before a delete still lists the entry; [CalendarRepository.refresh] skips it so it
 * does not come back (and get reminded). Memory only: a GET never outlives the process, and a few
 * minutes cover the slowest one.
 */
@Singleton
class CalendarDeletions @Inject constructor() {
    private val deleted = LinkedHashMap<String, Long>()

    @Synchronized fun record(id: String, atMillis: Long = System.currentTimeMillis()) {
        deleted.entries.removeAll { atMillis - it.value > KEEP_MS }
        deleted[id] = atMillis
    }

    /** Whether [id] was deleted at or after [sinceMillis]. */
    @Synchronized fun deletedSince(id: String, sinceMillis: Long): Boolean = deleted[id]?.let { it >= sinceMillis } == true

    private companion object {
        const val KEEP_MS = 10 * 60_000L
    }
}

/** Request bodies for the calendar, as pure functions. */
object CalendarRequests {
    fun create(d: CalendarEntryDraft, clientKey: String, notifyPartner: Boolean) = CreateCalendarRequest(
        kind = d.kind.wire, title = d.title.trim(), date = d.date.toString(),
        notes = d.notes.cleaned(), emoji = d.emoji.cleaned(), endDate = d.endDate?.toString(),
        time = d.time?.format(HH_MM), durationMinutes = d.durationMinutes, timeZone = d.timeZone,
        repeat = d.repeat.takeIf { it != CalendarRepeat.UNKNOWN }?.wire, repeatUntil = d.repeatUntil?.toString(),
        reminders = d.reminders.distinct().sorted(), remindUserIds = d.remindUserIds, pinned = d.pinned,
        source = d.source?.toDto(), clientKey = clientKey, notifyPartner = notifyPartner.takeIf { it },
    )

    /**
     * Only the fields [d] changes from [base], at base's version, or null when nothing changed. A
     * field changed to nothing (the notes emptied, all-day again) is sent as an explicit null.
     * [CalendarEntryDraft.remindUserIds] null means unchanged here; the source is never changed.
     */
    fun patch(base: CalendarEntry, d: CalendarEntryDraft, notifyPartner: Boolean): PatchCalendarRequest? {
        val clear = mutableSetOf<String>()
        fun <T> nullable(key: String, old: T?, new: T?): T? {
            if (old == new) return null
            if (new == null) clear += key
            return new
        }
        val changes = PatchCalendarRequest(
            kind = d.kind.takeIf { it != base.kind && it != CalendarKind.UNKNOWN }?.wire,
            title = d.title.trim().takeIf { it != base.title },
            date = d.date.takeIf { it != base.date }?.toString(),
            notes = nullable("notes", base.notes, d.notes.cleaned()),
            emoji = nullable("emoji", base.emoji, d.emoji.cleaned()),
            endDate = nullable("endDate", base.endDate?.toString(), d.endDate?.toString()),
            time = nullable("time", base.time?.format(HH_MM), d.time?.format(HH_MM)),
            durationMinutes = nullable("durationMinutes", base.durationMinutes, d.durationMinutes),
            timeZone = nullable("timeZone", base.timeZone, d.timeZone),
            repeat = d.repeat.takeIf { it != base.repeat && it != CalendarRepeat.UNKNOWN }?.wire,
            repeatUntil = nullable("repeatUntil", base.repeatUntil?.toString(), d.repeatUntil?.toString()),
            reminders = d.reminders.distinct().sorted().takeIf { it != base.reminders },
            remindUserIds = d.remindUserIds?.takeIf { it.toSet() != base.remindUserIds.toSet() },
            pinned = d.pinned.takeIf { it != base.pinned },
            clear = clear,
        )
        if (changes == PatchCalendarRequest()) return null
        return changes.copy(expectedVersion = base.version, notifyPartner = notifyPartner.takeIf { it })
    }

    private fun String?.cleaned(): String? = this?.trim()?.ifEmpty { null }
}
