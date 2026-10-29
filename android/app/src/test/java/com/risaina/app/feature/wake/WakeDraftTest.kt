package com.risaina.app.feature.wake

import com.risaina.app.core.model.AlarmMember
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.model.WakeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test fun `the editor's ring is the next of the chosen days`() {
        val d = WakeDrafts.new(india, now)
        assertEquals(Instant.parse("2026-10-30T01:00:00Z"), WakeDrafts.nextRing(d, now))
        // Weekends only: Saturday.
        assertEquals(Instant.parse("2026-10-31T01:00:00Z"), WakeDrafts.nextRing(d.copy(days = setOf(6, 7)), now))
        assertEquals(Instant.parse("2026-10-30T01:00:00Z"), WakeDrafts.nextRing(d.copy(days = emptySet()), now))
    }
}
