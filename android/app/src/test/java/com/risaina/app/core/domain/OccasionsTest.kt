package com.risaina.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

class OccasionsTest {
    private val today = LocalDate.of(2026, 10, 25)

    @Test fun `birthday wire format round trips with and without year`() {
        assertEquals(Birthday(MonthDay.of(3, 14), 1998), Birthday.parse("1998-03-14"))
        assertEquals(Birthday(MonthDay.of(3, 14), null), Birthday.parse("--03-14"))
        assertEquals("--03-14", Birthday(MonthDay.of(3, 14), null).wire)
        assertEquals("1998-03-14", Birthday(MonthDay.of(3, 14), 1998).wire)
        assertNull(Birthday.parse("not a date"))
    }

    @Test fun `next birthday later this year, next year, and today`() {
        assertEquals(LocalDate.of(2026, 12, 1), Occasions.nextBirthday(Birthday(MonthDay.of(12, 1), 1999), today).date)
        val next = Occasions.nextBirthday(Birthday(MonthDay.of(3, 14), 1998), today)
        assertEquals(LocalDate.of(2027, 3, 14), next.date); assertEquals(140, next.daysLeft); assertEquals(29, next.turning)
        assertEquals(0, Occasions.nextBirthday(Birthday(MonthDay.of(10, 25), null), today).daysLeft)
    }

    @Test fun `leap day birthday falls on Feb 28 in non leap years`() {
        assertEquals(LocalDate.of(2027, 2, 28), Occasions.nextBirthday(Birthday(MonthDay.of(2, 29), 2000), today).date)
    }

    @Test fun `anniversary counts days together and days to the next one`() {
        val a = Occasions.anniversary(LocalDate.of(2024, 7, 14), today)
        assertEquals(833, a.daysTogether)
        assertEquals("2 years, 3 months, 11 days", a.together)
        assertEquals(LocalDate.of(2027, 7, 14), a.nextDate)
        assertEquals(262, a.daysLeft)
        assertEquals(3, a.yearsAtNext)
    }

    @Test fun `anniversary today counts as today not next year`() {
        val a = Occasions.anniversary(LocalDate.of(2023, 10, 25), today)
        assertEquals(0, a.daysLeft); assertEquals(3, a.yearsAtNext)
    }

    @Test fun `partner clock shows their time and day difference`() {
        val now = Instant.parse("2026-10-25T20:30:00Z")
        val (time, note) = Occasions.partnerClock("Asia/Kolkata", now, ZoneId.of("Europe/London"))!!
        assertEquals("2:00 am", time); assertEquals("tomorrow for them", note)
        assertNull(Occasions.partnerClock(null, now))
        assertNull(Occasions.partnerClock("Not/AZone", now))
    }

    @Test fun `ordinals`() {
        assertEquals("1st", Occasions.ordinal(1)); assertEquals("2nd", Occasions.ordinal(2)); assertEquals("3rd", Occasions.ordinal(3))
        assertEquals("11th", Occasions.ordinal(11)); assertEquals("22nd", Occasions.ordinal(22)); assertEquals("4th", Occasions.ordinal(4))
    }
}
