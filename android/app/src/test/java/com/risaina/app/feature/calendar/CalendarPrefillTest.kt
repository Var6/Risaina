package com.risaina.app.feature.calendar

import com.risaina.app.core.domain.DateOrder
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarSource
import com.risaina.app.core.model.CalendarSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarPrefillTest {
    private val ref = ZonedDateTime.of(2026, 10, 29, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    @Test fun `a message becomes a title and a date, linked to the message`() {
        val p = CalendarPrefills.fromText("Dinner at Olive tomorrow 7pm?", ref, DateOrder.DMY, "Plan with Bea", messageId = "m1")
        assertEquals("Dinner at Olive", p.title)
        assertEquals(LocalDate.of(2026, 10, 30), p.date)
        assertEquals(LocalTime.of(19, 0), p.time)
        assertEquals(CalendarSourceKind.MESSAGE, p.sourceKind)
        assertEquals("m1", p.sourceMessageId)
    }

    @Test fun `only a date leaves the fallback title, and no date leaves the date to pick`() {
        assertEquals("Plan with Bea", CalendarPrefills.fromText("Sat?", ref, DateOrder.DMY, "Plan with Bea").title)
        val none = CalendarPrefills.fromText("We should go to the hills\nmaybe", ref, DateOrder.DMY, "Plan with Bea", kind = CalendarKind.TODO)
        assertEquals("We should go to the hills", none.title)
        assertNull(none.date)
        assertEquals(CalendarKind.TODO, none.kind)
        assertNull(none.sourceKind)
    }

    @Test fun `the composer's chip wants a date the parser is sure of`() {
        assertEquals(LocalDate.of(2026, 11, 14), CalendarPrefills.confidentHit("dinner 14 Nov 8pm", ref, DateOrder.DMY)?.date)
        assertNull(CalendarPrefills.confidentHit("see you at 8", ref, DateOrder.DMY))
        assertNull(CalendarPrefills.confidentHit("3/4 done", ref, DateOrder.DMY))
        assertNull(CalendarPrefills.confidentHit("hello", ref, DateOrder.DMY))
    }

    @Test fun `a source is kept only in a shape the server takes`() {
        val msg = "665f1c2e9b1d4a3f8c2e7a10"
        val list = "665f1c2e9b1d4a3f8c2e7a11"
        assertEquals(CalendarSource(CalendarSourceKind.MESSAGE, messageId = msg), CalendarPrefills.source("message", msg, null, null))
        assertEquals(CalendarSource(CalendarSourceKind.LIST, listId = list), CalendarPrefills.source("list", null, list, null))
        assertEquals(CalendarSource(CalendarSourceKind.LIST_ITEM, listId = list, itemId = "c0123456789abcde"), CalendarPrefills.source("list_item", null, list, "c0123456789abcde"))
        // An item with no usable id still links its list.
        assertEquals(CalendarSource(CalendarSourceKind.LIST, listId = list), CalendarPrefills.source("list_item", null, list, null))
        assertEquals(CalendarSource(CalendarSourceKind.LIST, listId = list), CalendarPrefills.source("list_item", null, list, "has space"))
        // A note not sent yet (no server id), a local message key, an unknown kind or none: no link.
        assertNull(CalendarPrefills.source("list", null, null, null))
        assertNull(CalendarPrefills.source("list_item", null, "local-1", "r1"))
        assertNull(CalendarPrefills.source("message", "local-7f3a", null, null))
        assertNull(CalendarPrefills.source("message", msg.uppercase(), null, null))
        assertNull(CalendarPrefills.source("photo_album", msg, list, null))
        assertNull(CalendarPrefills.source(null, msg, list, null))
    }

    @Test fun `a time from the partner's message keeps their clock`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val london = ZoneId.of("Europe/London")
        val sat = LocalDate.of(2026, 10, 31)
        val eight = LocalTime.of(20, 0)
        assertEquals("Europe/London", CalendarPrefills.zoneFor(eight, sat, kolkata, london))
        assertEquals("Asia/Kolkata", CalendarPrefills.zoneFor(eight, sat, kolkata, null))
        // Same clock that day: mine, so the zone row stays hidden.
        assertEquals("Asia/Kolkata", CalendarPrefills.zoneFor(eight, sat, kolkata, ZoneId.of("Asia/Calcutta")))
        assertEquals("Asia/Kolkata", CalendarPrefills.zoneFor(eight, null, kolkata, london))
        assertNull(CalendarPrefills.zoneFor(null, sat, kolkata, london))
    }

    @Test fun `long text is cut to a title`() {
        assertEquals(120, CalendarPrefills.plainTitle("x".repeat(500), "f").length)
        assertEquals("f", CalendarPrefills.plainTitle("  \n ", "f"))
    }
}
