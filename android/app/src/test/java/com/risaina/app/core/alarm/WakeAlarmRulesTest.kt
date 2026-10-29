package com.risaina.app.core.alarm

import com.risaina.app.core.model.AlarmMember
import com.risaina.app.core.model.AlarmWake
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.model.WakeMode
import com.risaina.app.core.model.WakeOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The next ring, daylight saving, the late guard, re-arming and the words around them. */
class WakeAlarmRulesTest {
    private val uk = Locale.UK
    private val india = ZoneId.of("Asia/Kolkata")
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")
    private val la = ZoneId.of("America/Los_Angeles")

    private fun member(id: String, ring: Boolean = true, skips: List<Instant> = emptyList(), ready: Boolean? = true, reason: String? = null, armedFor: Instant? = null, lastWake: AlarmWake? = null) =
        AlarmMember(id, ring, skips, ready, reason, armedFor, null, lastWake)

    private fun alarm(
        time: String = "06:30", zone: String = "Asia/Kolkata", days: List<Int> = listOf(1, 2, 3, 4, 5), onceAt: Instant? = null,
        enabled: Boolean = true, mode: WakeMode = WakeMode.TOGETHER, label: String? = null,
        members: List<AlarmMember> = listOf(member("me"), member("bea")),
    ) = WakeAlarm(
        id = "a1", coupleId = "c", createdBy = "bea", updatedBy = "bea", mode = mode, label = label, time = LocalTime.parse(time), timeZone = zone,
        days = days, onceAt = onceAt, enabled = enabled, snoozeMinutes = 10, version = 1, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, members = members,
    )

    private fun local(date: String, time: String, zone: ZoneId): Instant = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()
    private fun me(a: WakeAlarm) = a.member("me")

    // ---- next ring --------------------------------------------------------------------------

    @Test fun `the next weekday ring, later today or after the weekend`() {
        val a = alarm()
        // Thursday 06:00 in India: this morning at 06:30.
        assertEquals(Instant.parse("2026-10-29T01:00:00Z"), WakeAlarmRules.next(a, me(a), local("2026-10-29", "06:00", india)))
        // Friday 07:00: Monday (the week wraps past Saturday and Sunday).
        assertEquals(Instant.parse("2026-11-02T01:00:00Z"), WakeAlarmRules.next(a, me(a), local("2026-10-30", "07:00", india)))
        // Exactly at the ring: the next one, never the same one again.
        assertEquals(Instant.parse("2026-10-30T01:00:00Z"), WakeAlarmRules.next(a, me(a), Instant.parse("2026-10-29T01:00:00Z")))
    }

    @Test fun `a phone in another zone works out the same instant`() {
        val a = alarm()
        val now = Instant.parse("2026-10-29T00:00:00Z")
        // Nothing here depends on the phone's zone: both phones ring at 01:00 UTC.
        assertEquals(Instant.parse("2026-10-29T01:00:00Z"), WakeAlarmRules.next(a, me(a), now))
        assertEquals("1:00 am", WakeAlarmRules.timeText(Instant.parse("2026-10-29T01:00:00Z"), london, uk))
        assertEquals("6:30 am", WakeAlarmRules.timeText(Instant.parse("2026-10-29T01:00:00Z"), india, uk))
    }

    @Test fun `a one-off alarm rings once, in the future only`() {
        val at = Instant.parse("2026-11-05T01:00:00Z")
        val a = alarm(days = emptyList(), onceAt = at)
        assertEquals(at, WakeAlarmRules.next(a, me(a), Instant.parse("2026-11-01T00:00:00Z")))
        assertNull(WakeAlarmRules.next(a, me(a), at))
        assertNull(WakeAlarmRules.next(a, me(a), Instant.parse("2026-11-06T00:00:00Z")))
    }

    @Test fun `a skipped ring is passed over on that phone only`() {
        val skipped = Instant.parse("2026-10-30T01:00:00Z")
        val a = alarm(members = listOf(member("me", skips = listOf(skipped)), member("bea")))
        val now = local("2026-10-29", "08:00", india)
        assertEquals(Instant.parse("2026-11-02T01:00:00Z"), WakeAlarmRules.next(a, a.member("me"), now))
        assertEquals(skipped, WakeAlarmRules.next(a, a.member("bea"), now))
    }

