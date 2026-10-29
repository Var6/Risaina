package com.risaina.app.feature.calendar

import com.risaina.app.testutil.calendarEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class EntryRulesTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val london = ZoneId.of("Europe/London")
    private val fri = LocalDate.of(2026, 11, 13)

    @Test fun `the time-zone line converts both ways and warns about the night`() {
        val theirs = EntryRules.zoneLine(fri, LocalTime.of(21, 0), london, kolkata, london, "Bea Rao", Locale.UK)!!
        assertEquals("9:00 pm Bea's time = 2:30 am Sat for you", theirs.text)
        assertTrue(theirs.night)
        val mine = EntryRules.zoneLine(fri, LocalTime.of(20, 0), kolkata, kolkata, london, "Bea", Locale.UK)!!
        assertEquals("8:00 pm for you = 2:30 pm for Bea", mine.text)
        assertFalse(mine.night)
        // A floating time is each phone's own clock: nothing to convert.
        assertNull(EntryRules.zoneLine(fri, LocalTime.of(20, 0), null, kolkata, london, "Bea", Locale.UK))
        assertNull(EntryRules.zoneLine(fri, LocalTime.of(20, 0), kolkata, kolkata, ZoneId.of("Asia/Calcutta"), "Bea", Locale.UK))
    }

    @Test fun `the time-zone row shows only when the clocks differ`() {
        assertTrue(EntryRules.zonesDiffer(kolkata, london, fri))
        assertFalse(EntryRules.zonesDiffer(kolkata, ZoneId.of("Asia/Calcutta"), fri))
        assertFalse(EntryRules.zonesDiffer(kolkata, null, fri))
    }

    @Test fun `emoji keep at most two characters`() {
        assertEquals("🎂", EntryRules.emoji(" 🎂 "))
        assertEquals("🎂🎉", EntryRules.emoji("🎂🎉🥳"))
        assertEquals("ab", EntryRules.emoji("abc"))
        assertEquals("", EntryRules.emoji(""))
    }

    @Test fun `when-lines`() {
        fun line(e: com.risaina.app.core.model.CalendarEntry) =
            EntryRules.whenLine(com.risaina.app.core.domain.Occurrence(e, e.date, com.risaina.app.core.domain.CalendarRules.localStart(e, e.date, kolkata)), 2026, Locale.UK)
        assertEquals("Sat 14 Nov", line(calendarEntry()))
        assertEquals("Fri 13 Nov – Sun 15 Nov", line(calendarEntry(date = fri, endDate = LocalDate.of(2026, 11, 15))))
        assertEquals("Sat 14 Nov, 8:00 pm – 10:00 pm", line(calendarEntry(time = LocalTime.of(20, 0)).copy(durationMinutes = 120)))
        assertEquals("Sun 1 Nov, 9:00 pm · Every week", line(calendarEntry(date = LocalDate.of(2026, 11, 1), time = LocalTime.of(21, 0), repeat = com.risaina.app.core.model.CalendarRepeat.WEEKLY)))
        assertEquals("Sat 2 Jan 2027", line(calendarEntry(date = LocalDate.of(2027, 1, 2))))
    }

    @Test fun `durations and default reminders`() {
        assertEquals("No end", EntryRules.durationLabel(null))
        assertEquals("30 min", EntryRules.durationLabel(30))
        assertEquals("2 hours", EntryRules.durationLabel(120))
        assertEquals("1 hour 30 min", EntryRules.durationLabel(90))
        assertEquals(setOf(0), EntryRules.defaultReminders(allDay = true))
        assertEquals(setOf(60), EntryRules.defaultReminders(allDay = false))
    }
}
