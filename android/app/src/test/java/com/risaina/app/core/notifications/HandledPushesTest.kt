package com.risaina.app.core.notifications

import com.risaina.app.testutil.contextWithPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A push delivered twice posts one quick alert, not two. */
class HandledPushesTest {
    @Test fun theFirstIdIsNewAndTheSameOneAgainIsNot() {
        val (first, stored) = HandledPushes.record(null, "e1")
        assertTrue(first)
        val (again, storedAgain) = HandledPushes.record(stored, "e1")
        assertFalse(again)
        assertEquals(stored, storedAgain)
        assertTrue(HandledPushes.record(stored, "e2").first)
    }

    @Test fun the65thDistinctIdPushesOutTheOldest() {
        var stored: String? = null
        for (i in 1..HandledPushes.MAX) stored = HandledPushes.record(stored, "e$i").second
        assertFalse("still remembered", HandledPushes.record(stored, "e1").first)
        stored = HandledPushes.record(stored, "e65").second
        assertEquals(HandledPushes.MAX, stored!!.split(',').size)
        assertTrue("the oldest one was pushed out", HandledPushes.record(stored, "e1").first)
        assertFalse(HandledPushes.record(stored, "e2").first)
        assertFalse(HandledPushes.record(stored, "e65").first)
    }

    @Test fun aBlankOrMissingIdAlwaysCountsAsNew() {
        val stored = HandledPushes.record(null, "e1").second
        for (id in listOf(null, "", "  ")) {
            val (first, next) = HandledPushes.record(stored, id)
            assertTrue(first)
            assertEquals("nothing is stored for it", stored, next)
            assertTrue(HandledPushes.record(next, id).first)
        }
    }

    @Test fun diagnosticsRemembersAcrossCalls() {
        val diagnostics = DeliveryDiagnostics(contextWithPrefs())
        assertTrue(diagnostics.firstTimePush("65a1f0c2e4b0a1b2c3d4e5f6"))
        assertFalse(diagnostics.firstTimePush("65a1f0c2e4b0a1b2c3d4e5f6"))
        assertTrue(diagnostics.firstTimePush(null))
        assertTrue(diagnostics.firstTimePush(null))
    }
}
