package com.risaina.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The date parser (message-actions.md §4). Reference: Thursday 2026-10-29, 10:00 in Kolkata, day
 * first unless a test says otherwise. Checked with `date`: 30 Oct is a Friday, 31 Oct a Saturday,
 * 1 Nov a Sunday, 2 Nov a Monday, 6 Nov a Friday.
 */
class DateDetectorTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val ref = ZonedDateTime.of(2026, 10, 29, 10, 0, 0, 0, kolkata)

    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
    private fun t(h: Int, min: Int = 0) = LocalTime.of(h, min)
    private fun find(text: String, order: DateOrder = DateOrder.DMY, at: ZonedDateTime = ref) = DateDetector.find(text, at, order)
    private fun one(text: String, order: DateOrder = DateOrder.DMY, at: ZonedDateTime = ref): DateHit {
        val hits = find(text, order, at)
        assertEquals("one hit in '$text': $hits", 1, hits.size)
        return hits.single()
    }

    private fun assertHit(text: String, date: LocalDate, time: LocalTime? = null, order: DateOrder = DateOrder.DMY) {
        val h = one(text, order)
        assertEquals("date of '$text'", date, h.date)
        assertEquals("time of '$text'", time, h.time)
    }

    // ---- relative days ----

    @Test fun `relative days with and without times`() {
        val table = listOf(
            "tomorrow 7pm" to (d(10, 30) to t(19)),
            "TOMORROW 7 P.M." to (d(10, 30) to t(19)),
            "tmrw 7pm" to (d(10, 30) to t(19)),
            "tmr 7pm" to (d(10, 30) to t(19)),
            "tommorow 7pm" to (d(10, 30) to t(19)),
            "2moro 7pm" to (d(10, 30) to t(19)),
            "today at 5pm" to (d(10, 29) to t(17)),
            "tonight at 9" to (d(10, 29) to t(21)),
            "tonite 9:30" to (d(10, 29) to t(21, 30)),
            "tomorrow morning at 8" to (d(10, 30) to t(8)),
            "tomorrow evening at 8" to (d(10, 30) to t(20)),
            "noon tomorrow" to (d(10, 30) to t(12)),
            "7pm tomorrow" to (d(10, 30) to t(19)),
            "tomorrow 9:30" to (d(10, 30) to t(9, 30)),
            "today" to (d(10, 29) to null),
            "tonight" to (d(10, 29) to null),
        )
        table.forEach { (text, want) -> assertHit(text, want.first, want.second) }
        assertTrue(one("tomorrow 7pm").confident)
        assertTrue(one("tonight at 9").confident)
    }

    @Test fun `day after tomorrow is one hit, with no tomorrow inside it`() {
        val h = one("day after tomorrow")
        assertEquals(d(10, 31), h.date)
        assertEquals(0..17, h.range)
        assertEquals(d(10, 31), one("the day after tomorrow").date)
        assertEquals(d(10, 31), one("day after tmrw at 6pm").date)
    }

    // ---- weekdays ----

    @Test fun `weekdays, this and next`() {
        assertHit("Sat", d(10, 31))
        assertHit("see you Sat 8pm", d(10, 31), t(20))
        assertHit("on sat", d(10, 31))
        assertHit("this Sat", d(10, 31))
        assertHit("Sun 5pm", d(11, 1), t(17))
        assertHit("saturday", d(10, 31))
        assertHit("Tues.", d(11, 3))
        assertHit("before fri", d(10, 30))
        assertHit("sat 8pm", d(10, 31), t(20))
    }

    @Test fun `next weekday is the following week's, with the coming one as the other reading`() {
        val fri = one("next Friday")
        assertEquals(d(11, 6), fri.date)
        assertEquals(listOf(d(10, 30) to null), fri.alternatives)
        val mon = one("next Monday")
        assertEquals(d(11, 2), mon.date)
        assertTrue(mon.alternatives.isEmpty())
        assertEquals(0..10, mon.range)
        assertEquals(d(10, 30), one("this coming friday").date)
    }

    @Test fun `today's weekday means next week, with today as the other reading`() {
        val h = one("Thursday")
        assertEquals(d(11, 5), h.date)
        assertEquals(listOf(d(10, 29) to null), h.alternatives)
        assertEquals(d(10, 29), one("this Thursday").date)
    }

    @Test fun `short weekdays need a capital, a lead-in or a time after them`() {
        for (text in listOf("I sat down", "the sun is out", "we wed in spring", "fri-yay", "mon ami")) {
            assertTrue("nothing in '$text'", find(text).isEmpty())
        }
        assertEquals(d(10, 31), one("sat at 8pm").date)
        assertEquals(d(10, 30), one("by fri").date)
        assertEquals(d(11, 4), one("Wed").date)
    }

    // ---- month names ----

    @Test fun `day and month names`() {
        assertHit("25 Dec", d(12, 25))
        assertHit("25th of December", d(12, 25))
        assertHit("Dec 25th", d(12, 25))
        assertHit("Dec 25", d(12, 25))
        assertHit("December 25th, 2027", d(12, 25, 2027))
        assertHit("25 December 2027", d(12, 25, 2027))
        assertHit("2nd sept", d(9, 2, 2027))
        assertHit("1 Oct", d(10, 1, 2027))
        assertHit("29 Oct", d(10, 29))
        assertHit("29 Feb", d(2, 29, 2028))
        assertHit("25 Dec at 8pm", d(12, 25), t(20))
        assertTrue(one("25 Dec").confident)
    }

    @Test fun `a month name alone is never a date`() {
        for (text in listOf("I may come", "march on", "the jan files", "decide later", "31 Feb", "30 february 2027")) {
            assertTrue("nothing in '$text'", find(text).isEmpty())
        }
    }

    // ---- numeric dates ----

    @Test fun `numeric dates by the locale's order`() {
        assertHit("25/12", d(12, 25))
        assertHit("12/25", d(12, 25)) // swapped: only one reading is a real date
        assertHit("2026-12-25", d(12, 25))
        assertHit("25.12.2026", d(12, 25))
        assertHit("25-12-26", d(12, 25))
        assertHit("12/25/2026", d(12, 25), order = DateOrder.MDY)
        assertHit("13/12", d(12, 13), order = DateOrder.MDY)
        assertTrue(find("31/02").isEmpty())
        assertTrue(one("2026-12-25").confident)
        assertTrue(one("25.12.2026").confident)
    }

    @Test fun `an ambiguous day and month is not confident and offers the other reading`() {
        val dmy = one("3/4")
        assertEquals(d(4, 3, 2027), dmy.date)
        assertFalse(dmy.confident)
        assertEquals(listOf(d(3, 4, 2027) to null), dmy.alternatives)
        val mdy = one("3/4", DateOrder.MDY)
        assertEquals(d(3, 4, 2027), mdy.date)
        assertEquals(listOf(d(4, 3, 2027) to null), mdy.alternatives)
        assertFalse(one("25/12").confident)
    }

    @Test fun `the locale decides the order`() {
        assertEquals(DateOrder.DMY, DateOrder.forLocale(Locale.forLanguageTag("en-IN")))
        assertEquals(DateOrder.DMY, DateOrder.forLocale(Locale.forLanguageTag("en-GB")))
        assertEquals(DateOrder.MDY, DateOrder.forLocale(Locale.forLanguageTag("en-US")))
    }

    // ---- times ----

    @Test fun `a time with no date is today if it is still to come, else tomorrow`() {
        val seven = one("7pm")
        assertEquals(d(10, 29), seven.date)
        assertEquals(t(19), seven.time)
        assertTrue(seven.dateGuessed)
        assertTrue(seven.confident)
        assertHit("9am", d(10, 30), t(9))
        assertHit("19:30", d(10, 29), t(19, 30))
        assertHit("7:30pm", d(10, 29), t(19, 30))
        assertHit("7.30 pm", d(10, 29), t(19, 30))
        assertHit("7 a.m.", d(10, 30), t(7))
        assertHit("12pm", d(10, 29), t(12))
        assertHit("12am", d(10, 30), t(0))
        assertHit("noon", d(10, 29), t(12))
        assertHit("midday", d(10, 29), t(12))
        assertHit("midnight", d(10, 30), t(0))
        assertHit("8 o'clock", d(10, 29), t(20))
    }

    @Test fun `a bare at H reads as evening, with the morning as the other reading`() {
        val h = one("at 8")
        assertEquals(d(10, 29), h.date)
        assertEquals(t(20), h.time)
        assertFalse(h.confident)
        assertEquals(listOf(d(10, 30) to t(8)), h.alternatives)
        assertHit("@8", d(10, 29), t(20))
        val fifteen = one("at 15")
        assertEquals(t(15), fifteen.time)
        assertFalse(fifteen.confident)
        assertEquals(t(12), one("at 12").time)
        // A daypart decides, and then there is nothing to doubt.
        val morning = one("at 8 in the morning")
        assertEquals(t(8), morning.time)
        assertTrue(morning.confident)
        assertEquals(t(15), one("this afternoon at 3").time)
    }

    @Test fun `time ranges`() {
        val sat = one("Sat 7-9pm")
        assertEquals(d(10, 31), sat.date)
        assertEquals(t(19), sat.time)
        assertEquals(t(21), sat.endTime)
        val morning = one("10am-1pm")
        assertEquals(t(10), morning.time)
        assertEquals(t(13), morning.endTime)
        val late = one("11pm to 1am")
        assertEquals(t(23), late.time)
        assertEquals(t(1), late.endTime)
        assertEquals(t(11), one("11-1pm").time)
        assertEquals(t(21), one("19:00-21:00").endTime)
        assertEquals(t(19), one("from 7 to 9pm tomorrow").time)
    }

    // ---- relative spans ----

    @Test fun `in N days, weeks, hours and minutes`() {
        assertHit("in 3 days", d(11, 1))
        assertHit("in 2 weeks", d(11, 12))
        assertHit("in a week", d(11, 5))
        assertHit("in 2 hours", d(10, 29), t(12))
        assertHit("in an hour", d(10, 29), t(11))
        assertHit("in 90 minutes", d(10, 29), t(11, 30))
        assertHit("in three days", d(11, 1))
        assertHit("in 16 hours", d(10, 30), t(2))
    }

    // ---- what is not a date ----

    @Test fun `ordinary text finds nothing`() {
        val nothing = listOf(
            "I sat down", "the sun is out", "I may come", "march on", "we are 3 of us", "v1.2.3", "score 2-1", "₹500",
            "call 98765 43210", "tom said hi", "kal 7 baje", "7.30", "2-1", "3-4 people", "1/2 cup", "3/4 of us", "open 24/7",
            "7-9", "it costs 7.50", "version 2.0.1", "back at 99%", "room 12", "5 amazing things", "that 8 was close",
            "",
        )
        for (text in nothing) assertTrue("nothing in '$text': ${find(text)}", find(text).isEmpty())
    }

    // ---- reference and zones ----

    @Test fun `tomorrow is relative to when the message was written`() {
        val tuesdayNight = ZonedDateTime.of(2026, 10, 27, 21, 0, 0, 0, kolkata)
        val h = one("tomorrow 7pm", at = tuesdayNight)
        assertEquals(d(10, 28), h.date)
        assertEquals(t(19), h.time)
    }

    @Test fun `the same instant is a different day in another zone`() {
        val instant = Instant.parse("2026-10-29T20:00:00Z")
        assertEquals(d(10, 31), one("tomorrow", at = instant.atZone(kolkata)).date)
        assertEquals(d(10, 30), one("tomorrow", at = instant.atZone(ZoneId.of("America/New_York"))).date)
    }

    // ---- several hits, ranges ----

    @Test fun `several hits come back in text order with ranges into the original text`() {
        val hits = find("Sat or Sun?")
        assertEquals(listOf(d(10, 31), d(11, 1)), hits.map { it.date })
        assertEquals(listOf(0..2, 7..9), hits.map { it.range })
        // A character that lowercases to two must not shift the ranges.
        val text = "İstanbul trip 25 Dec"
        val h = one(text)
        assertEquals("25 Dec", text.substring(h.range))
    }

    @Test fun `a date and a time join only across small words`() {
        assertEquals(1, find("Sat, 8pm").size)
        assertEquals(1, find("25 Dec @ 7pm").size)
        assertEquals(1, find("on Friday at 9am").size)
        val apart = find("Sat we said maybe 8pm")
        assertEquals(2, apart.size)
        assertNull(apart.first().time)
    }

    // ---- titles ----

    @Test fun `a title is what is left around the date`() {
        fun title(text: String) = DateDetector.suggestTitle(text, find(text))
        assertEquals("Dinner at Olive", title("Dinner at Olive tomorrow 7pm?"))
        assertEquals("Call", title("Let's call Sat 9pm"))
        assertEquals("Book tickets", title("remind me to book tickets by 25 Dec"))
        assertEquals("", title("Sat"))
        assertEquals("Movie night then dinner", title("Movie night on Fri, then dinner"))
        assertEquals("Dinner", title("Dinner at 8 tomorrow"))
        assertEquals("Pick up the cake", title("don't forget to pick up the cake tomorrow"))
        assertEquals("Flight to Goa", title("Packing list\nFlight to Goa on 14 Nov 6am\nSunscreen"))
        assertEquals("Groceries", title("groceries"))
        assertEquals(120, title("a".repeat(300) + " tomorrow").length)
    }
}
