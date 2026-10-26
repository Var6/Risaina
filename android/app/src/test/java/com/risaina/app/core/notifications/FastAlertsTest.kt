package com.risaina.app.core.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detailed notification must not buzz again right after the quick one for the same item. */
class FastAlertsTest {
    private val now = 1_000_000L

    @Test fun theDetailedPostForTheSameItemIsSilent() {
        assertTrue(FastAlerts.follows(fastAt = now - 3_000, fastRef = "m1", ref = "m1", now = now))
    }

    @Test fun anotherItemFromTheSameSyncStillAlerts() {
        // A burst: the sync for m1's push also brought m2, which had no quick alert of its own.
        assertFalse(FastAlerts.follows(fastAt = now - 3_000, fastRef = "m1", ref = "m2", now = now))
    }

    @Test fun aQuickAlertFromAnOlderServerMatchesAnyItem() {
        assertTrue(FastAlerts.follows(fastAt = now - 3_000, fastRef = null, ref = "p9", now = now))
    }

    @Test fun anOldQuickAlertNeverMutesANewItem() {
        assertFalse(FastAlerts.follows(fastAt = now - FastAlerts.WINDOW_MS, fastRef = "m1", ref = "m1", now = now))
    }

    @Test fun noQuickAlertOrAClockJumpMeansAlert() {
        assertFalse(FastAlerts.follows(fastAt = 0, fastRef = null, ref = "m1", now = now))
        assertFalse(FastAlerts.follows(fastAt = now + 60_000, fastRef = "m1", ref = "m1", now = now))
    }
}
