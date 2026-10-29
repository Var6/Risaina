package com.risaina.app.navigation

import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarSourceKind
import com.risaina.app.feature.calendar.CalendarPrefill
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** A prefill from the chat travels to the editor as the route's plain strings. */
class CalendarLinksTest {
    @Test fun `a message prefill becomes the editor's route`() {
        val route = CalendarPrefill(
            title = "Dinner", date = LocalDate.of(2026, 11, 14), time = LocalTime.of(20, 5), kind = CalendarKind.TODO,
            sourceKind = CalendarSourceKind.MESSAGE, sourceMessageId = "m1",
        ).toRoute()
        assertEquals(CalendarEntryEdit(title = "Dinner", date = "2026-11-14", time = "20:05", kind = "todo", sourceKind = "message", sourceMessageId = "m1"), route)
    }

    @Test fun `an empty prefill is a new event with nothing filled in`() {
        assertEquals(CalendarEntryEdit(kind = "event"), CalendarPrefill().toRoute())
    }
}
