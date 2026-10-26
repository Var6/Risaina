package com.risaina.app.core.sos

import com.risaina.app.testutil.contextWithPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this phone already did about each SOS, matched by SOS id or by event seq: the outbox's push
 * knows only the seq, a re-push and the sync also know the id.
 */
class SosLocalStateTest {
    private val state = SosLocalState(contextWithPrefs())

    @Test
    fun `a push known only by its seq and the synced SOS are the same entry once linked`() {
        state.markRang(null, 42)
        state.link(42, "s1")
        assertTrue(state.isHandled("s1", null))
        assertTrue(state.isHandled(null, 42))
        assertFalse("rang, but nobody responded yet", state.isClosed("s1", null))
        assertEquals("s1", state.sosIdForSeq(42))

        state.markResolved("s1")
        assertTrue(state.isClosed(null, 42))
        assertFalse(state.isHandled("other", 7))
    }

    @Test
    fun `an acknowledgement before the id is known closes the SOS when it is linked`() {
        state.markAcked(null, 5)
        state.link(5, "s5")
        assertTrue(state.isClosed("s5", null))
        assertNull(state.entry("s5", null)?.resolvedAt)
    }

    @Test
    fun `nothing is known about an SOS that never reached this phone`() {
        assertFalse(state.isHandled("new", 99))
        assertFalse(state.isClosed("new", 99))
        assertNull(state.sosIdForSeq(99))
    }

    @Test
    fun `clear forgets every SOS but keeps the alarm volume to restore`() {
        state.markRang("a", 1)
        state.savedAlarmVolume = 3
        state.clear()
        assertFalse(state.isHandled("a", 1))
        assertEquals(3, state.savedAlarmVolume)
    }
}
