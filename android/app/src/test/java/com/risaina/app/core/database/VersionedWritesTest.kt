package com.risaina.app.core.database

import com.risaina.app.testutil.FakeCalendarDao
import com.risaina.app.testutil.FakeListDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that keep an older server copy from replacing a newer one in Room (lists.md A2): a
 * replayed event or a GET answered after a save carried an older version, and writing it made a
 * just-added item disappear.
 */
class VersionedWritesTest {
    private fun list(id: String, version: Int, title: String = "Trip", syncedAt: Long = 0) = ListEntity(
        id = id, title = title, kind = "checklist", body = "", itemsJson = "[]", pinned = false, version = version,
        createdBy = "me", updatedBy = null, createdAt = 0, updatedAt = 0, syncedAt = syncedAt,
    )

    @Test fun `a list is written when Room has none, or an older or equal version`() = runTest {
        val dao = FakeListDao()
        assertTrue(dao.upsertIfNewer(list("a", 2)))
        assertTrue(dao.upsertIfNewer(list("a", 3, title = "Goa")))
        assertTrue("the same version again (e.g. with new fields) is written", dao.upsertIfNewer(list("a", 3, title = "Goa trip")))
        assertEquals("Goa trip", dao.byId("a")!!.title)
    }

    @Test fun `an older version never replaces a newer one`() = runTest {
        val dao = FakeListDao()
        dao.upsertIfNewer(list("a", 3, title = "With Hike"))
        // This phone's own v2 comes back by replay after its v3 reply.
        assertFalse(dao.upsertIfNewer(list("a", 2, title = "Without Hike")))
        assertEquals(3, dao.byId("a")!!.version)
        assertEquals("With Hike", dao.byId("a")!!.title)
    }

    @Test fun `a sync writes only newer copies and keeps a list written while its GET was on the way`() = runTest {
        val dao = FakeListDao()
        dao.upsert(list("old", 1, syncedAt = 100))        // deleted on the server since
        dao.upsert(list("kept", 5, title = "Newer here", syncedAt = 100))
        dao.upsert(list("created", 1, syncedAt = 250))    // POST answered after the GET started at 200
        dao.syncFromServer(listOf(list("kept", 4, title = "Older on the GET", syncedAt = 300), list("new", 1, syncedAt = 300)), requestedAtMillis = 200)

        assertNull("a list the server no longer has goes", dao.byId("old"))
        assertEquals("Newer here", dao.byId("kept")!!.title)
        assertEquals("a list created during the GET survives it", 1, dao.byId("created")!!.version)
        assertEquals(1, dao.byId("new")!!.version)
    }

    @Test fun `a list deleted while the GET was on its way is not brought back by it`() = runTest {
        val dao = FakeListDao()
        dao.upsert(list("kept", 1, syncedAt = 100))
        // "gone" was in Room, then deleted after the GET started; the answer still has it.
        dao.syncFromServer(listOf(list("gone", 3, syncedAt = 300), list("kept", 1, syncedAt = 300)), requestedAtMillis = 200, deletedMeanwhile = setOf("gone"))
        assertNull(dao.byId("gone"))
        assertEquals(setOf("kept"), dao.rows.value.keys)
    }

    @Test fun `a list the GET returned is never deleted even if Room kept its newer copy`() = runTest {
        val dao = FakeListDao()
        dao.upsert(list("a", 5, syncedAt = 10))
        dao.syncFromServer(listOf(list("a", 4, syncedAt = 300)), requestedAtMillis = 200)
        assertEquals(5, dao.byId("a")!!.version)
    }

    private fun entry(id: String, version: Int, title: String = "Dinner", syncedAt: Long = 0) = CalendarEntryEntity(
        id = id, coupleId = "c", createdBy = "me", updatedBy = null, kind = "event", title = title, notes = null, emoji = null,
        date = "2026-11-14", endDate = null, time = "20:00", durationMinutes = null, timeZone = null, repeat = "none",
        repeatUntil = null, reminders = "60", remindUserIds = "me,bea", pinned = false, done = false, doneAt = null, doneBy = null,
        sourceKind = null, sourceMessageId = null, sourceListId = null, sourceItemId = null, clientKey = null,
        version = version, createdAt = 0, updatedAt = 0, syncedAt = syncedAt,
    )

    @Test fun `a calendar sync removes what the server lacks and keeps newer local copies`() = runTest {
        val dao = FakeCalendarDao()
        dao.upsert(entry("gone", 1, syncedAt = 100))
        dao.upsert(entry("edited", 3, title = "Dinner at 8", syncedAt = 100))
        dao.syncFromServer(listOf(entry("edited", 2, title = "Dinner", syncedAt = 300), entry("added", 1, syncedAt = 300)), requestedAtMillis = 200)
        assertEquals(setOf("edited", "added"), dao.rows.value.keys)
        assertEquals("Dinner at 8", dao.byId("edited")!!.title)
    }

    @Test fun `an entry created or received while the calendar GET was on its way survives it`() = runTest {
        val dao = FakeCalendarDao()
        // Saved here (or a partner's calendar.created applied) at 250, after the GET started at 200.
        dao.upsert(entry("mine", 1, syncedAt = 250))
        dao.syncFromServer(emptyList(), requestedAtMillis = 200)
        assertEquals(1, dao.byId("mine")!!.version)
    }

    @Test fun `a replayed calendar event does not undo a newer edit`() = runTest {
        val dao = FakeCalendarDao()
        dao.upsertIfNewer(entry("e", 4, title = "Moved to Sat"))
        assertFalse(dao.upsertIfNewer(entry("e", 3, title = "Fri")))
        assertEquals("Moved to Sat", dao.byId("e")!!.title)
    }
}
