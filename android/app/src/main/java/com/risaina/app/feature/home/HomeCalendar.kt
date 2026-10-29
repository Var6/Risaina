package com.risaina.app.feature.home

import com.risaina.app.core.domain.CalendarRules
import com.risaina.app.core.domain.CalendarText
import com.risaina.app.core.domain.Occurrence
import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.feature.calendar.AgendaItem
import java.time.LocalDate
import java.time.ZoneId

/** A pinned entry's countdown chip on Home: "12 days · Goa trip". */
data class PinnedChip(val entryId: String, val text: String)

/** Home's calendar card and chips, as pure functions (HomeCalendarTest). */
object HomeCalendar {
    const val DAYS_AHEAD = 7L
    const val MAX_COMING = 3

    /**
     * "Coming up": to-dos due today or overdue (with their checkboxes), then up to [MAX_COMING]
     * other entries in the next week. Occasions are left out: the hero chips already show them.
     */
    fun comingUp(entries: List<CalendarEntry>, today: LocalDate, zone: ZoneId): List<AgendaItem.Entry> {
        val due = entries.filter { it.kind == CalendarKind.TODO && !it.done && !it.date.isAfter(today) }
            .sortedBy { it.date }
            .map { AgendaItem.Entry(Occurrence(it, it.date, CalendarRules.localStart(it, it.date, zone)), overdue = it.date.isBefore(today)) }
        val dueIds = due.mapTo(HashSet()) { it.entry.id }
        val week = CalendarRules.agenda(entries.filter { it.id !in dueIds && !(it.kind == CalendarKind.TODO && it.done) && it.kind != CalendarKind.UNKNOWN }, today, today.plusDays(DAYS_AHEAD - 1), zone)
            .distinctBy { it.entry.id }
            .take(MAX_COMING)
            .map { AgendaItem.Entry(it) }
        return due + week
    }

    /** Pinned entries still to come, soonest first, as countdown chips. */
    fun pinned(entries: List<CalendarEntry>, today: LocalDate, zone: ZoneId): List<PinnedChip> =
        entries.filter { it.pinned && !(it.kind == CalendarKind.TODO && it.done) }
            .mapNotNull { e -> CalendarRules.agenda(listOf(e), today, today.plusYears(1), zone).firstOrNull() }
            .sortedBy { it.localStart }
            .map { o ->
                val day = maxOf(o.localStart.toLocalDate(), today)
                val lead = when (val c = CalendarText.countdown(day, today)) {
                    "Today", "Tomorrow" -> c
                    else -> CalendarText.plural(java.time.temporal.ChronoUnit.DAYS.between(today, day).toInt(), "day")
                }
                PinnedChip(o.entry.id, "${o.entry.emoji?.let { "$it " } ?: "📌 "}$lead · ${o.entry.title}")
            }
}
