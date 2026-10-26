package com.risaina.app.core.domain

import com.risaina.app.core.domain.LiveLocationRules.Freshness
import com.risaina.app.core.domain.LiveLocationRules.Mode
import com.risaina.app.core.domain.LiveLocationRules.Sample
import com.risaina.app.core.domain.LiveLocationRules.Uploaded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale
import kotlin.random.Random

class LiveLocationRulesTest {
    // Bengaluru; 0.0001 degree of latitude is about 11 m
    private val lat = 12.97160
    private val lng = 77.59460
    private fun uploaded(atMs: Long, accuracy: Double = 20.0) = Uploaded(lat, lng, accuracy, atMs)

    @Test fun `the first fix always uploads`() {
        assertTrue(LiveLocationRules.shouldUpload(null, Sample(lat, lng, 5000.0), Mode.MANUAL, 0))
    }

    @Test fun `manual uploads on real movement, scaled by accuracy`() {
        val last = uploaded(0)
        assertFalse(LiveLocationRules.shouldUpload(last, Sample(lat + 0.0001, lng, 20.0), Mode.MANUAL, 60_000)) // ~11 m
        assertTrue(LiveLocationRules.shouldUpload(last, Sample(lat + 0.0003, lng, 20.0), Mode.MANUAL, 60_000)) // ~33 m
        // a 100 m-accurate fix needs 50 m of movement before it counts
        assertFalse(LiveLocationRules.shouldUpload(last, Sample(lat + 0.0003, lng, 100.0), Mode.MANUAL, 60_000))
        assertTrue(LiveLocationRules.shouldUpload(last, Sample(lat + 0.0006, lng, 100.0), Mode.MANUAL, 60_000))
    }

    @Test fun `a much better accuracy uploads, a small gain does not`() {
        val last = uploaded(0, accuracy = 400.0)
        assertTrue(LiveLocationRules.shouldUpload(last, Sample(lat, lng, 30.0), Mode.MANUAL, 1_000))
        assertFalse(LiveLocationRules.shouldUpload(last, Sample(lat, lng, 300.0), Mode.MANUAL, 1_000))
        // already precise: no "improvement" upload
        assertFalse(LiveLocationRules.shouldUpload(uploaded(0, 40.0), Sample(lat, lng, 10.0), Mode.MANUAL, 1_000))
    }

    @Test fun `heartbeat after five minutes still, ten seconds in panic`() {
        val last = uploaded(0)
        val still = Sample(lat, lng, 20.0)
        assertFalse(LiveLocationRules.shouldUpload(last, still, Mode.MANUAL, 299_000))
        assertTrue(LiveLocationRules.shouldUpload(last, still, Mode.MANUAL, 300_000))
        assertFalse(LiveLocationRules.shouldUpload(last, still, Mode.PANIC, 5_000))
        assertTrue(LiveLocationRules.shouldUpload(last, still, Mode.PANIC, 10_000))
    }

    @Test fun `distance is sane`() {
        val d = LiveLocationRules.distanceMeters(12.0, 77.0, 13.0, 77.0)
        assertTrue(d in 110_000.0..112_500.0)
        assertEquals(0.0, LiveLocationRules.distanceMeters(lat, lng, lat, lng), 0.001)
    }

    @Test fun `fix age uses the monotonic clock and is clamped to what the server takes`() {
        assertEquals(1_500L, LiveLocationRules.ageMs(10_000_000_000L, 8_500_000_000L))
        assertEquals(0L, LiveLocationRules.ageMs(1_000L, 9_000_000L)) // a fix "from the future"
        assertEquals(LiveLocationRules.MAX_FIX_AGE_MS, LiveLocationRules.ageMs(3_600_000_000_000L, 0L))
    }

