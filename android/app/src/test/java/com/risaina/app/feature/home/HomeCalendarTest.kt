package com.risaina.app.feature.home

import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.testutil.calendarEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Home's "Coming up" card and pinned chips (D4). Today is Thursday 29 October 2026. */
class HomeCalendarTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 29)
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    @Test fun `due and overdue to-dos first, then at most three entries in the next week`() {
        val entries = listOf(
            calendarEntry(id = "late", kind = CalendarKind.TODO, date = d(10, 27)),
            calendarEntry(id = "due", kind = CalendarKind.TODO, date = today),
            calendarEntry(id = "doneDue", kind = CalendarKind.TODO, date = today, done = true),
            calendarEntry(id = "a", date = today, time = LocalTime.of(20, 0)),
            calendarEntry(id = "b", date = d(10, 31)),
            calendarEntry(id = "weekly", date = d(10, 1), repeat = CalendarRepeat.WEEKLY),
            calendarEntry(id = "c", date = d(11, 3)),
            calendarEntry(id = "d", date = d(11, 4)), // the 7th day: outside "the next 7 days"
            calendarEntry(id = "laterTodo", kind = CalendarKind.TODO, date = d(11, 2)),
        )
        val items = HomeCalendar.comingUp(entries, today, zone)
        // All-day before timed on the same day; "c" is the fourth entry of the week.
        assertEquals(listOf("late", "due", "weekly", "a", "b"), items.map { it.entry.id })
        assertTrue(items.first().overdue)
    }

    @Test fun `nothing coming up means no card`() {
        assertTrue(HomeCalendar.comingUp(listOf(calendarEntry(date = d(12, 1))), today, zone).isEmpty())
    }

    @Test fun `pinned entries count down on Home`() {
        val entries = listOf(
            calendarEntry(id = "goa", title = "Goa trip", emoji = "🏖️", date = d(11, 10), pinned = true),
            calendarEntry(id = "dinner", title = "Dinner", date = d(10, 30), pinned = true),
            calendarEntry(id = "past", title = "Old", date = d(10, 1), pinned = true),
            calendarEntry(id = "plain", title = "Not pinned", date = d(11, 1)),
        )
        assertEquals(listOf("📌 Tomorrow · Dinner", "🏖️ 12 days · Goa trip"), HomeCalendar.pinned(entries, today, zone).map { it.text })
    }
}
