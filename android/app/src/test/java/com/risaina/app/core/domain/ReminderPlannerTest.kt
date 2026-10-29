package com.risaina.app.core.domain

import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.core.model.Couple
import com.risaina.app.core.model.CoupleStatus
import com.risaina.app.core.model.Milestone
import com.risaina.app.core.model.MilestoneKind
import com.risaina.app.core.model.Partner
import com.risaina.app.testutil.calendarEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Which reminders are due and when the next one is (calendar.md §6), on a phone in Kolkata. */
class ReminderPlannerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Instant = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()
    private val now = at(2026, 10, 29, 10)
    private val nothing = ReminderSettings(anniversary = OccasionReminders(false, listOf(7, 0)), partnerBirthday = OccasionReminders(false, listOf(7, 1, 0)))

    private fun plan(
        entries: List<com.risaina.app.core.model.CalendarEntry> = emptyList(),
        occasions: List<Occasion> = emptyList(),
        settings: ReminderSettings = nothing,
        now: Instant = this.now,
        partnerZone: ZoneId? = null,
    ) = ReminderPlanner.plan(ReminderInput(entries, occasions, settings, "me", zone, now, partnerZone))

    // ---- entries ----

    @Test fun `an all-day day-before reminder fires the day before at the all-day time`() {
        val e = calendarEntry(date = LocalDate.of(2026, 11, 14), reminders = listOf(1440))
        assertEquals(at(2026, 11, 13, 9), plan(listOf(e)).next)
        val due = plan(listOf(e), now = at(2026, 11, 13, 9)).due.single()
        assertEquals("e1|2026-11-14|1440|v1", due.key)
        assertEquals(at(2026, 11, 13, 9), due.fireAt)
        // The all-day time is this phone's setting.
        assertEquals(at(2026, 11, 13, 8, 30), plan(listOf(e), settings = nothing.copy(allDayTime = LocalTime.of(8, 30))).next)
    }

    @Test fun `a late reminder is posted up to six hours late, then dropped`() {
        val e = calendarEntry(date = LocalDate.of(2026, 10, 29), time = LocalTime.of(20, 0), reminders = listOf(60))
        assertEquals(at(2026, 10, 29, 19), plan(listOf(e)).next)
        assertEquals(1, plan(listOf(e), now = at(2026, 10, 30, 0)).due.size) // 5 h late
        assertTrue(plan(listOf(e), now = at(2026, 10, 30, 2)).due.isEmpty()) // 7 h late
    }

    @Test fun `an all-day on-the-day reminder is still posted until eleven at night`() {
        val e = calendarEntry(date = LocalDate.of(2026, 10, 29), reminders = listOf(0, 1440))
        val late = plan(listOf(e), now = at(2026, 10, 29, 22, 30)).due
        assertEquals(listOf("e1|2026-10-29|0|v1"), late.map { it.key })
        assertTrue(plan(listOf(e), now = at(2026, 10, 29, 23, 30)).due.isEmpty())
    }

    @Test fun `a fire time before the entry's last change is skipped`() {
        // Made at 10:00 for 10:10 with "1 hour before": that reminder would already be late.
        val e = calendarEntry(date = LocalDate.of(2026, 10, 29), time = LocalTime.of(10, 10), reminders = listOf(60, 0), updatedAt = now)
        val p = plan(listOf(e), now = now.plusSeconds(30))
        assertTrue(p.due.isEmpty())
        assertEquals(at(2026, 10, 29, 10, 10), p.next)
    }

    @Test fun `an entry moved later the same day after its reminder went off reminds again at the new time`() {
        // "Call mum" at 18:00, 10 minutes before: posted at 17:50 and claimed under its key.
        val call = calendarEntry(title = "Call mum", date = LocalDate.of(2026, 10, 29), time = LocalTime.of(18, 0), reminders = listOf(10))
        val first = plan(listOf(call), now = at(2026, 10, 29, 17, 50)).due.single()
        val claimed = setOf(first.key)
        // At 17:55 Bea moves it to 21:00: the server's copy has a newer version and updatedAt.
        val moved = call.copy(time = LocalTime.of(21, 0), version = 2, updatedAt = at(2026, 10, 29, 17, 55))
        assertTrue(plan(listOf(moved), now = at(2026, 10, 29, 17, 56)).due.isEmpty())
        assertEquals(at(2026, 10, 29, 20, 50), plan(listOf(moved), now = at(2026, 10, 29, 17, 56)).next)
        val again = plan(listOf(moved), now = at(2026, 10, 29, 20, 50)).due.single()
        assertTrue("the new reminder must not be taken for the one already posted", again.key !in claimed)
        assertEquals(at(2026, 10, 29, 20, 50), again.fireAt)
        // Posted once: the next run finds its key claimed, like any other.
        assertEquals(again.key, plan(listOf(moved), now = at(2026, 10, 29, 20, 51)).due.single().key)
    }

    @Test fun `an edit after a reminder went off does not post that reminder again under the new version`() {
        val call = calendarEntry(title = "Call mum", date = LocalDate.of(2026, 10, 29), time = LocalTime.of(18, 0), reminders = listOf(10))
        assertEquals(1, plan(listOf(call), now = at(2026, 10, 29, 17, 50)).due.size)
        // Renamed at 17:55, the time unchanged: the 17:50 reminder is before the change, so it is skipped.
        val renamed = call.copy(title = "Call mum and dad", version = 2, updatedAt = at(2026, 10, 29, 17, 55))
        assertTrue(plan(listOf(renamed), now = at(2026, 10, 29, 17, 56)).due.isEmpty())
        // An all-day "on the day" given a time later that day, "at the time": it reminds at that time.
        val allDay = calendarEntry(date = LocalDate.of(2026, 10, 29), reminders = listOf(0))
        val dayKey = plan(listOf(allDay), now = at(2026, 10, 29, 9)).due.single().key
        val timed = allDay.copy(time = LocalTime.of(19, 0), timeZone = "Asia/Kolkata", version = 2, updatedAt = at(2026, 10, 29, 12))
        val atTime = plan(listOf(timed), now = at(2026, 10, 29, 19)).due.single()
        assertTrue(atTime.key != dayKey)
    }

    @Test fun `nothing from before the reminders were first set up`() {
        // Due at 09:30, half an hour ago; the reminders were first set up at 09:45.
        val e = calendarEntry(date = LocalDate.of(2026, 10, 29), time = LocalTime.of(10, 30), reminders = listOf(60))
        assertEquals(1, plan(listOf(e)).due.size)
        assertTrue(plan(listOf(e), settings = nothing.copy(since = at(2026, 10, 29, 9, 45))).due.isEmpty())
    }

    @Test fun `only the phones in remindUserIds remind`() {
        val e = calendarEntry(date = LocalDate.of(2026, 11, 1), reminders = listOf(0), remindUserIds = listOf("bea"))
        val p = plan(listOf(e))
        assertTrue(p.due.isEmpty())
        assertNull(p.next)
    }

    @Test fun `done to-dos and entries from a newer app never remind`() {
        val done = calendarEntry(id = "t", kind = CalendarKind.TODO, date = LocalDate.of(2026, 11, 1), reminders = listOf(0), done = true)
        val unknown = calendarEntry(id = "u", kind = CalendarKind.UNKNOWN, date = LocalDate.of(2026, 11, 1), reminders = listOf(0))
        assertNull(plan(listOf(done, unknown)).next)
        val open = done.copy(done = false)
        assertEquals(at(2026, 11, 1, 9), plan(listOf(open)).next)
    }

    @Test fun `the master switch silences everything`() {
        val e = calendarEntry(date = LocalDate.of(2026, 10, 29), reminders = listOf(0))
        val p = plan(listOf(e), settings = nothing.copy(enabled = false))
        assertTrue(p.due.isEmpty())
        assertNull(p.next)
    }

    @Test fun `a weekly entry reminds each week until repeatUntil`() {
        val e = calendarEntry(date = LocalDate.of(2026, 10, 4), time = LocalTime.of(21, 0), repeat = CalendarRepeat.WEEKLY, repeatUntil = LocalDate.of(2026, 11, 8), reminders = listOf(30))
        assertEquals(at(2026, 11, 1, 20, 30), plan(listOf(e)).next)
        assertEquals(at(2026, 11, 8, 20, 30), plan(listOf(e), now = at(2026, 11, 2, 0)).next)
        assertNull(plan(listOf(e), now = at(2026, 11, 9, 0)).next)
    }

    @Test fun `an anchored entry reminds at its instant, not at the same clock time here`() {
        val e = calendarEntry(date = LocalDate.of(2026, 11, 1), time = LocalTime.of(21, 0), timeZone = "Europe/London", reminders = listOf(0))
        assertEquals(Instant.parse("2026-11-01T21:00:00Z"), plan(listOf(e)).next)
    }

    @Test fun `several entries - the soonest is next, every due one is posted in order`() {
        val a = calendarEntry(id = "a", date = LocalDate.of(2026, 10, 29), time = LocalTime.of(9, 50), reminders = listOf(0))
        val b = calendarEntry(id = "b", date = LocalDate.of(2026, 10, 29), time = LocalTime.of(9, 40), reminders = listOf(0))
        val c = calendarEntry(id = "c", date = LocalDate.of(2026, 10, 30), reminders = listOf(0))
        val d = calendarEntry(id = "d", date = LocalDate.of(2026, 10, 29), time = LocalTime.of(18, 0), reminders = listOf(0))
        val p = plan(listOf(a, b, c, d))
        assertEquals(listOf("b", "a"), p.due.map { it.entry!!.id })
        assertEquals(at(2026, 10, 29, 18), p.next)
    }

    @Test fun `nothing beyond the 400-day horizon`() {
        val far = calendarEntry(date = LocalDate.of(2028, 1, 1), reminders = listOf(0))
        assertNull(plan(listOf(far)).next)
    }

    // ---- occasions ----

    private val couple = Couple("c", CoupleStatus.ACTIVE, listOf("me", "bea"), LocalDate.of(2023, 11, 14), null, null, Instant.EPOCH)
    private val bea = Partner("bea", "Bea", null, birthday = "1999-11-05", timeZone = "Europe/London")

    @Test fun `default occasion reminders - the partner's birthday and the anniversary`() {
        val occasions = CalendarOccasions.derive(couple, "me", "2000-12-01", bea, emptyList())
        val p = plan(occasions = occasions, settings = ReminderSettings())
        // 7 days before Bea's birthday was today at 09:00, an hour ago.
        assertEquals(listOf("occ:birthday:bea|2026-11-05|10080"), p.due.map { it.key })
        assertEquals(at(2026, 11, 4, 9), p.next)
        // My birthday (1 December) is off by default: after the anniversary, next is a week before Bea's next one.
        val dec = plan(occasions = occasions, settings = ReminderSettings(), now = at(2026, 11, 30, 10))
        assertTrue(dec.due.isEmpty())
        assertEquals(at(2027, 10, 29, 9), dec.next)
    }

    @Test fun `the anniversary reminds a week before and on the day`() {
        val occasions = CalendarOccasions.derive(couple, "me", null, null, emptyList())
        val p = plan(occasions = occasions, settings = ReminderSettings(), now = at(2026, 11, 7, 9, 30))
        assertEquals(listOf("occ:anniversary|2026-11-14|10080"), p.due.map { it.key })
        assertEquals(at(2026, 11, 14, 9), p.next)
    }

    @Test fun `midnight where the partner is, when asked for`() {
        val occasions = CalendarOccasions.derive(null, "me", null, bea, emptyList())
        val settings = ReminderSettings(partnerBirthday = OccasionReminders(true, emptyList()), anniversary = OccasionReminders(false, emptyList()), partnerMidnight = true)
        assertEquals(Instant.parse("2026-11-05T00:00:00Z"), plan(occasions = occasions, settings = settings, partnerZone = ZoneId.of("Europe/London")).next)
        assertNull(plan(occasions = occasions, settings = settings, partnerZone = null).next)
        // On one clock their midnight is this phone's own: no reminder "for them".
        assertNull(plan(occasions = occasions, settings = settings, partnerZone = zone).next)
        assertNull(plan(occasions = occasions, settings = settings, partnerZone = ZoneId.of("Asia/Calcutta")).next)
    }

    @Test fun `only yearly milestones remind, and only when switched on`() {
        val milestones = listOf(
            Milestone("y", "c", "me", "First kiss", LocalDate.of(2024, 11, 2), MilestoneKind.ANNIVERSARY, Instant.EPOCH),
            Milestone("o", "c", "me", "Trip", LocalDate.of(2026, 11, 1), MilestoneKind.CUSTOM, Instant.EPOCH),
        )
        val occasions = CalendarOccasions.derive(null, "me", null, null, milestones)
        assertNull(plan(occasions = occasions, settings = ReminderSettings()).next)
        val on = ReminderSettings(milestones = OccasionReminders(true, listOf(0)))
        assertEquals(at(2026, 11, 2, 9), plan(occasions = occasions, settings = on).next)
    }

    // ---- exact or not ----

    @Test fun `exact alarms below Android 12, or once allowed`() {
        assertTrue(ReminderPlanner.useExact(30, canScheduleExact = false))
        assertFalse(ReminderPlanner.useExact(31, canScheduleExact = false))
        assertTrue(ReminderPlanner.useExact(31, canScheduleExact = true))
        assertFalse(ReminderPlanner.useExact(35, canScheduleExact = false))
    }

    // ---- words ----

    @Test fun `reminder titles and lines`() {
        val uk = Locale.UK
        val occasions = CalendarOccasions.derive(null, "me", null, bea, emptyList())
        val birthday = plan(occasions = occasions, settings = ReminderSettings(partnerBirthday = OccasionReminders(true, listOf(1))), now = at(2026, 11, 4, 9)).due.single()
        assertEquals("Tomorrow · Bea's birthday · turning 27 🎂", ReminderText.title(birthday, at(2026, 11, 4, 9), zone, uk))
        assertEquals("Thu 5 Nov", ReminderText.body(birthday, zone, "Bea Rao", ZoneId.of("Europe/London"), uk))

        val call = calendarEntry(title = "Call Bea", date = LocalDate.of(2026, 10, 29), time = LocalTime.of(20, 0), timeZone = "Asia/Kolkata", reminders = listOf(60))
        val r = plan(listOf(call), now = at(2026, 10, 29, 19)).due.single()
        assertEquals("In 1 hour · Call Bea", ReminderText.title(r, at(2026, 10, 29, 19), zone, uk))
        assertEquals("8:00 pm · 2:30 pm for Bea", ReminderText.body(r, zone, "Bea Rao", ZoneId.of("Europe/London"), uk))
        assertEquals("8:00 pm", ReminderText.body(r, zone, "Bea Rao", ZoneId.of("Asia/Calcutta"), uk))
        assertEquals("In 10 min · Call Bea", ReminderText.title(r, at(2026, 10, 29, 19, 50), zone, uk))
        assertEquals("Now · Call Bea", ReminderText.title(r, at(2026, 10, 29, 20, 1), zone, uk))

        val trip = calendarEntry(title = "Goa", emoji = "🏖️", date = LocalDate.of(2026, 11, 13), endDate = LocalDate.of(2026, 11, 15), reminders = listOf(0))
        val t = plan(listOf(trip), now = at(2026, 11, 13, 9)).due.single()
        assertEquals("Today · Goa 🏖️", ReminderText.title(t, at(2026, 11, 13, 9), zone, uk))
        assertEquals("Fri 13 Nov – Sun 15 Nov", ReminderText.body(t, zone, null, null, uk))

        val todo = calendarEntry(title = "Book tickets", kind = CalendarKind.TODO, date = LocalDate.of(2026, 11, 14), reminders = listOf(0))
        val td = plan(listOf(todo), now = at(2026, 11, 14, 9)).due.single()
        assertEquals("To-do · due Sat 14 Nov", ReminderText.body(td, zone, null, null, uk))
    }
}
