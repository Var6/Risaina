package com.risaina.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** One clock or two: aliases, zones that agree all year, part of the year, or never. */
class TimeZonesTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val calcutta = ZoneId.of("Asia/Calcutta")
    private val london = ZoneId.of("Europe/London")
    private val lisbon = ZoneId.of("Europe/Lisbon")
    private val newYork = ZoneId.of("America/New_York")
    private val toronto = ZoneId.of("America/Toronto")
    private val phoenix = ZoneId.of("America/Phoenix")
    private val denver = ZoneId.of("America/Denver")
    private val la = ZoneId.of("America/Los_Angeles")
    private val now = Instant.parse("2026-10-29T06:00:00Z")

    @Test fun `zones are read leniently`() {
        assertEquals(kolkata, TimeZones.zoneOf("Asia/Kolkata"))
        assertNull(TimeZones.zoneOf(null))
        assertNull(TimeZones.zoneOf(""))
        assertNull(TimeZones.zoneOf("Not/AZone"))
        assertEquals("New York", TimeZones.city(newYork))
    }

    @Test fun `a partner whose zone is not shared counts as one clock`() {
        assertTrue(TimeZones.sameClock(kolkata, null, now))
        assertTrue(TimeZones.sameClockAllYear(kolkata, null, now))
        assertTrue(TimeZones.sameClockAllYear(kolkata, TimeZones.zoneOf("Not/AZone"), now))
    }

    @Test fun `one zone and its other names are one clock`() {
        assertTrue(TimeZones.sameClockAllYear(kolkata, kolkata, now))
        assertTrue(TimeZones.sameClockAllYear(kolkata, calcutta, now))
        assertTrue(TimeZones.sameClockAllYear(ZoneId.of("UTC"), ZoneId.of("Etc/UTC"), now))
        assertTrue(TimeZones.sameClockAllYear(ZoneOffset.UTC, ZoneId.of("Z"), now))
    }

    @Test fun `india and london are two clocks, every day of the year`() {
        assertFalse(TimeZones.sameClock(kolkata, london, now))
        assertFalse(TimeZones.sameClock(kolkata, london, Instant.parse("2026-07-01T12:00:00Z")))
        assertFalse(TimeZones.sameClockAllYear(kolkata, london, now))
    }

    @Test fun `zones with other names but the same clock changes are one clock`() {
        // Lisbon and London have different histories (their rules differ) but change together today:
        // both leave summer time at 01:00 UTC on 25 Oct.
        assertTrue(TimeZones.sameClockAllYear(london, lisbon, now))
        assertTrue(TimeZones.sameClock(london, lisbon, Instant.parse("2026-10-25T00:00:00Z"), Instant.parse("2026-10-26T00:00:00Z")))
        assertTrue(TimeZones.sameClockAllYear(newYork, toronto, now))
        assertTrue(TimeZones.sameClock(newYork, toronto, Instant.parse("2026-11-01T04:00:00Z"), Instant.parse("2026-11-02T05:00:00Z")))
    }

    @Test fun `zones that part ways for some months are one clock only where they agree`() {
        // Denver leaves summer time on Sun 1 Nov 2026 at 08:00 UTC; Phoenix never keeps it.
        assertFalse(TimeZones.sameClock(phoenix, denver, Instant.parse("2026-11-01T07:59:59Z")))
        assertTrue(TimeZones.sameClock(phoenix, denver, Instant.parse("2026-11-01T08:00:00Z")))
        // A span that starts before the change is two clocks.
        assertFalse(TimeZones.sameClock(phoenix, denver, Instant.parse("2026-11-01T07:00:00Z"), Instant.parse("2026-11-02T07:00:00Z")))
        // Summer time again from 09:00 UTC on Sun 14 Mar 2027.
        assertTrue(TimeZones.sameClock(phoenix, denver, Instant.parse("2026-11-01T08:00:00Z"), Instant.parse("2027-03-14T08:59:59Z")))
        assertFalse(TimeZones.sameClock(phoenix, denver, Instant.parse("2026-11-01T08:00:00Z"), Instant.parse("2027-03-14T09:00:00Z")))
        // A screen asks about the whole year ahead: not one clock.
        assertFalse(TimeZones.sameClockAllYear(phoenix, denver, Instant.parse("2026-12-01T00:00:00Z")))
    }

    @Test fun `one clock in summer, two in winter`() {
        // Los Angeles leaves summer time at 09:00 UTC on 1 Nov.
        val july = Instant.parse("2026-07-01T07:00:00Z")
        assertTrue(TimeZones.sameClock(phoenix, la, july))
        assertTrue(TimeZones.sameClock(phoenix, la, july, Instant.parse("2026-11-01T08:59:59Z")))
        assertFalse(TimeZones.sameClock(phoenix, la, july, Instant.parse("2026-11-01T09:00:00Z")))
        assertFalse(TimeZones.sameClockAllYear(phoenix, la, july))
    }
}
