package com.risaina.app.core.sync

import com.risaina.app.core.data.CalendarDeletions
import com.risaina.app.core.data.toEntity
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.model.Session
import com.risaina.app.core.network.CalendarEntryDto
import com.risaina.app.core.network.EventDto
import com.risaina.app.core.network.NetworkModule
import com.risaina.app.core.network.SharedListDto
import com.risaina.app.testutil.FakeCalendarDao
import com.risaina.app.testutil.FakeListDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The reducer branches added for this round: calendar.* update Room, alarm.* are passed over until
 * the wake package handles them, list.* never write an older copy. The cursor moves on for all.
 */
class NextRoundEventsTest {
    private val json = NetworkModule.json
    private val cursor = MutableStateFlow(0L)
    private val session = mockk<SessionStore>().also {
        coEvery { it.current() } returns Session("me", "a", Instant.MAX, "r", Instant.MAX, "d")
        every { it.lastEventSeq } returns cursor
        coEvery { it.setLastEventSeq(any()) } answers { cursor.value = firstArg() }
    }
    private val listDao = FakeListDao()
    private val calendarDao = FakeCalendarDao()
    private val reducer = EventReducer(
        json, session, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), listDao,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), calendarDao, mockk(relaxed = true),
        CalendarDeletions(), { rearms++ },
    )
    private var rearms = 0

    private fun event(seq: Long, type: String, payload: Map<String, JsonElement>, actor: String = "bea") =
        EventDto("e$seq", "c", seq, type, actor, JsonObject(payload), "2026-10-29T10:00:00.000Z")

    private fun entry(version: Int, title: String) = json.encodeToJsonElement(
        CalendarEntryDto.serializer(),
        CalendarEntryDto(id = "cal", kind = "event", title = title, date = "2026-11-14", version = version, createdAt = "2026-10-29T10:00:00.000Z", updatedAt = "2026-10-29T10:00:00.000Z"),
    )

    @Test fun `calendar events add, update (newer only) and delete entries`() = runTest {
        assertTrue(reducer.apply(event(1, "calendar.created", mapOf("entry" to entry(1, "Dinner"), "notify" to JsonPrimitive(true)), actor = "me")).isEmpty())
        assertEquals("Dinner", calendarDao.byId("cal")!!.title)
        reducer.apply(event(2, "calendar.updated", mapOf("entry" to entry(3, "Dinner at 8"), "notify" to JsonPrimitive(false))))
        reducer.apply(event(3, "calendar.updated", mapOf("entry" to entry(2, "Dinner at 7"))))
        assertEquals("Dinner at 8", calendarDao.byId("cal")!!.title)
        reducer.apply(event(4, "calendar.deleted", mapOf("entryId" to JsonPrimitive("cal"))))
        assertNull(calendarDao.byId("cal"))
        assertEquals(4L, cursor.value)
    }

    @Test fun `the partner's entry with notify on is announced, once and only when fresh`() = runTest {
        val out = reducer.apply(event(1, "calendar.created", mapOf("entry" to entry(1, "Dinner"), "notify" to JsonPrimitive(true))))
        val shared = out.single() as Incoming.CalendarShared
        assertEquals("Dinner", shared.entry.title)
        assertEquals(false, shared.updated)
        val moved = reducer.apply(event(2, "calendar.updated", mapOf("entry" to entry(2, "Dinner at 9"), "notify" to JsonPrimitive(true)))).single() as Incoming.CalendarShared
        assertEquals(true, moved.updated)
        // Not asked to tell me, my own change, or history being replayed: stored, not announced.
        assertTrue(reducer.apply(event(3, "calendar.updated", mapOf("entry" to entry(3, "Dinner at 10"), "notify" to JsonPrimitive(false)))).isEmpty())
        assertTrue(reducer.apply(event(4, "calendar.updated", mapOf("entry" to entry(4, "Dinner at 11"), "notify" to JsonPrimitive(true)), actor = "me")).isEmpty())
        assertTrue(reducer.apply(event(5, "calendar.updated", mapOf("entry" to entry(5, "Dinner at 12"), "notify" to JsonPrimitive(true))), replay = true).isEmpty())
        assertTrue(reducer.apply(event(6, "calendar.updated", mapOf("entry" to entry(6, "Dinner"), "notify" to JsonPrimitive("true")))).isEmpty())
        assertEquals("Dinner", calendarDao.byId("cal")!!.title)
    }

    @Test fun `calendar, couple, partner and milestone events ask for the reminders to be worked out again`() = runTest {
        reducer.apply(event(1, "calendar.created", mapOf("entry" to entry(1, "Dinner"))))
        reducer.apply(event(2, "calendar.deleted", mapOf("entryId" to JsonPrimitive("cal"))))
        reducer.apply(event(3, "milestone.deleted", mapOf("milestoneId" to JsonPrimitive("m"))))
        assertEquals(3, rearms)
        reducer.apply(event(4, "list.deleted", mapOf("listId" to JsonPrimitive("l"))))
        assertEquals(3, rearms)
    }

    @Test fun `a deleted entry is remembered so an older GET answer does not bring it back`() = runTest {
        val deletions = CalendarDeletions()
        val r = EventReducer(
            json, session, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), listDao,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), calendarDao, mockk(relaxed = true),
            deletions, {},
        )
        val before = System.currentTimeMillis()
        r.apply(event(1, "calendar.deleted", mapOf("entryId" to JsonPrimitive("cal"))))
        assertTrue(deletions.deletedSince("cal", before))
    }

    @Test fun `an unreadable calendar payload is skipped and the cursor still moves`() = runTest {
        reducer.apply(event(1, "calendar.created", mapOf("entry" to JsonObject(mapOf("id" to JsonPrimitive("x"))))))
        assertTrue(calendarDao.rows.value.isEmpty())
        assertEquals(1L, cursor.value)
    }

    @Test fun `alarm events are passed over for now, without failing the sync`() = runTest {
        for ((seq, type) in listOf("alarm.created", "alarm.updated", "alarm.woke", "alarm.deleted").withIndex()) {
            assertTrue(reducer.apply(event(seq + 1L, type, mapOf("alarm" to JsonObject(emptyMap()), "alarmId" to JsonPrimitive("a")))).isEmpty())
        }
        assertEquals(4L, cursor.value)
        coVerify(exactly = 4) { session.setLastEventSeq(any()) }
    }

    @Test fun `a replayed older list version does not replace the newer one`() = runTest {
        fun list(v: Int, title: String) = json.encodeToJsonElement(
            SharedListDto.serializer(),
            SharedListDto("l", "c", "me", null, title, "checklist", "", emptyList(), false, v, "2026-10-29T10:00:00.000Z", "2026-10-29T10:00:00.000Z"),
        )
        listDao.upsert(json.decodeFromJsonElement(SharedListDto.serializer(), list(3, "With Hike")).toEntity(json))
        reducer.apply(event(1, "list.updated", mapOf("list" to list(2, "Without Hike")), actor = "me"))
        assertEquals("With Hike", listDao.byId("l")!!.title)
        reducer.apply(event(2, "list.updated", mapOf("list" to list(4, "Hike and beach"))))
        assertEquals("Hike and beach", listDao.byId("l")!!.title)
    }
}
