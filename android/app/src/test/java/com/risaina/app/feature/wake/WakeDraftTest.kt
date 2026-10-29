package com.risaina.app.feature.wake

import com.risaina.app.core.model.AlarmMember
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.model.WakeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The editor: defaults, a one-off's instant, what can be saved, and the PATCH it sends. */
class WakeDraftTest {
    private val india = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-29T03:00:00Z") // Thursday 08:30 in India

    private fun alarm(days: List<Int> = listOf(1, 2, 3, 4, 5), onceAt: Instant? = null, time: String = "06:30", label: String? = null) = WakeAlarm(
        id = "a1", coupleId = "c", createdBy = "bea", updatedBy = null, mode = WakeMode.TOGETHER, label = label, time = LocalTime.parse(time),
        timeZone = "Asia/Kolkata", days = days, onceAt = onceAt, enabled = true, snoozeMinutes = 10, version = 4,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        members = listOf(AlarmMember("me", false, emptyList(), true, null, null, null, null), AlarmMember("bea", true, emptyList(), true, null, null, null, null)),
    )

    @Test fun `a new alarm is 6 30 on weekdays here, once meaning the next 6 30 ahead`() {
        val d = WakeDrafts.new(india, now)
        assertEquals("Asia/Kolkata", d.zone)
        assertEquals(LocalTime.of(6, 30), d.time)
        assertEquals(setOf(1, 2, 3, 4, 5), d.days)
        // 08:30 now: today's 06:30 has passed.
        assertEquals(LocalDate.of(2026, 10, 30), d.date)
        assertEquals(LocalDate.of(2026, 10, 29), WakeDrafts.defaultDate(LocalTime.of(9, 0), india, now))
    }

    @Test fun `editing starts from the alarm, in its own zone, with my switch`() {
        val d = WakeDrafts.of(alarm(label = "Gym"), "me", now)
        assertEquals("a1", d.id)
        assertEquals(4, d.version)
        assertEquals("Gym", d.label)
        assertEquals(false, d.ringOnMyPhone)
        assertEquals("Asia/Kolkata", d.zone)
        val once = WakeDrafts.of(alarm(days = emptyList(), onceAt = Instant.parse("2026-11-05T01:00:00Z")), "me", now)
        assertEquals(LocalDate.of(2026, 11, 5), once.date)
    }

    @Test fun `a one-off in a spring-forward gap is sent at its real time`() {
        val d = WakeDraft(time = LocalTime.of(2, 30), days = emptySet(), date = LocalDate.of(2027, 3, 14), zone = "America/New_York")
        assertEquals(Instant.parse("2027-03-14T07:30:00Z"), WakeDrafts.onceAt(d))
        assertEquals(LocalTime.of(3, 30), WakeDrafts.wireTime(d))
        assertNull(WakeDrafts.onceAt(d.copy(days = setOf(1))))
        assertEquals(LocalTime.of(2, 30), WakeDrafts.wireTime(d.copy(days = setOf(1))))
    }

    @Test fun `what cannot be saved says why`() {
        val ok = WakeDrafts.new(india, now)
        assertNull(WakeDrafts.problem(ok, now))
        assertNull(WakeDrafts.problem(ok.copy(days = emptySet()), now))
        assertNotNull(WakeDrafts.problem(ok.copy(days = emptySet(), date = LocalDate.of(2026, 10, 29)), now))
        assertNotNull(WakeDrafts.problem(ok.copy(days = emptySet(), date = LocalDate.of(2027, 12, 1)), now))
        assertNotNull(WakeDrafts.problem(ok.copy(days = emptySet(), date = null), now))
        assertNotNull(WakeDrafts.problem(ok.copy(label = "x".repeat(41)), now))
        assertNotNull(WakeDrafts.problem(ok.copy(zone = "Nowhere/Land"), now))
    }

    @Test fun `the patch sends only what changed, at the version the editor started from`() {
        val a = alarm(label = "Gym")
        val d = WakeDrafts.of(a, "me", now)
        assertNull(WakeDrafts.patch(a, d))
        // The ring switch is this person's own, not part of the shared alarm.
        assertNull(WakeDrafts.patch(a, d.copy(ringOnMyPhone = true)))
        val p = WakeDrafts.patch(a, d.copy(time = LocalTime.of(7, 0), snoozeMinutes = 5, label = " "))!!
        assertEquals(4, p.expectedVersion)
        assertEquals("07:00", p.time)
        assertEquals(5, p.snoozeMinutes)
        assertEquals(setOf("label"), p.clear)
        assertNull(p.days)
        assertNull(p.onceAt)
        assertEquals("Run", WakeDrafts.patch(a, d.copy(label = "Run"))!!.label)
    }

    @Test fun `switching between once and repeating sends days and onceAt together`() {
        val weekly = alarm()
        val toOnce = WakeDrafts.patch(weekly, WakeDrafts.of(weekly, "me", now).copy(days = emptySet(), date = LocalDate.of(2026, 11, 5)))!!
        assertEquals(emptyList<Int>(), toOnce.days)
        assertEquals("2026-11-05T01:00:00Z", toOnce.onceAt)

        val once = alarm(days = emptyList(), onceAt = Instant.parse("2026-11-05T01:00:00Z"))
        val toWeekly = WakeDrafts.patch(once, WakeDrafts.of(once, "me", now).copy(days = setOf(6, 7)))!!
        assertEquals(listOf(6, 7), toWeekly.days)
        assertEquals(setOf("onceAt"), toWeekly.clear)

        val moved = WakeDrafts.patch(once, WakeDrafts.of(once, "me", now).copy(date = LocalDate.of(2026, 11, 6)))!!
        assertEquals("2026-11-06T01:00:00Z", moved.onceAt)
        assertNull(moved.time)
        val later = WakeDrafts.patch(once, WakeDrafts.of(once, "me", now).copy(time = LocalTime.of(7, 0)))!!
        assertEquals("07:00", later.time)
        assertEquals("2026-11-05T01:30:00Z", later.onceAt)
    }