    @Test fun `freshness follows the last check-in, not the fix time`() {
        val now = Instant.parse("2026-10-26T12:00:00Z")
        assertEquals(Freshness.WAITING, LiveLocationRules.freshness(null, hasFix = false, panic = false, now = now))
        assertEquals(Freshness.WAITING, LiveLocationRules.freshness(now.minusSeconds(30), hasFix = false, panic = false, now = now))
        assertEquals(Freshness.LIVE, LiveLocationRules.freshness(now.minusSeconds(5 * 60), hasFix = true, panic = false, now = now))
        assertEquals(Freshness.STALE, LiveLocationRules.freshness(now.minusSeconds(10 * 60), hasFix = true, panic = false, now = now))
        assertEquals(Freshness.OLD, LiveLocationRules.freshness(now.minusSeconds(40 * 60), hasFix = true, panic = false, now = now))
        assertEquals(Freshness.LIVE, LiveLocationRules.freshness(now.minusSeconds(60), hasFix = true, panic = true, now = now))
        assertEquals(Freshness.STALE, LiveLocationRules.freshness(now.minusSeconds(120), hasFix = true, panic = true, now = now))
    }

    @Test fun `accuracy labels`() {
        assertEquals("±20 m", LiveLocationRules.accuracyLabel(20.0))
        assertEquals("±1 m", LiveLocationRules.accuracyLabel(0.3))
        assertEquals("±2 km", LiveLocationRules.accuracyLabel(2_000.0))
        assertEquals("±2.5 km", LiveLocationRules.accuracyLabel(2_500.0))
        assertEquals("±15 km", LiveLocationRules.accuracyLabel(15_000.0))
        assertTrue(LiveLocationRules.isApproximate(2_000.0))
        assertFalse(LiveLocationRules.isApproximate(40.0))
    }

    @Test fun `maps links always use a dot and encode the name`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val geo = LiveLocationRules.geoUri(12.5, 77.25, "Raina & me")
            assertEquals("geo:12.500000,77.250000?q=12.500000,77.250000(Raina%20%26%20me)", geo)
            assertEquals("https://www.google.com/maps/search/?api=1&query=12.500000,77.250000", LiveLocationRules.mapsWebUrl(12.5, 77.25))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun `active until the expiry`() {
        val end = Instant.parse("2026-10-26T13:00:00Z")
        assertTrue(LiveLocationRules.isActive(null, end))
        assertTrue(LiveLocationRules.isActive(end, end.minusSeconds(1)))
        assertFalse(LiveLocationRules.isActive(end, end))
        assertFalse(LiveLocationRules.isActive(end, end.plusSeconds(1)))
    }

    @Test fun `session ids look like object ids and start with the time`() {
        val id = LiveLocationRules.newSessionId(0x6720_0000L, Random(1))
        assertEquals(24, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(id.startsWith("67200000"))
    }

    @Test fun `trouble text for the share-sheet fallback`() {
        assertEquals(
            "I'm in trouble. My location: https://www.google.com/maps/search/?api=1&query=12.500000,77.250000",
            LiveLocationRules.troubleText(null, 12.5, 77.25),
        )
        assertEquals("Help. Please call me.", LiveLocationRules.troubleText(" Help ", null, null))
    }

    @Test fun `battery only within range and labels for durations`() {
        assertEquals(34, LiveLocationRules.batteryPct(34))
        assertEquals(null, LiveLocationRules.batteryPct(-1))
        assertEquals(null, LiveLocationRules.batteryPct(null))
        assertEquals("1 hour", LiveLocationRules.minutesLabel(60))
        assertEquals("8 hours", LiveLocationRules.minutesLabel(480))
        assertEquals("Until I turn it off", LiveLocationRules.minutesLabel(LiveLocationRules.UNTIL_OFF))
        assertEquals("just now", LiveLocationRules.ago(Instant.EPOCH, Instant.EPOCH.plusSeconds(10)))
        assertEquals("4 min ago", LiveLocationRules.ago(Instant.EPOCH, Instant.EPOCH.plusSeconds(4 * 60 + 5)))
        assertEquals("2 h ago", LiveLocationRules.ago(Instant.EPOCH, Instant.EPOCH.plusSeconds(2 * 3600 + 5)))
    }
}
