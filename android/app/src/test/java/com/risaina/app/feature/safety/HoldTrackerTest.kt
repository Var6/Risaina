package com.risaina.app.feature.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldTrackerTest {
    /** Simulates frames every 16 ms from [from] to [to] and returns how many times it fired. */
    private fun HoldTracker.frames(from: Long, to: Long): Int {
        var fired = 0
        var t = from
        while (t <= to) {
            if (tick(t)) fired++
            t += 16
        }
        return fired
    }

    @Test fun `letting go at 500 ms never fires`() {
        val h = HoldTracker(2_000)
        h.press(0)
        assertEquals(0, h.frames(0, 500))
        assertFalse(h.release())
        // frames that arrive after the release (the loop is being cancelled) must not fire either
        assertEquals(0, h.frames(500, 3_000))
        assertEquals(0f, h.progress(3_000), 0f)
    }

    @Test fun `letting go just before the end never fires`() {
        val h = HoldTracker(2_000)
        h.press(1_000)
        assertEquals(0, h.frames(1_000, 2_990))
        assertFalse(h.release())
    }

    @Test fun `a full hold fires once, while still pressed`() {
        val h = HoldTracker(2_000)
        h.press(0)
        assertEquals(1, h.frames(0, 5_000))
        assertTrue(h.release())
    }

    @Test fun `a second hold after an early release starts from zero`() {
        val h = HoldTracker(2_000)
        h.press(0)
        h.frames(0, 1_500)
        h.release()
        h.press(10_000)
        assertEquals(0, h.frames(10_000, 11_900))
        assertEquals(1, h.frames(11_900, 12_100))
    }

    @Test fun `progress and quarter ticks`() {
        val h = HoldTracker(2_000)
        assertEquals(0f, h.progress(100), 0f)
        h.press(0)
        assertEquals(0.25f, h.progress(500), 0.001f)
        assertEquals(1, h.quarter(500))
        assertEquals(2, h.quarter(1_000))
        assertEquals(3, h.quarter(1_999))
        assertEquals(4, h.quarter(2_000))
        assertEquals(1f, h.progress(9_000), 0f)
        assertTrue(h.pressed)
        h.release()
        assertFalse(h.pressed)
    }
}
