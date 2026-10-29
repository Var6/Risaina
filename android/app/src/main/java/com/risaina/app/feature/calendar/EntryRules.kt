package com.risaina.app.feature.calendar

import com.risaina.app.core.domain.CalendarRules
import com.risaina.app.core.domain.CalendarText
import com.risaina.app.core.domain.Occurrence
import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.CalendarEntryDraft
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.core.model.CalendarSource
import java.text.BreakIterator
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The editor's small rules, pure (EntryRulesTest). */
object EntryRules {
    /** Reminder chips. Timed: at the time, 10 min, 1 hour, 1 day, 1 week. All-day: on the day, 1 day, 3 days, 1 week. */
    val TIMED_REMINDERS = listOf(0, 10, 60, 1440, 10080)
    val ALL_DAY_REMINDERS = listOf(0, 1440, 4320, 10080)
    /** The server keeps at most four. */
    const val MAX_REMINDERS = 4

    /** A new entry's reminders: an hour before, or on the day. */
    fun defaultReminders(allDay: Boolean): Set<Int> = if (allDay) setOf(0) else setOf(60)

    /** Lengths offered for a timed entry; null is "no end". */
    val DURATIONS = listOf(null, 30, 60, 120, 180)

    fun durationLabel(minutes: Int?): String = when (minutes) {
        null -> "No end"
        in 1..59 -> "$minutes min"
        else -> CalendarText.plural(minutes / 60, "hour") + if (minutes % 60 != 0) " ${minutes % 60} min" else ""
    }

    /** At most [max] characters as a person sees them (an emoji with a skin tone is one). */
    fun emoji(input: String, max: Int = 2): String {
        val s = input.trim()
        val chars = BreakIterator.getCharacterInstance().apply { setText(s) }
        var end = 0
        var count = 0
        var next = chars.next()
        while (next != BreakIterator.DONE && count < max) {
            end = next
            count++
            next = chars.next()
        }
        return s.substring(0, end)
    }

    /**
     * The time-zone row's live conversion, when the partner's zone tells another time:
     * "9:00 pm Bea's time = 2:30 am Sat for you", or "9:00 pm for you = 3:30 pm for Bea".
     * [night]: either side falls between 23:00 and 07:00, worth a warning. Null for a floating time
     * (each phone's own clock) or when both clocks agree.
     */
    data class ZoneLine(val text: String, val night: Boolean)

    fun zoneLine(date: LocalDate, time: LocalTime, anchor: ZoneId?, mine: ZoneId, partner: ZoneId, partnerName: String, locale: Locale = Locale.getDefault()): ZoneLine? {
        if (anchor == null) return null
        val at = ZonedDateTime.of(date, time, anchor)
        val forMe = at.withZoneSameInstant(mine)
        val forThem = at.withZoneSameInstant(partner)
        if (forMe.toLocalDateTime() == forThem.toLocalDateTime()) return null
        val name = partnerName.substringBefore(" ").ifBlank { "them" }
        fun clock(z: ZonedDateTime) = CalendarText.time(z.toLocalTime(), locale) +
            if (z.toLocalDate() != date) " " + z.format(DateTimeFormatter.ofPattern("EEE", locale)) else ""
        val text = if (anchor.rules == partner.rules && anchor.rules != mine.rules) {
            "${clock(forThem)} $name's time = ${clock(forMe)} for you"
        } else {
            "${clock(forMe)} for you = ${clock(forThem)} for $name"
        }
        return ZoneLine(text, night = isNight(forMe.toLocalTime()) || isNight(forThem.toLocalTime()))
    }

    private fun isNight(t: LocalTime) = !t.isBefore(LocalTime.of(23, 0)) || t.isBefore(LocalTime.of(7, 0))

    /**
     * A row's when-line on this phone: "Sat 14 Nov", "Fri 13 Nov – Sun 15 Nov", "Sat 14 Nov, 8:00 pm –
     * 10:00 pm", with "Every week" for a repeat. The year is added outside [thisYear].
     */
    fun whenLine(o: Occurrence, thisYear: Int, locale: Locale = Locale.getDefault()): String {
        val e = o.entry
        val start = o.localStart
        val base = if (e.time == null) {
            val span = CalendarRules.spanDays(e)
            CalendarText.day(start.toLocalDate(), thisYear, locale) + if (span > 0) " – " + CalendarText.day(start.toLocalDate().plusDays(span), thisYear, locale) else ""
        } else {
            "${CalendarText.day(start.toLocalDate(), thisYear, locale)}, ${CalendarText.time(start.toLocalTime(), locale)}" +
                (e.durationMinutes?.let { " – " + CalendarText.time(start.toLocalTime().plusMinutes(it.toLong()), locale) } ?: "")
        }
        val repeat = when (e.repeat) {
            CalendarRepeat.WEEKLY -> "Every week"
            CalendarRepeat.MONTHLY -> "Every month"
            CalendarRepeat.YEARLY -> "Every year"
            else -> null
        }.takeIf { e.kind != CalendarKind.TODO }
        return listOfNotNull(base, repeat).joinToString(" · ")
    }

