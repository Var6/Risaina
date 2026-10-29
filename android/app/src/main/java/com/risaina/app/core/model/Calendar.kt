package com.risaina.app.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Kinds of calendar entry. A plain string on the wire (the server answers with strings, not an
 * enum), so a kind added by a newer app reaches this one as [UNKNOWN] instead of failing the sync.
 */
enum class CalendarKind(val wire: String) {
    EVENT("event"), TODO("todo"),
    /** From a newer app: listed on its date, never reminded, never sent back. */
    UNKNOWN("");
    companion object { fun fromWire(v: String) = entries.firstOrNull { it != UNKNOWN && it.wire == v } ?: UNKNOWN }
}

enum class CalendarRepeat(val wire: String) {
    NONE("none"), WEEKLY("weekly"), MONTHLY("monthly"), YEARLY("yearly"),
    /** A rule from a newer app: shown once, on its first date, and never sent back. */
    UNKNOWN("");
    companion object { fun fromWire(v: String) = entries.firstOrNull { it != UNKNOWN && it.wire == v } ?: UNKNOWN }
}

enum class CalendarSourceKind(val wire: String) {
    MESSAGE("message"), LIST("list"), LIST_ITEM("list_item"), UNKNOWN("");
    companion object { fun fromWire(v: String) = entries.firstOrNull { it != UNKNOWN && it.wire == v } ?: UNKNOWN }
}

/** Where an entry came from. Ids only, never copied text: deleting the message removes its words everywhere. */
data class CalendarSource(
    val kind: CalendarSourceKind,
    val messageId: String? = null,
    val listId: String? = null,
    val itemId: String? = null,
)

/**
 * A shared calendar entry: an event, or a to-do with a due date. Dates float (both phones show the
 * same date). A timed entry happens at [time] in its anchor [timeZone], or at [time] on each phone's
 * own clock when that is null. Occasions (anniversary, birthdays, next visit) are not entries: they
 * are worked out on the phone from data the app already has.
 */
data class CalendarEntry(
    val id: String,
    val coupleId: String,
    val createdBy: String,
    val updatedBy: String?,
    val kind: CalendarKind,
    val title: String,
    val notes: String?,
    val emoji: String?,
    /** The first or only date; a to-do's due date. */
    val date: LocalDate,
    /** All-day entries over several days (a trip) end here, inclusive. */
    val endDate: LocalDate?,
    /** Null means all day. */
    val time: LocalTime?,
    val durationMinutes: Int?,
    /** IANA id as stored, kept as text so an id this phone's time-zone data lacks is not lost on a save. */
    val timeZone: String?,
    val repeat: CalendarRepeat,
    /** Inclusive. */
    val repeatUntil: LocalDate?,
    /** Minutes before, ascending. All-day entries count back from each phone's own all-day reminder time. */
    val reminders: List<Int>,
    /** Only these phones remind; for a to-do, who does it. */
    val remindUserIds: List<String>,
    val pinned: Boolean,
    val done: Boolean,
    val doneAt: Instant?,
    val doneBy: String?,
    val source: CalendarSource?,
    val clientKey: String?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val allDay: Boolean get() = time == null

    /** The anchor zone. Null for "each our own clock", and for an id this phone does not know. */
    val zone: ZoneId? get() = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
}

/**
 * What the editor saves: everything an entry has that a person sets. [CalendarRepository] sends it
 * whole on create, and only what differs from the entry it was opened on for an edit.
 */
data class CalendarEntryDraft(
    val kind: CalendarKind,
    val title: String,
    val date: LocalDate,
    val notes: String? = null,
    val emoji: String? = null,
    val endDate: LocalDate? = null,
    val time: LocalTime? = null,
    val durationMinutes: Int? = null,
    val timeZone: String? = null,
    val repeat: CalendarRepeat = CalendarRepeat.NONE,
    val repeatUntil: LocalDate? = null,
    val reminders: List<Int> = emptyList(),
    /** Null: both of us (the server's default) on a create, and "not changed" on an edit. */
    val remindUserIds: List<String>? = null,
    val pinned: Boolean = false,
    val source: CalendarSource? = null,
)

/** The editor's starting point for an existing entry. */
fun CalendarEntry.toDraft() = CalendarEntryDraft(
    kind = kind, title = title, date = date, notes = notes, emoji = emoji, endDate = endDate, time = time,
    durationMinutes = durationMinutes, timeZone = timeZone, repeat = repeat, repeatUntil = repeatUntil,
    reminders = reminders, remindUserIds = remindUserIds, pinned = pinned, source = source,
)