    @Test fun `off, off on this phone, a later mode or an unknown zone never ring`() {
        val now = Instant.parse("2026-10-29T00:00:00Z")
        val off = alarm(enabled = false)
        assertNull(WakeAlarmRules.next(off, me(off), now))
        assertEquals(Instant.parse("2026-10-29T01:00:00Z"), WakeAlarmRules.nextOfRule(off, now))
        val notMine = alarm(members = listOf(member("me", ring = false), member("bea")))
        assertNull(WakeAlarmRules.next(notMine, notMine.member("me"), now))
        assertNull(WakeAlarmRules.next(alarm(), null, now))
        val later = alarm(mode = WakeMode.UNKNOWN)
        assertNull(WakeAlarmRules.next(later, me(later), now))
        assertFalse(WakeAlarmRules.canSchedule(alarm(zone = "Mars/Olympus")))
    }

    @Test fun `seven skips in a row still find the next ring`() {
        val a0 = alarm(days = listOf(1))
        val now = Instant.parse("2026-10-29T00:00:00Z")
        var after = now
        val skips = (1..7).map { WakeAlarmRules.nextOfRule(a0, after)!!.also { after = it } }
        val a = a0.copy(members = listOf(member("me", skips = skips)))
        assertEquals(WakeAlarmRules.nextOfRule(a0, skips.last()), WakeAlarmRules.next(a, a.member("me"), now))
    }

    // ---- daylight saving --------------------------------------------------------------------

    @Test fun `New York spring forward moves a ring in the gap forward by an hour`() {
        val a = alarm(time = "02:30", zone = "America/New_York", days = (1..7).toList())
        val next = WakeAlarmRules.next(a, me(a), local("2027-03-13", "12:00", newYork))!!
        // 2027-03-14 02:30 does not exist in New York: 03:30 EDT.
        assertEquals(Instant.parse("2027-03-14T07:30:00Z"), next)
        assertEquals("3:30 am", WakeAlarmRules.timeText(next, newYork, uk))
        assertTrue(WakeAlarmRules.isOccurrence(a, next))
        // The day after, 02:30 EDT again.
        assertEquals(Instant.parse("2027-03-15T06:30:00Z"), WakeAlarmRules.next(a, me(a), next))
    }

