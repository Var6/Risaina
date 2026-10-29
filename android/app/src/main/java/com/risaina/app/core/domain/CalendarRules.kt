package com.risaina.app.core.domain

import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Where calendar entries fall, as pure functions (calendar.md §9-10).
 *
 * - Repeats count from the first date: occurrence n is date.plusWeeks/Months/Years(n), never a step
 *   from the previous one, so a monthly entry on the 31st is on 28 or 29 February and back on 31
 *   March, and a yearly 29 February is the 28th in other years. repeatUntil is included.
 * - All-day entries float: the same date on both phones.
 * - A timed entry happens at its time in its anchor zone ([CalendarEntry.timeZone]), or at its time on
 *   each phone's own clock when it has none. An anchored "Sunday 9 pm London" stays 9 pm in London
 *   across the clock changes, and can fall on a different date in Kolkata.
 */
object CalendarRules {
    /** The start dates of [e]'s occurrences from [from] to [to], both included, in its own (floating) dates. */
    fun occurrences(e: CalendarEntry, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        val repeat = if (e.kind == CalendarKind.TODO) CalendarRepeat.NONE else e.repeat
        val last = listOfNotNull(to, e.repeatUntil).min()
        if (repeat == CalendarRepeat.NONE || repeat == CalendarRepeat.UNKNOWN) {
            return listOf(e.date).filter { !it.isBefore(from) && !it.isAfter(to) }
        }
        // Start a step before the window, so the first occurrence in it is never skipped.
        val unit = when (repeat) { CalendarRepeat.WEEKLY -> ChronoUnit.WEEKS; CalendarRepeat.MONTHLY -> ChronoUnit.MONTHS; else -> ChronoUnit.YEARS }
        var n = maxOf(0L, unit.between(e.date, from) - 1)
        val out = mutableListOf<LocalDate>()
        while (true) {
            val d = nth(e.date, repeat, n)
            if (d.isAfter(last)) break
            if (!d.isBefore(from)) out += d
            n++
        }
        return out
    }

    fun nth(start: LocalDate, repeat: CalendarRepeat, n: Long): LocalDate = when (repeat) {
        CalendarRepeat.WEEKLY -> start.plusWeeks(n)
        CalendarRepeat.MONTHLY -> start.plusMonths(n)
        CalendarRepeat.YEARLY -> start.plusYears(n)
        else -> start
    }

    /** Days an occurrence covers after its start: a trip from Fri to Sun is 2. */
    fun spanDays(e: CalendarEntry): Long = if (e.allDay && e.endDate != null) ChronoUnit.DAYS.between(e.date, e.endDate).coerceIn(0, 366) else 0

    /** The instant a timed occurrence starts; null for all-day ones, which have no single instant. */
    fun start(e: CalendarEntry, occurrence: LocalDate, deviceZone: ZoneId): Instant? {
        val time = e.time ?: return null
        return ZonedDateTime.of(occurrence, time, e.zone ?: deviceZone).toInstant()
    }

    /** When an occurrence starts on this phone: its own date for all-day, the converted time for timed. */
    fun localStart(e: CalendarEntry, occurrence: LocalDate, deviceZone: ZoneId): LocalDateTime =
        start(e, occurrence, deviceZone)?.atZone(deviceZone)?.toLocalDateTime() ?: occurrence.atStartOfDay()

    /** The same start on the partner's clock, when they share a zone that tells a different time. Null otherwise. */
    fun partnerStart(e: CalendarEntry, occurrence: LocalDate, deviceZone: ZoneId, partnerZone: ZoneId?): LocalDateTime? {
        if (partnerZone == null) return null
        val instant = start(e, occurrence, deviceZone) ?: return null
        // A floating time is "each our own clock": the partner sees the same time, so no second line.
        if (e.zone == null) return null
        // On one clock there is only one time.
        if (TimeZones.sameClock(deviceZone, partnerZone, instant)) return null
        return instant.atZone(partnerZone).toLocalDateTime()
    }

    /**
     * Occurrences that touch this phone's days [from]..[to], each with its local start, in time
     * order. A timed anchored entry can land a day earlier or later here than its written date, so
     * the window is widened by a day and then cut on the local date; a trip covers every day it spans.
     */
    fun agenda(entries: List<CalendarEntry>, from: LocalDate, to: LocalDate, deviceZone: ZoneId): List<Occurrence> = buildList {
        entries.forEach { e ->
            val span = spanDays(e)
            occurrences(e, from.minusDays(span + 1), to.plusDays(1)).forEach { d ->
                val local = localStart(e, d, deviceZone)
                val lastDay = local.toLocalDate().plusDays(span)
                if (!lastDay.isBefore(from) && !local.toLocalDate().isAfter(to)) add(Occurrence(e, d, local))
            }
        }
    }.sortedWith(compareBy<Occurrence> { it.localStart.toLocalDate() }.thenBy { it.entry.time != null }.thenBy { it.localStart })

    /** Whether occurrence [o] covers this phone's [day] (a trip covers several). */
    fun covers(o: Occurrence, day: LocalDate): Boolean {
        val first = o.localStart.toLocalDate()
        return !day.isBefore(first) && !day.isAfter(first.plusDays(spanDays(o.entry)))
    }

    /** A to-do that is not done and was due before [today]. */
    fun overdue(e: CalendarEntry, today: LocalDate): Boolean = e.kind == CalendarKind.TODO && !e.done && e.date.isBefore(today)
}

/** One occurrence of an entry: [date] as written (the key for reminders), [localStart] on this phone. */
data class Occurrence(val entry: CalendarEntry, val date: LocalDate, val localStart: LocalDateTime)