    @Test fun `a conflict from a readiness report keeps my edit and sends it again at the new version`() {
        // Opened at v4; the partner's phone then reported ready (v5): no shared field moved.
        val base = alarm()
        val start = WakeDrafts.of(base, "me", now)
        val mine = start.copy(time = LocalTime.of(6, 15))
        val newer = base.copy(version = 5, members = base.members.map { if (it.userId == "bea") it.copy(ready = false, reason = "exact_alarms_off") else it })
        val rb = WakeDrafts.rebase(base, start, newer, mine, "me", now)
        assertTrue(rb.retry)
        assertEquals(emptyList<SharedField>(), rb.changed)
        assertEquals(LocalTime.of(6, 15), rb.draft.time)
        assertEquals(5, rb.draft.version)
        val p = WakeDrafts.patch(newer, rb.draft)!!
        assertEquals(5, p.expectedVersion)
        assertEquals("06:15", p.time)
    }

    @Test fun `a conflict from the partner's edit keeps my fields, takes theirs, and asks again`() {
        val base = alarm()
        val start = WakeDrafts.of(base, "me", now)
        val mine = start.copy(time = LocalTime.of(6, 15))
        // Raina named it meanwhile: my time stays, their name is taken, not undone.
        val named = base.copy(version = 5, label = "Gym")
        val rb = WakeDrafts.rebase(base, start, named, mine, "me", now)
        assertFalse(rb.retry)
        assertEquals(listOf(SharedField.NAME), rb.changed)
        assertEquals(LocalTime.of(6, 15), rb.draft.time)
        assertEquals("Gym", rb.draft.label)
        val p = WakeDrafts.patch(named, rb.draft)!!
        assertEquals(5, p.expectedVersion)
        assertEquals("06:15", p.time)
        assertNull(p.label)
        assertEquals(emptySet<String>(), p.clear)
        // Both changed the time: mine is kept for me to look at; the next catch-up measures from theirs.
        val moved = base.copy(version = 5, time = LocalTime.of(7, 0))
        val both = WakeDrafts.rebase(base, start, moved, mine, "me", now)
        assertEquals(listOf(SharedField.TIME), both.changed)
        assertEquals(LocalTime.of(6, 15), both.draft.time)
        assertEquals(LocalTime.of(7, 0), both.start.time)
        // My own ring switch is never the partner's to change.
        assertEquals(true, WakeDrafts.rebase(base, start, named, mine.copy(ringOnMyPhone = true), "me", now).draft.ringOnMyPhone)
    }

    @Test fun `what moved on is named the way the editor shows it`() {
        val once = alarm(days = emptyList(), onceAt = Instant.parse("2026-11-05T01:00:00Z"))
        // A one-off's later time moves onceAt on the same day: the time, not the day.
        assertEquals(listOf(SharedField.TIME), WakeDrafts.sharedChanges(once, once.copy(time = LocalTime.of(7, 0), onceAt = Instant.parse("2026-11-05T01:30:00Z"))))
        assertEquals(listOf(SharedField.DAY), WakeDrafts.sharedChanges(once, once.copy(onceAt = Instant.parse("2026-11-06T01:00:00Z"))))
        assertEquals(listOf(SharedField.DAYS), WakeDrafts.sharedChanges(once, once.copy(days = listOf(6, 7), onceAt = null)))
        val a = alarm()
        assertEquals(emptyList<SharedField>(), WakeDrafts.sharedChanges(a, a.copy(version = 9, updatedBy = "bea")))
        assertEquals(
            "Raina changed the time and the name meanwhile. Your changes are still here. Have a look and save again.",
            WakeDrafts.conflictText("Raina", listOf(SharedField.TIME, SharedField.NAME), enabledNow = true),
        )
        assertEquals(
            "Raina changed the days, the snooze and the time zone, and turned it off meanwhile. Your changes are still here. Have a look and save again.",
            WakeDrafts.conflictText("Raina", listOf(SharedField.DAYS, SharedField.SNOOZE, SharedField.ZONE, SharedField.ON_OFF), enabledNow = false),
        )
        assertTrue(WakeDrafts.conflictText("Raina", listOf(SharedField.ON_OFF), enabledNow = false).startsWith("Raina turned it off meanwhile."))
    }

    @Test fun `the editor's ring is the next of the chosen days`() {
        val d = WakeDrafts.new(india, now)
        assertEquals(Instant.parse("2026-10-30T01:00:00Z"), WakeDrafts.nextRing(d, now))
        // Weekends only: Saturday.
        assertEquals(Instant.parse("2026-10-31T01:00:00Z"), WakeDrafts.nextRing(d.copy(days = setOf(6, 7)), now))
        assertEquals(Instant.parse("2026-10-30T01:00:00Z"), WakeDrafts.nextRing(d.copy(days = emptySet()), now))
    }
}
