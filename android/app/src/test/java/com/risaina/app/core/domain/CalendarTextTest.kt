package com.risaina.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class CalendarTextTest {
    private val uk = Locale.UK
    private val fri = LocalDate.of(2026, 11, 14).minusDays(1)

    @Test fun `days and times read the same everywhere`() {
        assertEquals("Fri 13 Nov", CalendarText.day(fri, uk))
        assertEquals("Fri 13 Nov, 8:00 pm", CalendarText.dayTime(fri, LocalTime.of(20, 0), uk))
        assertEquals("Fri 13 Nov", CalendarText.dayTime(fri, null, uk))
        assertEquals("Fri 13 Nov", CalendarText.day(fri, 2026, uk))
        assertEquals("Sat 2 Jan 2027", CalendarText.day(LocalDate.of(2027, 1, 2), 2026, uk))
        assertEquals("12:30 am", CalendarText.time(LocalTime.of(0, 30), uk))
    }

    @Test fun `countdowns`() {
        val today = LocalDate.of(2026, 10, 29)
        assertEquals("Today", CalendarText.countdown(today, today))
        assertEquals("Tomorrow", CalendarText.countdown(today.plusDays(1), today))
        assertEquals("In 12 days", CalendarText.countdown(today.plusDays(12), today))
        assertEquals("Yesterday", CalendarText.countdown(today.minusDays(1), today))
        assertEquals("3 days ago", CalendarText.countdown(today.minusDays(3), today))
    }

    @Test fun `reminder offsets`() {
        assertEquals("On the day", CalendarText.offset(0, allDay = true))
        assertEquals("At the time", CalendarText.offset(0, allDay = false))
        assertEquals("10 min before", CalendarText.offset(10, allDay = false))
        assertEquals("1 hour before", CalendarText.offset(60, allDay = false))
        assertEquals("1 day before", CalendarText.offset(1440, allDay = true))
        assertEquals("3 days before", CalendarText.offset(4320, allDay = true))
        assertEquals("1 week before", CalendarText.offset(10080, allDay = false))
        assertEquals("90 min before", CalendarText.offset(90, allDay = false))
    }
}
