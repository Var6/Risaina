package com.risaina.app.core.data

import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.CalendarEntryDraft
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.core.model.CalendarSource
import com.risaina.app.core.model.CalendarSourceKind
import com.risaina.app.core.model.toDraft
import com.risaina.app.core.network.NetworkModule
import com.risaina.app.core.network.PatchCalendarRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** What the calendar sends: a whole entry on create, only the differences (with nulls) on an edit. */
class CalendarRequestsTest {
    private val base = CalendarEntry(
        id = "e1", coupleId = "c", createdBy = "me", updatedBy = "me", kind = CalendarKind.EVENT, title = "Dinner", notes = "At Olive",
        emoji = null, date = LocalDate.of(2026, 11, 14), endDate = null, time = LocalTime.of(20, 0), durationMinutes = 90,
        timeZone = "Asia/Kolkata", repeat = CalendarRepeat.NONE, repeatUntil = null, reminders = listOf(60), remindUserIds = listOf("me", "bea"),
        pinned = false, done = false, doneAt = null, doneBy = null, source = null, clientKey = null, version = 7,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    @Test fun `a create carries the whole draft, trimmed, with times as HH mm`() {
        val draft = CalendarEntryDraft(
            kind = CalendarKind.TODO, title = "  Book tickets ", date = LocalDate.of(2026, 12, 1), notes = "  ", time = LocalTime.of(9, 5),
            reminders = listOf(60, 0, 60), source = CalendarSource(CalendarSourceKind.MESSAGE, messageId = "m1"),
        )
        val req = CalendarRequests.create(draft, clientKey = "k1", notifyPartner = true)
        assertEquals("todo", req.kind)
        assertEquals("Book tickets", req.title)
        assertEquals("2026-12-01", req.date)
        assertEquals("09:05", req.time)
        assertNull("blank notes are not sent", req.notes)
        assertEquals(listOf(0, 60), req.reminders)
        assertEquals("message", req.source?.kind)
        assertEquals(true, req.notifyPartner)
        assertNull("both of us is the server's default", req.remindUserIds)
        val off = CalendarRequests.create(draft, "k1", notifyPartner = false)
        assertNull(off.notifyPartner)
    }

    @Test fun `an edit that changes nothing sends nothing`() {
        assertNull(CalendarRequests.patch(base, base.toDraft(), notifyPartner = true))
    }

    @Test fun `an edit sends only the changed fields at the base version`() {
        val req = CalendarRequests.patch(base, base.toDraft().copy(title = "Dinner with her parents", reminders = listOf(1440, 60)), notifyPartner = false)!!
        assertEquals(
            PatchCalendarRequest(title = "Dinner with her parents", reminders = listOf(60, 1440), expectedVersion = 7),
            req,
        )
    }

    @Test fun `making it all day clears time, zone and duration with explicit nulls`() {
        val req = CalendarRequests.patch(base, base.toDraft().copy(time = null, timeZone = null, durationMinutes = null, notes = ""), notifyPartner = true)!!
        assertEquals(setOf("time", "timeZone", "durationMinutes", "notes"), req.clear)
        assertEquals(true, req.notifyPartner)
        val body = com.risaina.app.core.network.withNulls(NetworkModule.json.encodeToJsonElement(PatchCalendarRequest.serializer(), req), req.clear)
        assertEquals(setOf("time", "timeZone", "durationMinutes", "notes", "expectedVersion", "notifyPartner"), body.keys)
    }

    @Test fun `who it reminds is compared as a set, and null means unchanged`() {
        assertNull(CalendarRequests.patch(base, base.toDraft().copy(remindUserIds = listOf("bea", "me")), false))
        assertNull(CalendarRequests.patch(base, base.toDraft().copy(remindUserIds = null), false))
        assertEquals(listOf("bea"), CalendarRequests.patch(base, base.toDraft().copy(remindUserIds = listOf("bea")), false)!!.remindUserIds)
    }

    @Test fun `an UNKNOWN kind or repeat is never sent`() {
        val weird = base.copy(kind = CalendarKind.UNKNOWN, repeat = CalendarRepeat.UNKNOWN)
        val req = CalendarRequests.patch(weird, weird.toDraft().copy(title = "Walk"), false)!!
        assertNull(req.kind)
        assertNull(req.repeat)
        assertNull(CalendarRequests.create(weird.toDraft(), "k", false).repeat)
    }
}
