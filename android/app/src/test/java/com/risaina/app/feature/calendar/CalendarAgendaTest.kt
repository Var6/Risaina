package com.risaina.app.feature.calendar

import com.risaina.app.core.domain.CalendarOccasions
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.core.model.Couple
import com.risaina.app.core.model.CoupleStatus
import com.risaina.app.testutil.calendarEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** The calendar screen's Upcoming sections, month grid and day list. Today is Thursday 29 October 2026. */
class CalendarAgendaTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 29)
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
    private val couple = Couple("c", CoupleStatus.ACTIVE, listOf("me", "bea"), d(11, 14, 2023), null, null, Instant.EPOCH)

    private fun titles(sections: List<AgendaSection>) = sections.associate { s ->
        s.title to s.items.map { (it as? AgendaItem.Entry)?.entry?.title ?: (it as AgendaItem.Occasion).occasionDay.title }
    }

    @Test fun `upcoming sections, overdue first, each repeat once`() {
        val entries = listOf(
            calendarEntry(id = "late", title = "Book tickets", kind = CalendarKind.TODO, date = d(10, 27)),
            calendarEntry(id = "doneLate", title = "Old to-do", kind = CalendarKind.TODO, date = d(10, 27), done = true),
            calendarEntry(id = "today", title = "Call", date = today, time = LocalTime.of(20, 0)),
            calendarEntry(id = "tmrw", title = "Dinner", date = d(10, 30)),
            calendarEntry(id = "sat", title = "Movie", date = d(10, 31)),
            calendarEntry(id = "nov", title = "Goa", date = d(11, 20)),
            calendarEntry(id = "weekly", title = "Sunday call", date = d(10, 4), repeat = CalendarRepeat.WEEKLY),
            calendarEntry(id = "far", title = "Too far", date = d(12, 1, 2027)),
        )
        val occasions = CalendarOccasions.derive(couple, "me", null, null, emptyList())
        val s = CalendarAgenda.upcoming(entries, occasions, today, zone, DayOfWeek.MONDAY)
        assertEquals(listOf("Overdue", "Today", "Tomorrow", "This week", "Later"), s.map { it.title })
        val t = titles(s)
        assertEquals(listOf("Book tickets"), t["Overdue"])
        assertEquals(listOf("Call"), t["Today"])
        assertEquals(listOf("Dinner"), t["Tomorrow"])
        assertEquals(listOf("Movie", "Sunday call"), t["This week"])
        assertEquals(listOf("Our 3rd anniversary", "Goa"), t["Later"])
        assertTrue((s.first().items.single() as AgendaItem.Entry).overdue)
    }

    @Test fun `this week ends on the locale's last day of the week`() {
        val sun = calendarEntry(title = "Brunch", date = d(11, 1))
        assertEquals("This week", CalendarAgenda.upcoming(listOf(sun), emptyList(), today, zone, DayOfWeek.MONDAY).single().title)
        // Where weeks start on Sunday, Sunday 1 November is next week, and in another month: Later.
        assertEquals("Later", CalendarAgenda.upcoming(listOf(sun), emptyList(), today, zone, DayOfWeek.SUNDAY).single().title)
        val sat = calendarEntry(title = "Movie", date = d(10, 31))
        assertEquals("This week", CalendarAgenda.upcoming(listOf(sat), emptyList(), today, zone, DayOfWeek.SUNDAY).single().title)
    }

    @Test fun `this month holds the rest of the month`() {
        val e = calendarEntry(title = "Halloween party", date = d(10, 31))
        val s = CalendarAgenda.upcoming(listOf(e), emptyList(), d(10, 20), zone, DayOfWeek.MONDAY)
        assertEquals("This month", s.single().title)
    }

    @Test fun `a trip under way is under today`() {
        val trip = calendarEntry(title = "Goa", date = d(10, 27), endDate = d(10, 31))
        val s = CalendarAgenda.upcoming(listOf(trip), emptyList(), today, zone, DayOfWeek.MONDAY)
        assertEquals("Today", s.single().title)
    }

    @Test fun `the month grid is six weeks from the first day of the week`() {
        val days = CalendarAgenda.gridDays(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(42, days.size)
        assertEquals(d(10, 26), days.first())
        assertEquals(d(12, 6), days.last())
        assertEquals(d(11, 1), CalendarAgenda.gridDays(YearMonth.of(2026, 11), DayOfWeek.SUNDAY).first())
    }

    @Test fun `day marks and the day list`() {
        val entries = listOf(
            calendarEntry(id = "trip", title = "Goa", date = d(11, 13), endDate = d(11, 15)),
            calendarEntry(id = "todo", title = "Pack", kind = CalendarKind.TODO, date = d(11, 13)),
            calendarEntry(id = "call", title = "Call", date = d(11, 14), time = LocalTime.of(9, 0)),
        )
        val occasions = CalendarOccasions.derive(couple, "me", null, null, emptyList())
        val marks = CalendarAgenda.marks(entries, occasions, d(11, 1), d(11, 30), zone)
        assertEquals(setOf(DayMark.EVENT, DayMark.TODO), marks[d(11, 13)])
        assertEquals(setOf(DayMark.EVENT, DayMark.OCCASION), marks[d(11, 14)])
        assertEquals(setOf(DayMark.EVENT), marks[d(11, 15)])
        assertEquals(null, marks[d(11, 16)])
        val day = CalendarAgenda.dayItems(entries, occasions, d(11, 14), zone)
        assertEquals(listOf("occ:anniversary|2026-11-14", "trip|2026-11-13", "call|2026-11-14"), day.map { it.key })
    }
}
