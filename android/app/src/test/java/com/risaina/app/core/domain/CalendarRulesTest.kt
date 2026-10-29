package com.risaina.app.core.domain

import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.testutil.calendarEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Where entries fall: repeats, anchored and floating times, other zones (calendar.md §9-10). */
class CalendarRulesTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val london = ZoneId.of("Europe/London")
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test fun `weekly repeats`() {
        val e = calendarEntry(date = d(2026, 11, 1), repeat = CalendarRepeat.WEEKLY)
        assertEquals(listOf(d(2026, 11, 8), d(2026, 11, 15), d(2026, 11, 22)), CalendarRules.occurrences(e, d(2026, 11, 2), d(2026, 11, 28)))
    }

    @Test fun `monthly on the 31st never drifts`() {
        val e = calendarEntry(date = d(2026, 1, 31), repeat = CalendarRepeat.MONTHLY)
        assertEquals(
            listOf(d(2026, 1, 31), d(2026, 2, 28), d(2026, 3, 31), d(2026, 4, 30), d(2026, 5, 31)),
            CalendarRules.occurrences(e, d(2026, 1, 1), d(2026, 5, 31)),
        )
        assertEquals(listOf(d(2028, 2, 29)), CalendarRules.occurrences(e, d(2028, 2, 1), d(2028, 2, 29)))
    }

    @Test fun `yearly from 29 February`() {
        val e = calendarEntry(date = d(2024, 2, 29), repeat = CalendarRepeat.YEARLY)
        assertEquals(
            listOf(d(2024, 2, 29), d(2025, 2, 28), d(2026, 2, 28), d(2027, 2, 28), d(2028, 2, 29)),
            CalendarRules.occurrences(e, d(2024, 1, 1), d(2028, 12, 31)),
        )
    }

    @Test fun `repeatUntil is included, and to-dos and unknown repeats happen once`() {
        val weekly = calendarEntry(date = d(2026, 11, 1), repeat = CalendarRepeat.WEEKLY, repeatUntil = d(2026, 11, 15))
        assertEquals(listOf(d(2026, 11, 1), d(2026, 11, 8), d(2026, 11, 15)), CalendarRules.occurrences(weekly, d(2026, 10, 1), d(2026, 12, 31)))
        val todo = calendarEntry(date = d(2026, 11, 1), kind = CalendarKind.TODO, repeat = CalendarRepeat.WEEKLY)
        assertEquals(listOf(d(2026, 11, 1)), CalendarRules.occurrences(todo, d(2026, 10, 1), d(2026, 12, 31)))
        val unknown = calendarEntry(date = d(2026, 11, 1), repeat = CalendarRepeat.UNKNOWN)
        assertEquals(listOf(d(2026, 11, 1)), CalendarRules.occurrences(unknown, d(2026, 10, 1), d(2026, 12, 31)))
        assertTrue(CalendarRules.occurrences(unknown, d(2026, 11, 2), d(2026, 12, 31)).isEmpty())
    }

    @Test fun `an anchored London evening keeps its London time across the clock change and moves in Kolkata`() {
        val e = calendarEntry(date = d(2026, 3, 22), time = LocalTime.of(21, 0), timeZone = "Europe/London", repeat = CalendarRepeat.WEEKLY)
        // Before 29 March London is on GMT, after it on BST.
        assertEquals(Instant.parse("2026-03-22T21:00:00Z"), CalendarRules.start(e, d(2026, 3, 22), kolkata))
        assertEquals(Instant.parse("2026-03-29T20:00:00Z"), CalendarRules.start(e, d(2026, 3, 29), kolkata))
        assertEquals(LocalDateTime.of(2026, 3, 23, 2, 30), CalendarRules.localStart(e, d(2026, 3, 22), kolkata))
        assertEquals(LocalDateTime.of(2026, 3, 30, 1, 30), CalendarRules.localStart(e, d(2026, 3, 29), kolkata))
        assertEquals(LocalDateTime.of(2026, 3, 29, 21, 0), CalendarRules.localStart(e, d(2026, 3, 29), london))
    }

    @Test fun `a floating time is the same time on both phones`() {
        val e = calendarEntry(time = LocalTime.of(9, 0))
        assertEquals(LocalDateTime.of(2026, 11, 14, 9, 0), CalendarRules.localStart(e, e.date, kolkata))
        assertEquals(LocalDateTime.of(2026, 11, 14, 9, 0), CalendarRules.localStart(e, e.date, ZoneId.of("America/New_York")))
        assertNull(CalendarRules.partnerStart(e, e.date, kolkata, london))
    }

    @Test fun `across the date line the same event is on different days`() {
        val e = calendarEntry(time = LocalTime.of(9, 0), timeZone = "Pacific/Auckland")
        assertEquals(LocalDateTime.of(2026, 11, 14, 9, 0), CalendarRules.localStart(e, e.date, ZoneId.of("Pacific/Auckland")))
        assertEquals(LocalDateTime.of(2026, 11, 13, 12, 0), CalendarRules.localStart(e, e.date, ZoneId.of("America/Los_Angeles")))
    }

    @Test fun `the partner's clock is shown only when it tells a different time`() {
        val e = calendarEntry(time = LocalTime.of(20, 0), timeZone = "Asia/Kolkata")
        assertEquals(LocalDateTime.of(2026, 11, 14, 14, 30), CalendarRules.partnerStart(e, e.date, kolkata, london))
        assertNull(CalendarRules.partnerStart(e, e.date, kolkata, kolkata))
        assertNull(CalendarRules.partnerStart(e, e.date, kolkata, null))
        assertNull(CalendarRules.partnerStart(calendarEntry(), d(2026, 11, 14), kolkata, london))
        // One clock under two names, or two zones that tell the same time: no second line.
        assertNull(CalendarRules.partnerStart(e, e.date, kolkata, ZoneId.of("Asia/Calcutta")))
        assertNull(CalendarRules.partnerStart(calendarEntry(time = LocalTime.of(20, 0), timeZone = "Europe/London"), e.date, london, ZoneId.of("Europe/Lisbon")))
    }

    @Test fun `zones that agree part of the year show the partner's clock only where they differ`() {
        val phoenix = ZoneId.of("America/Phoenix")
        val la = ZoneId.of("America/Los_Angeles")
        val weekly = calendarEntry(date = d(2026, 10, 24), time = LocalTime.of(19, 0), timeZone = "America/Phoenix", repeat = CalendarRepeat.WEEKLY)
        // Los Angeles keeps summer time until 1 Nov: one clock with Phoenix until then, an hour behind after.
        assertNull(CalendarRules.partnerStart(weekly, d(2026, 10, 31), phoenix, la))
        assertEquals(LocalDateTime.of(2026, 11, 7, 18, 0), CalendarRules.partnerStart(weekly, d(2026, 11, 7), phoenix, la))
    }

    @Test fun `the agenda places an anchored entry on this phone's day`() {
        val sundayLondon = calendarEntry(date = d(2026, 11, 1), time = LocalTime.of(21, 0), timeZone = "Europe/London")
        val agenda = CalendarRules.agenda(listOf(sundayLondon), d(2026, 11, 2), d(2026, 11, 2), kolkata)
        assertEquals(1, agenda.size)
        assertEquals(d(2026, 11, 1), agenda.single().date)
        assertEquals(LocalDateTime.of(2026, 11, 2, 2, 30), agenda.single().localStart)
        assertTrue(CalendarRules.agenda(listOf(sundayLondon), d(2026, 11, 1), d(2026, 11, 1), kolkata).isEmpty())
    }

    @Test fun `a trip covers every day it spans, and all-day comes before timed`() {
        val trip = calendarEntry(id = "trip", date = d(2026, 11, 13), endDate = d(2026, 11, 15))
        val call = calendarEntry(id = "call", date = d(2026, 11, 14), time = LocalTime.of(8, 0))
        val agenda = CalendarRules.agenda(listOf(call, trip), d(2026, 11, 14), d(2026, 11, 14), kolkata)
        assertEquals(listOf("trip", "call"), agenda.map { it.entry.id })
        assertTrue(CalendarRules.covers(agenda.first(), d(2026, 11, 15)))
        assertFalse(CalendarRules.covers(agenda.first(), d(2026, 11, 16)))
        assertEquals(2L, CalendarRules.spanDays(trip))
    }

    @Test fun `overdue to-dos`() {
        val today = d(2026, 10, 29)
        assertTrue(CalendarRules.overdue(calendarEntry(kind = CalendarKind.TODO, date = d(2026, 10, 28)), today))
        assertFalse(CalendarRules.overdue(calendarEntry(kind = CalendarKind.TODO, date = d(2026, 10, 28), done = true), today))
        assertFalse(CalendarRules.overdue(calendarEntry(kind = CalendarKind.TODO, date = today), today))
        assertFalse(CalendarRules.overdue(calendarEntry(date = d(2026, 10, 28)), today))
    }
}