    /** The editor's fields for a saved entry. "Let them know" starts off: an edit tells only when it moves the entry. */
    fun formFor(entry: CalendarEntry, me: String?, partnerId: String?): EntryForm {
        val who = when (entry.remindUserIds.toSet()) {
            setOfNotNull(me) -> Who.ME
            setOfNotNull(partnerId) -> Who.PARTNER
            else -> Who.BOTH
        }
        return EntryForm(
            kind = entry.kind.takeIf { it != CalendarKind.UNKNOWN } ?: CalendarKind.EVENT, title = entry.title, emoji = entry.emoji.orEmpty(),
            date = entry.date, endDate = entry.endDate, allDay = entry.allDay, time = entry.time, durationMinutes = entry.durationMinutes,
            timeZone = entry.timeZone, repeat = entry.repeat, repeatUntil = entry.repeatUntil, reminders = entry.reminders.toSet(),
            who = who, pinned = entry.pinned, notes = entry.notes.orEmpty(), notifyPartner = false,
        )
    }

    /** What a save sends: the form's fields that apply to its kind and to all day or not. [date] is the form's, checked. */
    fun draft(f: EntryForm, date: LocalDate, remindUserIds: List<String>?, source: CalendarSource?) = CalendarEntryDraft(
        kind = f.kind, title = f.title.trim(), date = date, notes = f.notes.trim().ifEmpty { null }, emoji = f.emoji.trim().ifEmpty { null },
        endDate = f.endDate.takeIf { f.allDay }, time = f.time.takeIf { !f.allDay }, durationMinutes = f.durationMinutes.takeIf { !f.allDay },
        timeZone = f.timeZone.takeIf { !f.allDay }, repeat = if (f.kind == CalendarKind.TODO) CalendarRepeat.NONE else f.repeat,
        repeatUntil = f.repeatUntil.takeIf { f.kind != CalendarKind.TODO && f.repeat != CalendarRepeat.NONE },
        reminders = f.reminders.sorted(), remindUserIds = remindUserIds, pinned = f.pinned, source = source,
    )

    /**
     * A save refused because the partner saved first (409): the person's edits laid over the
     * partner's copy, so the next save sends only what the person changed. [old] is the form of the
     * entry the editor was opened on, [new] the partner's copy, [mine] what the person has now.
     * Every field the person changed from [old] keeps their value; every other field takes [new]'s.
     * "When" (all day, time, zone, length) is one field: a time from one side and "all day" from the
     * other would be neither. Words count as changed only when they differ beyond spaces at the ends,
     * which the save trims anyway.
     */
    fun rebase(old: EntryForm, new: EntryForm, mine: EntryForm): EntryForm {
        fun <T> pick(o: T, n: T, m: T): T = if (m != o) m else n
        fun words(o: String, n: String, m: String): String = if (m.trim() != o.trim()) m else n
        fun timing(f: EntryForm) = listOf(f.allDay, f.time.takeIf { !f.allDay }, f.timeZone.takeIf { !f.allDay }, f.durationMinutes.takeIf { !f.allDay })
        val whenFrom = if (timing(mine) != timing(old)) mine else new
        val date = pick(old.date, new.date, mine.date)
        return EntryForm(
            kind = pick(old.kind, new.kind, mine.kind),
            title = words(old.title, new.title, mine.title),
            emoji = words(old.emoji, new.emoji, mine.emoji),
            date = date,
            // Kept only where it still fits the date (the partner may have moved it past the end).
            endDate = pick(old.endDate, new.endDate, mine.endDate)?.takeIf { e -> date == null || e.isAfter(date) },
            allDay = whenFrom.allDay, time = whenFrom.time, timeZone = whenFrom.timeZone, durationMinutes = whenFrom.durationMinutes,
            repeat = pick(old.repeat, new.repeat, mine.repeat),
            repeatUntil = pick(old.repeatUntil, new.repeatUntil, mine.repeatUntil)?.takeIf { u -> date == null || !u.isBefore(date) },
            reminders = pick(old.reminders, new.reminders, mine.reminders),
            who = pick(old.who, new.who, mine.who),
            pinned = pick(old.pinned, new.pinned, mine.pinned),
            notes = words(old.notes, new.notes, mine.notes),
            notifyPartner = mine.notifyPartner,
        )
    }

    /** Whether the two zones tell a different time around [date] (the row is shown only then). */
    fun zonesDiffer(mine: ZoneId, partner: ZoneId?, date: LocalDate): Boolean {
        partner ?: return false
        val noon = date.atTime(12, 0)
        return mine.rules.getOffset(noon) != partner.rules.getOffset(noon)
    }
}
