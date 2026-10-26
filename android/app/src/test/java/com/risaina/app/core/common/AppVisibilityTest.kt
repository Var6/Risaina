package com.risaina.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Risaina is open" for notifications and the socket: its own screens, debounced like ProcessLifecycleOwner. */
class AppVisibilityTest {
    private val seen = mutableListOf<Boolean>()
    private val counter = VisibilityCounter { seen += it }

    @Test fun visibleAtOnceHiddenOnlyAfterTheLeaveDelay() {
        counter.onStarted()
        assertEquals(listOf(true), seen)
        assertTrue(counter.onStopped())
        assertEquals(listOf(true), seen) // not yet: the delay has not passed
        counter.onLeaveDelayOver()
        assertEquals(listOf(true, false), seen)
    }

    @Test fun aRotationIsNotLeaving() {
        counter.onStarted()
        assertTrue(counter.onStopped())
        counter.onStarted() // the recreated activity, within the delay
        counter.onLeaveDelayOver() // the stale delayed callback must not report leaving
        assertFalse(seen.contains(false))
    }

    @Test fun twoScreensOneLeftStillVisible() {
        counter.onStarted()
        counter.onStarted()
        assertFalse(counter.onStopped())
        counter.onLeaveDelayOver()
        assertFalse(seen.contains(false))
    }

    @Test fun aStopWithoutAStartNeverGoesNegative() {
        // Registered after an activity had already started (its stop arrives, its start did not).
        counter.onStopped()
        counter.onStarted()
        assertTrue(counter.onStopped())
        counter.onLeaveDelayOver()
        assertEquals(listOf(true, false), seen)
    }
}
