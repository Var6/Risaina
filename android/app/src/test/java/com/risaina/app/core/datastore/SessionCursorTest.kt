package com.risaina.app.core.datastore

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The event cursor, the replay mark and the bound couple (SessionStore's edits). Keys are rebuilt by
 * name here; DataStore keys are equal by name.
 */
class SessionCursorTest {
    private val cursor = longPreferencesKey("last_event_seq")
    private val mark = longPreferencesKey("bootstrap_until_seq")
    private val couple = stringPreferencesKey("active_couple_id")

    // --- binding a couple ---------------------------------------------------------------------

    @Test fun anActiveCoupleUpdatedFromTheFeedKeepsTheCursorAndMark() {
        // Not paired yet; the first sync after the partner accepted applied seq 1 (couple.updated)
        // and seq 2 (a ping) of the new couple's feed.
        val p = mutablePreferencesOf(cursor to 0L)
        SessionStore.markBootstrap(p, latestSeq = 2)
        SessionStore.adoptCouple(p, "c1")
        SessionStore.advanceCursor(p, 2)
        // The queued refreshMe then binds the same couple: nothing may rewind.
        SessionStore.bindCouple(p, "c1")
        assertEquals(2L, p[cursor])
        assertEquals(2L, p[mark])
        assertEquals("c1", p[couple])
    }

    @Test fun refreshMeWithoutTheAdoptStillRestartsTheCursorForANewCouple() {
        // After an unlink the slot is empty but the cursor still belongs to the old couple (its
        // couple.unlinked must not replay). Pairing with someone else through refreshMe restarts it.
        val p = mutablePreferencesOf(cursor to 500L, mark to 40L)
        SessionStore.bindCouple(p, null)
        assertEquals(500L, p[cursor])
        SessionStore.bindCouple(p, "c2")
        assertEquals(0L, p[cursor])
        assertNull(p[mark])
        assertEquals("c2", p[couple])
    }

    @Test fun adoptNeverReplacesADifferentCouple() {
        // A different stored couple means this phone missed an unlink: refreshMe wipes and restarts.
        val p = mutablePreferencesOf(cursor to 30L, couple to "old")
        SessionStore.adoptCouple(p, "new")
        assertEquals("old", p[couple])
        assertEquals(30L, p[cursor])
    }

    @Test fun switchingCouplesRestartsCursorAndMark() {
        val p = mutablePreferencesOf(cursor to 30L, mark to 12L, couple to "a")
        SessionStore.bindCouple(p, "b")
        assertEquals(0L, p[cursor])
        assertNull(p[mark])
    }

    // --- the replay mark ----------------------------------------------------------------------

    @Test fun theFirstSnapshotOfACursorZeroEpochWins() {
        // Fresh sign-in: the socket's Ready said 40; then the worker's first page says 42 because the
        // partner just sent a ping (41-42) while the cursor is still 0. 41 and 42 are new, not history.
        val p = mutablePreferencesOf()
        SessionStore.markBootstrap(p, 40)
        SessionStore.markBootstrap(p, 42)
        assertEquals(40L, p[mark])
    }

    @Test fun noMarkOnceTheCursorMoved() {
        val p = mutablePreferencesOf(cursor to 7L)
        SessionStore.markBootstrap(p, 9)
        assertNull(p[mark])
    }

    @Test fun aNewEpochGetsANewMark() {
        val p = mutablePreferencesOf()
        SessionStore.markBootstrap(p, 40)
        SessionStore.advanceCursor(p, 40)
        SessionStore.bindCouple(p, "other") // re-paired: cursor and mark restart
        SessionStore.markBootstrap(p, 5)
        assertEquals(5L, p[mark])
    }

    @Test fun theCursorOnlyMovesForward() {
        val p = mutablePreferencesOf(cursor to 10L)
        SessionStore.advanceCursor(p, 8)
        assertEquals(10L, p[cursor])
        SessionStore.advanceCursor(p, 11)
        assertEquals(11L, p[cursor])
    }
}
