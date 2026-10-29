package com.risaina.app.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderDaysTest {
    @Test fun `days round-trip, largest first, at most four`() {
        assertEquals("7,1,0", ReminderDays.format(listOf(0, 7, 1, 7)))
        assertEquals(listOf(7, 1, 0), ReminderDays.parse("0,7,1"))
        assertEquals(listOf(28, 14, 7, 3), ReminderDays.parse("1,3,7,14,28"))
        assertEquals(emptyList<Int>(), ReminderDays.parse(""))
    }

    @Test fun `a damaged value falls back to the default instead of crashing`() {
        assertNull(ReminderDays.parse("7,x"))
        assertEquals(listOf(7), ReminderDays.parse("7,99,-1"))
    }
}
