package com.risaina.app.feature.calendar

import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
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
        val eight = LocalTime.of(20, 0)
        assertTrue(EntryRules.zonesDiffer(kolkata, london, fri, eight))
        assertFalse(EntryRules.zonesDiffer(kolkata, ZoneId.of("Asia/Calcutta"), fri, eight))
        assertFalse(EntryRules.zonesDiffer(kolkata, null, fri, eight))
        assertFalse(EntryRules.zonesDiffer(london, ZoneId.of("Europe/Lisbon"), fri, eight))
    }

    @Test fun `whose time is asked by the moment an entry starts, not its whole day`() {
        val phoenix = ZoneId.of("America/Phoenix")
        val denver = ZoneId.of("America/Denver")
        // Denver leaves summer time at 1:00 am Phoenix time on Sun 1 Nov: two clocks until then, one after.
        val sevenPm = EntryForm(date = LocalDate.of(2026, 11, 1), allDay = false, time = LocalTime.of(19, 0), timeZone = "America/Phoenix")
        assertFalse(EntryRules.asksWhoseTime(sevenPm, phoenix, denver))
        assertTrue(EntryRules.asksWhoseTime(sevenPm.copy(time = LocalTime.of(0, 30)), phoenix, denver))
        // Every 15 Jan falls in winter, when the two agree: nothing to ask. Weekly, it reaches March.
        val january = sevenPm.copy(date = LocalDate.of(2027, 1, 15), repeat = CalendarRepeat.YEARLY)
        assertFalse(EntryRules.asksWhoseTime(january, phoenix, denver))
        assertTrue(EntryRules.asksWhoseTime(january.copy(repeat = CalendarRepeat.WEEKLY), phoenix, denver))
    }

    @Test fun `whose time is asked only when the two of you are on two clocks`() {
        val eight = EntryForm(date = fri, allDay = false, time = LocalTime.of(20, 0), timeZone = "Asia/Kolkata")
        assertTrue(EntryRules.asksWhoseTime(eight, kolkata, london))
        // One clock: nothing to ask, under any name, or with their zone not shared.
        assertFalse(EntryRules.asksWhoseTime(eight, kolkata, kolkata))
        assertFalse(EntryRules.asksWhoseTime(eight, kolkata, ZoneId.of("Asia/Calcutta")))
        assertFalse(EntryRules.asksWhoseTime(eight, kolkata, null))
        assertFalse(EntryRules.asksWhoseTime(eight.copy(timeZone = "Europe/London"), london, ZoneId.of("Europe/Lisbon")))
        // All day, or no date or time yet: no clock to choose.
        assertFalse(EntryRules.asksWhoseTime(eight.copy(allDay = true, time = null), kolkata, london))
        assertFalse(EntryRules.asksWhoseTime(eight.copy(date = null), kolkata, london))
    }

    @Test fun `a repeat asks when the clocks part ways on any of its days`() {
        val phoenix = ZoneId.of("America/Phoenix")
        val la = ZoneId.of("America/Los_Angeles")
        // One clock in July; Los Angeles leaves summer time on 1 Nov and is an hour behind until March.
        val july = EntryForm(date = LocalDate.of(2026, 7, 4), allDay = false, time = LocalTime.of(19, 0), timeZone = "America/Phoenix")
        assertFalse(EntryRules.asksWhoseTime(july, phoenix, la))
        assertTrue(EntryRules.asksWhoseTime(july.copy(repeat = CalendarRepeat.WEEKLY), phoenix, la))
        assertFalse(EntryRules.asksWhoseTime(july.copy(repeat = CalendarRepeat.WEEKLY, repeatUntil = LocalDate.of(2026, 10, 31)), phoenix, la))
        assertTrue(EntryRules.asksWhoseTime(july.copy(repeat = CalendarRepeat.MONTHLY, repeatUntil = LocalDate.of(2026, 11, 4)), phoenix, la))
        // A to-do never repeats, whatever the form still holds.
        assertFalse(EntryRules.asksWhoseTime(july.copy(kind = CalendarKind.TODO, repeat = CalendarRepeat.WEEKLY), phoenix, la))
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

    // ---- a date suggested from the title ----

    private val ref = java.time.ZonedDateTime.of(2026, 10, 29, 10, 0, 0, 0, kolkata)
    private fun take(title: String, foundIn: String): String {
        val hit = com.risaina.app.core.domain.DateDetector.find(foundIn, ref, com.risaina.app.core.domain.DateOrder.DMY).first()
        return EntryRules.titleTakingSuggestion(title, foundIn, hit, ref, com.risaina.app.core.domain.DateOrder.DMY)
    }

    @Test fun `taking a suggested date cuts its words from the title`() {
        assertEquals("Dinner with mum", take("Dinner on 3/4 with mum", "Dinner on 3/4 with mum"))
    }

    @Test fun `a title rewritten since the suggestion keeps its words`() {
        // The chip was for "3/4" in the old title; cutting the same places now would mangle the new one.
        assertEquals("Lunch with mum and dad", take("Lunch with mum and dad", "Dinner on 3/4 with mum"))
        // Edited elsewhere, the date words still there (and moved): only they go.
        assertEquals("Dinner with mum and dad", take("Dinner on 3/4 with mum and dad", "Dinner on 3/4 with mum"))
        assertEquals("Big dinner with mum", take("Big dinner on 3/4 with mum", "Dinner on 3/4 with mum"))
        // Another date typed in their place is not the one suggested: the title stays as typed.
        assertEquals("Dinner on 5/4 with mum", take("Dinner on 5/4 with mum", "Dinner on 3/4 with mum"))
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
