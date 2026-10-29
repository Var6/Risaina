package com.risaina.app.feature.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** What the phone's calendar app is handed for "Also add to my phone's calendar". */
class CalendarInsertTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test fun `a timed plan lasts an hour unless it has a length`() {
        val x = CalendarInsert.extras("Dinner", LocalDate.of(2026, 10, 31), null, LocalTime.of(20, 0), null, kolkata, "Bea Rao")
        assertEquals(Instant.parse("2026-10-31T14:30:00Z").toEpochMilli(), x.beginMillis)
        assertEquals(x.beginMillis + 3_600_000, x.endMillis)
        assertFalse(x.allDay)
        assertEquals("Dinner", x.title)
        assertEquals("Planned with Bea in Risaina", x.description)
        val long = CalendarInsert.extras("Movie", LocalDate.of(2026, 10, 31), null, LocalTime.of(23, 0), 150, kolkata, null)
        assertEquals(long.beginMillis + 150 * 60_000, long.endMillis)
        assertEquals("Planned with your partner in Risaina", long.description)
    }

    @Test fun `an all-day plan runs from UTC midnight to the midnight after its last day`() {
        val x = CalendarInsert.extras("Christmas", LocalDate.of(2026, 12, 25), null, null, null, kolkata, "Bea")
        assertTrue(x.allDay)
        assertEquals(Instant.parse("2026-12-25T00:00:00Z").toEpochMilli(), x.beginMillis)
        assertEquals(Instant.parse("2026-12-26T00:00:00Z").toEpochMilli(), x.endMillis)
        val trip = CalendarInsert.extras("Goa", LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 15), null, null, kolkata, "Bea")
        assertEquals(Instant.parse("2026-11-16T00:00:00Z").toEpochMilli(), trip.endMillis)
    }
}