    @Test fun `New York fall back takes the earlier 01 30`() {
        val a = alarm(time = "01:30", zone = "America/New_York", days = (1..7).toList())
        val next = WakeAlarmRules.next(a, me(a), local("2026-10-31", "12:00", newYork))!!
        // 2026-11-01 01:30 happens twice: the first one, EDT (-4).
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), next)
        // and not again an hour later
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), WakeAlarmRules.next(a, me(a), next))
    }

    @Test fun `London spring forward and fall back`() {
        val a = alarm(time = "01:30", zone = "Europe/London", days = (1..7).toList())
        // 2027-03-28 01:30 is in London's gap (01:00 → 02:00): 02:30 BST.
        assertEquals(Instant.parse("2027-03-28T01:30:00Z"), WakeAlarmRules.next(a, me(a), local("2027-03-27", "12:00", london)))
        // 2026-10-25 01:30 happens twice: the earlier one, BST.
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), WakeAlarmRules.next(a, me(a), local("2026-10-24", "12:00", london)))
    }

    @Test fun `an India alarm moves an hour on a London phone when London changes the clocks`() {
        val a = alarm(time = "06:30", zone = "Asia/Kolkata", days = (1..7).toList())
        val before = WakeAlarmRules.next(a, me(a), local("2027-03-25", "12:00", india))!!
        assertEquals("1:00 am", WakeAlarmRules.timeText(before, london, uk))
        val after = WakeAlarmRules.next(a, me(a), local("2027-03-28", "12:00", india))!!
        assertEquals("2:00 am", WakeAlarmRules.timeText(after, london, uk))
        // Still 06:30 in India, the same instant on both phones.
        assertEquals("6:30 am", WakeAlarmRules.timeText(after, india, uk))
    }

    @Test fun `the weekdays a ring falls on across the date line`() {
        val a = alarm(time = "06:30", zone = "Asia/Kolkata", days = listOf(1, 2, 3, 4, 5))
        val now = Instant.parse("2026-10-29T00:00:00Z")
        // 06:30 in India is the evening before in Los Angeles: Sunday to Thursday there.
        assertEquals(listOf(1, 2, 3, 4, 7), WakeAlarmRules.localDays(a, la, now))
        assertEquals(listOf(1, 2, 3, 4, 5), WakeAlarmRules.localDays(a, india, now))
        assertEquals(emptyList<Int>(), WakeAlarmRules.localDays(alarm(days = emptyList(), onceAt = now), india, now))
    }

    // ---- ringing, late, re-arming -----------------------------------------------------------

    @Test fun `it rings up to 30 minutes late and not after`() {
        val occ = Instant.parse("2026-10-29T01:00:00Z")
        assertTrue(WakeAlarmRules.shouldRing(occ, occ))
        assertTrue(WakeAlarmRules.shouldRing(occ, occ.plusSeconds(29 * 60)))
        assertFalse(WakeAlarmRules.shouldRing(occ, occ.plusSeconds(31 * 60)))
        assertTrue(WakeAlarmRules.shouldRing(occ, occ.minusSeconds(30)))
        assertFalse(WakeAlarmRules.shouldRing(occ, occ.minusSeconds(120)))
    }

    @Test fun `a fire rings only for a live, unskipped, unrung occurrence`() {
        val occ = Instant.parse("2026-10-29T01:00:00Z")
        val a = alarm()
        assertEquals(FireDecision.RING, WakeAlarmRules.fireDecision(a, me(a), occ, occ.plusSeconds(2), null))
        assertEquals(FireDecision.MISSED, WakeAlarmRules.fireDecision(a, me(a), occ, occ.plusSeconds(45 * 60), null))
        assertEquals(FireDecision.EARLY, WakeAlarmRules.fireDecision(a, me(a), occ, occ.minusSeconds(600), null))
        assertEquals(FireDecision.IGNORE, WakeAlarmRules.fireDecision(null, null, occ, occ, null))
        assertEquals(FireDecision.IGNORE, WakeAlarmRules.fireDecision(a, me(a), occ, occ, handled = occ))
        assertEquals(FireDecision.IGNORE, WakeAlarmRules.fireDecision(alarm(enabled = false), me(a), occ, occ, null))
        val skipped = alarm(members = listOf(member("me", skips = listOf(occ))))
        assertEquals(FireDecision.IGNORE, WakeAlarmRules.fireDecision(skipped, skipped.member("me"), occ, occ, null))
        // Edited since it was set: 06:30 is no longer a ring of a 07:00 alarm.
        val edited = alarm(time = "07:00")
        assertEquals(FireDecision.IGNORE, WakeAlarmRules.fireDecision(edited, me(edited), occ, occ, null))
        // Up already (their other phone said so).
        val up = alarm(members = listOf(member("me", lastWake = AlarmWake(occ, WakeOutcome.UP, occ, 0))))
        assertEquals(FireDecision.IGNORE, WakeAlarmRules.fireDecision(up, up.member("me"), occ, occ, null))
    }

    @Test fun `re-arming catches a lost ring, reports a long-lost one, and always sets the next`() {
        val occ = Instant.parse("2026-10-29T01:00:00Z")
        val a = alarm()
        val tomorrow = Instant.parse("2026-10-30T01:00:00Z")
        // The set ring never came (a force-stop dropped it) and it is 5 minutes late: ring it now.
        assertEquals(ArmPlan(ringNow = occ, next = tomorrow), WakeAlarmRules.plan(a, me(a), occ.plusSeconds(300), armed = occ, handled = null))
        // The phone was off for hours: missed.
        assertEquals(ArmPlan(missed = occ, next = tomorrow), WakeAlarmRules.plan(a, me(a), occ.plusSeconds(3 * 3600), armed = occ, handled = null))
        // It rang: nothing to catch up.
        assertEquals(ArmPlan(next = tomorrow), WakeAlarmRules.plan(a, me(a), occ.plusSeconds(300), armed = occ, handled = occ))
        // Still ahead.
        assertEquals(ArmPlan(next = occ), WakeAlarmRules.plan(a, me(a), occ.minusSeconds(300), armed = occ, handled = null))
        // Turned off meanwhile: nothing at all.
        val off = alarm(enabled = false)
        assertEquals(ArmPlan(), WakeAlarmRules.plan(off, me(off), occ.plusSeconds(300), armed = occ, handled = null))
    }

    @Test fun `a ring whose own alarms a reboot or force-stop dropped is set again, rung again or given up`() {
        val start = Instant.parse("2026-10-29T01:00:00Z")
        val ringing = WakeRing("a1", start.toEpochMilli(), WakePhase.RINGING, "Wake together", startedAt = start.toEpochMilli())
        fun at(minutes: Long) = start.plusSeconds(minutes * 60)
        assertEquals(RingRecovery.NONE, WakeAlarmRules.recovery(null, start))
        assertEquals(RingRecovery.KEEP_TIMEOUT, WakeAlarmRules.recovery(ringing, at(5)))
        assertEquals(RingRecovery.TIME_OUT, WakeAlarmRules.recovery(ringing, at(12)))
        assertEquals(RingRecovery.GIVE_UP, WakeAlarmRules.recovery(ringing, at(41)))
        // The test rings for a minute only.
        assertEquals(RingRecovery.TIME_OUT, WakeAlarmRules.recovery(ringing.copy(test = true), at(2)))

        val snoozed = ringing.copy(phase = WakePhase.SNOOZED, until = at(10).toEpochMilli())
        assertEquals(RingRecovery.KEEP_SNOOZE, WakeAlarmRules.recovery(snoozed, at(9)))
        assertEquals(RingRecovery.RING_AGAIN, WakeAlarmRules.recovery(snoozed, at(10)))
        assertEquals(RingRecovery.RING_AGAIN, WakeAlarmRules.recovery(snoozed, at(39)))
        assertEquals(RingRecovery.GIVE_UP, WakeAlarmRules.recovery(snoozed, at(41)))
        assertEquals(RingRecovery.GIVE_UP, WakeAlarmRules.recovery(snoozed.copy(until = null), at(1)))

        // Over: nothing to recover.
        assertEquals(RingRecovery.NONE, WakeAlarmRules.recovery(ringing.copy(phase = WakePhase.UP), at(500)))
        assertEquals(RingRecovery.NONE, WakeAlarmRules.recovery(ringing.copy(phase = WakePhase.MISSED), at(500)))
    }

    // ---- words ------------------------------------------------------------------------------

    @Test fun `days, day and time in words`() {
        assertEquals("Mon–Fri", WakeAlarmRules.daysText(listOf(5, 1, 2, 3, 4), uk))
        assertEquals("Every day", WakeAlarmRules.daysText((1..7).toList(), uk))
        assertEquals("Weekends", WakeAlarmRules.daysText(listOf(6, 7), uk))
        assertEquals("Mon, Wed, Fri", WakeAlarmRules.daysText(listOf(1, 3, 5), uk))
        assertEquals("Tue–Thu", WakeAlarmRules.daysText(listOf(2, 3, 4), uk))
        assertEquals("Once", WakeAlarmRules.daysText(emptyList(), uk))
        val now = local("2026-10-29", "22:00", india)
        assertEquals("Tomorrow 6:30 am", WakeAlarmRules.whenText(Instant.parse("2026-10-30T01:00:00Z"), india, now, uk))
        assertEquals("Today 6:30 am", WakeAlarmRules.whenText(Instant.parse("2026-10-30T01:00:00Z"), india, local("2026-10-30", "01:00", india), uk))
        assertEquals("Mon 2 Nov 6:30 am", WakeAlarmRules.whenText(Instant.parse("2026-11-02T01:00:00Z"), india, now, uk))
        assertEquals("Wake together", WakeAlarmRules.label(alarm(label = "  ")))
        assertEquals("Gym", WakeAlarmRules.label(alarm(label = "Gym")))
    }

    @Test fun `the partner's notice says this phone's time and days`() {
        val now = local("2026-10-29", "22:00", india)
        assertEquals(
            "Raina set Wake together for 6:30 am, Mon–Fri. It rings on your phone too.",
            WakeAlarmRules.noticeText("Raina", alarm(), "created", india, now, uk),
        )
        assertEquals(
            "Raina changed Gym to 6:30 am, Mon–Fri. It rings on your phone too.",
            WakeAlarmRules.noticeText("Raina", alarm(label = "Gym"), "edited", india, now, uk),
        )
        // On a phone in Los Angeles: the evening before, Sunday to Thursday.
        assertEquals(
            "Raina turned on Wake together for 6:00 pm, Mon, Tue, Wed, Thu, Sun. It rings on your phone too.",
            WakeAlarmRules.noticeText("Raina", alarm(), "enabled", la, now, uk),
        )
        val once = alarm(days = emptyList(), onceAt = Instant.parse("2026-10-30T01:00:00Z"))
        assertEquals("Raina set Wake together for 6:30 am tomorrow. It rings on your phone too.", WakeAlarmRules.noticeText("Raina", once, "created", india, now, uk))
        val later = alarm(days = emptyList(), onceAt = Instant.parse("2026-11-06T01:00:00Z"))
        assertEquals("Raina set Wake together for 6:30 am on Fri 6 Nov. It rings on your phone too.", WakeAlarmRules.noticeText("Raina", later, "created", india, now, uk))
    }

    @Test fun `the list says this phone's ring time when it is not the anchor's`() {
        val ring = Instant.parse("2026-10-29T01:00:00Z")
        assertNull(WakeAlarmRules.localLine(alarm(), ring, india, "Raina", locale = uk))
        assertNull(WakeAlarmRules.localLine(alarm(), ring, ZoneId.of("Asia/Calcutta"), "Raina", locale = uk))
        assertEquals("Rings here 1:00 am (6:30 am for Raina)", WakeAlarmRules.localLine(alarm(), ring, london, "Raina", locale = uk))
        assertEquals("Rings here 1:00 am (6:30 am for Raina)", WakeAlarmRules.localLine(alarm(), ring, london, "Raina", india, uk))
        assertEquals("Rings here 1:00 am (6:30 am Kolkata time)", WakeAlarmRules.localLine(alarm(), ring, london, null, locale = uk))
        // Raina set it in Kolkata and is in London with me now: 6:30 am is not her time any more.
        assertEquals("Rings here 1:00 am (6:30 am Kolkata time)", WakeAlarmRules.localLine(alarm(), ring, london, "Raina", london, uk))
        assertEquals("New York", WakeAlarmRules.zoneName(newYork))
    }

    @Test fun `the editor says where an alarm was set only when that clock is not this phone's`() {
        val ring = Instant.parse("2026-10-29T01:00:00Z")
        assertNull(WakeAlarmRules.anchorNote(ring, india, india))
        assertNull(WakeAlarmRules.anchorNote(ring, india, ZoneId.of("Asia/Calcutta")))
        assertEquals("Set in Kolkata time", WakeAlarmRules.anchorNote(ring, india, london))
        // Lisbon and London tell the same time: nothing to say.
        assertNull(WakeAlarmRules.anchorNote(ring, ZoneId.of("Europe/Lisbon"), london))
    }

    @Test fun `the editor shows both clocks when they differ, and warns about the night`() {
        val ring = Instant.parse("2026-10-29T01:00:00Z")
        assertNull(WakeAlarmRules.zoneLines(ring, india, india, india, "Raina", uk))
        assertEquals(
            ZoneLines("6:30 am for you · 1:00 am for Raina", "That's 1:00 am for Raina"),
            WakeAlarmRules.zoneLines(ring, india, india, london, "Raina", uk),
        )
        // Set in London time, seen from India: the warning is about this person's own night.
        val londonRing = Instant.parse("2026-10-28T23:00:00Z")
        assertEquals(
            ZoneLines("4:30 am for you · 11:00 pm for Raina · the day before for them", "That's 4:30 am for you"),
            WakeAlarmRules.zoneLines(londonRing, london, india, london, "Raina", uk),
        )
        assertEquals(
            ZoneLines("6:00 pm for you · 6:30 am for Raina · the next day for them", null),
            WakeAlarmRules.zoneLines(ring, india, la, india, "Raina", uk),
        )
        // Theirs not shared: nothing to compare, unless the alarm was set on another clock.
        assertNull(WakeAlarmRules.zoneLines(ring, india, india, null, "Raina", uk))
        assertEquals(
            ZoneLines("4:30 am for you. Rings at the same moment on both phones", "That's 4:30 am for you"),
            WakeAlarmRules.zoneLines(londonRing, london, india, null, "Raina", uk),
        )
    }

    @Test fun `on one clock the editor shows no second time and no warning for the partner`() {
        val ring = Instant.parse("2026-10-29T01:00:00Z")
        val calcutta = ZoneId.of("Asia/Calcutta")
        assertNull(WakeAlarmRules.zoneLines(ring, india, india, calcutta, "Raina", uk))
        assertNull(WakeAlarmRules.zoneLines(ring, calcutta, india, india, "Raina", uk))
        // A 2:00 am ring on one clock: the time was picked on that clock, nothing to warn about.
        val twoAm = Instant.parse("2026-10-28T20:30:00Z")
        assertNull(WakeAlarmRules.zoneLines(twoAm, india, india, india, "Raina", uk))
        // Set in London, both of you in India now: one line for the two of you.
        val londonRing = Instant.parse("2026-10-29T06:30:00Z")
        assertEquals(ZoneLines("12:00 pm for you both", null), WakeAlarmRules.zoneLines(londonRing, london, india, calcutta, "Raina", uk))
        assertEquals(
            ZoneLines("4:30 am for you both", "That's 4:30 am for you both"),
            WakeAlarmRules.zoneLines(Instant.parse("2026-10-28T23:00:00Z"), london, india, india, "Raina", uk),
        )
    }

    @Test fun `the partner's chip`() {
        val now = Instant.parse("2026-10-29T00:00:00Z")
        val next = Instant.parse("2026-10-29T01:00:00Z")
        fun status(m: AlarmMember, a: WakeAlarm = alarm()) = WakeAlarmRules.memberStatus(a, m, now)
        assertEquals(MemberStatus.READY, status(member("bea", armedFor = next)))
        assertEquals(MemberStatus.NOT_SET_YET, status(member("bea", armedFor = Instant.parse("2026-10-28T01:00:00Z"))))
        assertEquals(MemberStatus.OFF, status(member("bea", ring = false)))
        assertEquals(MemberStatus.OFF, status(member("bea"), alarm(enabled = false)))
        assertEquals(MemberStatus.NEEDS_UPDATE, status(member("bea", ready = null)))
        assertEquals(MemberStatus.PERMISSION_OFF, status(member("bea", ready = false, reason = "exact_alarms_off")))
        assertEquals(MemberStatus.NOTIFICATIONS_OFF, status(member("bea", ready = false, reason = "channel_blocked")))
        assertEquals(MemberStatus.NOT_READY, status(member("bea", ready = false, reason = "something_new")))
        assertEquals(MemberStatus.DONE, status(member("bea"), alarm(days = emptyList(), onceAt = Instant.parse("2026-10-01T01:00:00Z"))))
        assertEquals(MemberStatus.NOT_SET_YET, WakeAlarmRules.memberStatus(alarm(), null, now))
        assertEquals("Off on their phone", WakeAlarmRules.statusText(MemberStatus.OFF, mine = false))
        assertEquals("Not set on their phone yet", WakeAlarmRules.statusText(MemberStatus.NOT_SET_YET, mine = false))
        assertEquals("Needs the new Risaina", WakeAlarmRules.statusText(MemberStatus.NEEDS_UPDATE, mine = false))
    }

    @Test fun `this phone's chip`() {
        val now = Instant.parse("2026-10-29T00:00:00Z")
        val next = Instant.parse("2026-10-29T01:00:00Z")
        val a = alarm()
        assertEquals(MemberStatus.READY, WakeAlarmRules.myStatus(a, me(a), true, null, next, now))
        assertEquals(MemberStatus.NOT_SET_YET, WakeAlarmRules.myStatus(a, me(a), true, null, null, now))
        assertEquals(MemberStatus.PERMISSION_OFF, WakeAlarmRules.myStatus(a, me(a), false, WakeReadiness.EXACT_ALARMS_OFF, next, now))
        assertEquals(MemberStatus.OFF, WakeAlarmRules.myStatus(a, member("me", ring = false), true, null, null, now))
        assertEquals(MemberStatus.NEEDS_UPDATE, WakeAlarmRules.myStatus(alarm(mode = WakeMode.UNKNOWN), me(a), true, null, null, now))
        assertEquals("Off on your phone", WakeAlarmRules.statusText(MemberStatus.OFF, mine = true))
    }

    @Test fun `the ringing screen says whether the partner is up`() {
        val occ = Instant.parse("2026-10-29T01:00:00Z")
        val upAt = Instant.parse("2026-10-29T01:04:00Z")
        assertEquals("Raina is up ☀ 6:34 am", WakeAlarmRules.ringPartnerLine("Raina", member("bea"), occ, upAt, india, uk))
        assertEquals("Raina is up ☀ 6:34 am", WakeAlarmRules.ringPartnerLine("Raina", member("bea", lastWake = AlarmWake(occ, WakeOutcome.UP, upAt, 1)), occ, null, india, uk))
        // An older wake is not this ring's.
        val old = AlarmWake(occ.minusSeconds(86_400), WakeOutcome.UP, upAt.minusSeconds(86_400), 0)
        assertEquals("Raina's alarm is ringing too", WakeAlarmRules.ringPartnerLine("Raina", member("bea", lastWake = old), occ, null, india, uk))
        assertNull(WakeAlarmRules.ringPartnerLine("Raina", member("bea", ring = false), occ, null, india, uk))
        assertNull(WakeAlarmRules.ringPartnerLine("Raina", member("bea", skips = listOf(occ)), occ, null, india, uk))
        assertNull(WakeAlarmRules.partnerUpLine("Raina", member("bea"), occ, null, india, uk))
    }
}
