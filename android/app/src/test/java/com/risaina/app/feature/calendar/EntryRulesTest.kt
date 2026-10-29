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

    // ---- a save refused because the partner saved first ----

    private val ids = listOf("me", "bea")
    private fun form(e: com.risaina.app.core.model.CalendarEntry) = EntryRules.formFor(e, "me", "bea")
    private fun patchAfter(theirs: com.risaina.app.core.model.CalendarEntry, f: EntryForm) =
        com.risaina.app.core.data.CalendarRequests.patch(theirs, EntryRules.draft(f, f.date!!, ids, null), notifyPartner = false)

    @Test fun `a conflict keeps what the person typed and takes the partner's other changes`() {
        val base = calendarEntry(title = "Dinner", time = LocalTime.of(20, 0), timeZone = "Asia/Kolkata", reminders = listOf(60))
        // I write the address in the notes; meanwhile Bea renames it (version 2).
        val mine = form(base).copy(notes = "Olive, Bandra. Table for two")
        val theirs = base.copy(title = "Dinner at 8", version = 2)
        val rebased = EntryRules.rebase(form(base), form(theirs), mine)
        assertEquals("Dinner at 8", rebased.title)
        assertEquals("Olive, Bandra. Table for two", rebased.notes)
        // The next save sends only my notes, at Bea's version.
        assertEquals(
            com.risaina.app.core.network.PatchCalendarRequest(notes = "Olive, Bandra. Table for two", expectedVersion = 2),
            patchAfter(theirs, rebased),
        )
    }

    @Test fun `a conflict on different fields merges them, and on the same field the person's wins`() {
        val base = calendarEntry(title = "Dinner", time = LocalTime.of(20, 0), timeZone = "Asia/Kolkata", reminders = listOf(60))
        val mine = form(base).copy(time = LocalTime.of(21, 0), title = "Dinner with Ma", pinned = true)
        val theirs = base.copy(date = LocalDate.of(2026, 11, 15), title = "Dinner at Olive", reminders = listOf(10, 60), version = 2)
        val r = EntryRules.rebase(form(base), form(theirs), mine)
        assertEquals(LocalDate.of(2026, 11, 15), r.date)
        assertEquals(LocalTime.of(21, 0), r.time)
        assertEquals("Dinner with Ma", r.title)
        assertEquals(setOf(10, 60), r.reminders)
        assertTrue(r.pinned)
        assertEquals(
            com.risaina.app.core.network.PatchCalendarRequest(title = "Dinner with Ma", time = "21:00", pinned = true, expectedVersion = 2),
            patchAfter(theirs, r),
        )
    }

    @Test fun `a conflict takes the when as one - all day from one side never meets a time from the other`() {
        val base = calendarEntry(time = LocalTime.of(20, 0), timeZone = "Asia/Kolkata", durationMinutes = 120, reminders = listOf(60))
        val allDay = base.copy(time = null, timeZone = null, durationMinutes = null, version = 2)
        // I only changed the notes: Bea's "all day" stands.
        val notesOnly = EntryRules.rebase(form(base), form(allDay), form(base).copy(notes = "Bring flowers"))
        assertTrue(notesOnly.allDay)
        assertNull(notesOnly.time)
        assertNull(notesOnly.durationMinutes)
        // I moved the time: my timed entry stands, whole.
        val moved = EntryRules.rebase(form(base), form(allDay), form(base).copy(time = LocalTime.of(21, 0)))
        assertFalse(moved.allDay)
        assertEquals(LocalTime.of(21, 0), moved.time)
        assertEquals("Asia/Kolkata", moved.timeZone)
        assertEquals(120, moved.durationMinutes)
    }

    @Test fun `a conflict ignores spaces at the ends, keeps my choices about telling and clamps what no longer fits`() {
        val base = calendarEntry(title = "Goa", date = fri, endDate = LocalDate.of(2026, 11, 15), reminders = listOf(0))
        val mine = form(base).copy(title = "Goa ", endDate = LocalDate.of(2026, 11, 16), notifyPartner = true)
        // Bea renamed it and moved the start past my new end.
        val theirs = base.copy(title = "Goa trip", date = LocalDate.of(2026, 11, 20), endDate = LocalDate.of(2026, 11, 22), version = 2)
        val r = EntryRules.rebase(form(base), form(theirs), mine)
        assertEquals("Goa trip", r.title)
        assertNull(r.endDate)
        assertTrue(r.notifyPartner)
        // Nothing changed on my side: the form is simply Bea's copy.
        assertEquals(form(theirs), EntryRules.rebase(form(base), form(theirs), form(base)))
        assertNull(patchAfter(theirs, EntryRules.rebase(form(base), form(theirs), form(base))))
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
