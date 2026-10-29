package com.risaina.app.feature.calendar

import com.risaina.app.core.domain.CalendarOccasions
import com.risaina.app.core.domain.CalendarRules
import com.risaina.app.core.domain.Occasion
import com.risaina.app.core.domain.OccasionDay
import com.risaina.app.core.domain.Occurrence
import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.CalendarKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One line of the calendar: an entry's occurrence, or an occasion's day. */
sealed interface AgendaItem {
    val key: String
    /** The day it shows under on this phone. */
    val day: LocalDate

    data class Entry(val occurrence: Occurrence, val overdue: Boolean = false) : AgendaItem {
        val entry: CalendarEntry get() = occurrence.entry
        override val key: String get() = "${entry.id}|${occurrence.date}"
        override val day: LocalDate get() = occurrence.localStart.toLocalDate()
    }

    data class Occasion(val occasionDay: OccasionDay) : AgendaItem {
        override val key: String get() = "${occasionDay.occasion.key}|${occasionDay.date}"
        override val day: LocalDate get() = occasionDay.date
    }
}

data class AgendaSection(val title: String, val items: List<AgendaItem>)

/** What a month cell shows: up to three dots, one per kind of thing on that day. */
enum class DayMark { OCCASION, EVENT, TODO }

/** The calendar screen's lists, as pure functions (CalendarAgendaTest). */
object CalendarAgenda {
    /** How far "Later" reaches. */
    const val UPCOMING_MONTHS = 12L

    /**
     * The Upcoming tab: Overdue to-dos, then Today, Tomorrow, This week, This month and Later (a
     * year). A repeating entry shows once, at its next occurrence ("Every week" on its row). A trip
     * under way shows under Today. Done to-dos stay on their day (ticked) and leave once past.
     */
    fun upcoming(
        entries: List<CalendarEntry>,
        occasions: List<Occasion>,
        today: LocalDate,
        zone: ZoneId,
        firstDayOfWeek: DayOfWeek,
    ): List<AgendaSection> {
        val horizon = today.plusMonths(UPCOMING_MONTHS)
        val overdue = entries.filter { CalendarRules.overdue(it, today) }
            .sortedBy { it.date }
            .map { AgendaItem.Entry(Occurrence(it, it.date, CalendarRules.localStart(it, it.date, zone)), overdue = true) }
        val coming = entries.filterNot { CalendarRules.overdue(it, today) }.mapNotNull { e ->
            CalendarRules.agenda(listOf(e), today, horizon, zone).firstOrNull()?.let { AgendaItem.Entry(it) }
        } + occasions.mapNotNull { o -> CalendarOccasions.next(o, today)?.takeIf { !it.date.isAfter(horizon) }?.let { AgendaItem.Occasion(it) } }
        val endOfWeek = today.with(TemporalAdjusters.nextOrSame(firstDayOfWeek.minus(1)))
        fun section(d: LocalDate): String = when {
            !d.isAfter(today) -> "Today"
            d == today.plusDays(1) -> "Tomorrow"
            !d.isAfter(endOfWeek) -> "This week"
            YearMonth.from(d) == YearMonth.from(today) -> "This month"
            else -> "Later"
        }
        val grouped = sorted(coming).groupBy { section(it.day) }
        return buildList {
            if (overdue.isNotEmpty()) add(AgendaSection("Overdue", overdue))
            for (title in listOf("Today", "Tomorrow", "This week", "This month", "Later")) grouped[title]?.let { add(AgendaSection(title, it)) }
        }
    }

    /** Every item on this phone's [day]: occasions, then all-day entries (trips too), then by time. */
    fun dayItems(entries: List<CalendarEntry>, occasions: List<Occasion>, day: LocalDate, zone: ZoneId): List<AgendaItem> =
        (CalendarOccasions.between(occasions, day, day).map { AgendaItem.Occasion(it) } + CalendarRules.agenda(entries, day, day, zone).map { AgendaItem.Entry(it) })
            .sortedWith(withinDay)

    /** The 6 weeks a month grid shows, starting on the locale's first day of the week. */
    fun gridDays(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> {
        val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        return (0 until 42).map { start.plusDays(it.toLong()) }
    }

    /** The dots for each day in [from]..[to]: an occasion, an event, a to-do (a trip marks every day). */
    fun marks(entries: List<CalendarEntry>, occasions: List<Occasion>, from: LocalDate, to: LocalDate, zone: ZoneId): Map<LocalDate, Set<DayMark>> {
        val out = HashMap<LocalDate, MutableSet<DayMark>>()
        CalendarOccasions.between(occasions, from, to).forEach { out.getOrPut(it.date) { mutableSetOf() } += DayMark.OCCASION }
        CalendarRules.agenda(entries, from, to, zone).forEach { o ->
            val mark = if (o.entry.kind == CalendarKind.TODO) DayMark.TODO else DayMark.EVENT
            var d = maxOf(o.localStart.toLocalDate(), from)
            val last = minOf(o.localStart.toLocalDate().plusDays(CalendarRules.spanDays(o.entry)), to)
            while (!d.isAfter(last)) { out.getOrPut(d) { mutableSetOf() } += mark; d = d.plusDays(1) }
        }
        return out
    }

    /** Occasions, then all-day entries (a trip begun earlier too), then by time. */
    private val withinDay: Comparator<AgendaItem> =
        compareBy<AgendaItem> { if (it is AgendaItem.Occasion) 0 else if ((it as AgendaItem.Entry).entry.time == null) 1 else 2 }
            .thenBy { (it as? AgendaItem.Entry)?.occurrence?.localStart }
            .thenBy { (it as? AgendaItem.Entry)?.entry?.title ?: (it as AgendaItem.Occasion).occasionDay.title }

    private fun sorted(items: List<AgendaItem>): List<AgendaItem> = items.sortedWith(compareBy<AgendaItem> { it.day }.then(withinDay))
}
