package com.risaina.app.feature.wake

import com.risaina.app.core.alarm.WakeLocal
import com.risaina.app.core.alarm.WakePhase
import com.risaina.app.core.alarm.WakeReadyReport
import com.risaina.app.core.alarm.WakeRing
import com.risaina.app.core.network.AlarmDto
import com.risaina.app.core.network.AlarmMemberDto
import com.risaina.app.core.network.AlarmWakeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The Home card's alarm and its one line. */
class WakeHomeTest {
    private val india = ZoneId.of("Asia/Kolkata")
    private val uk = Locale.UK
    private val now = Instant.parse("2026-10-29T16:30:00Z") // Thursday 22:00 in India
    private val occ = Instant.parse("2026-10-30T01:00:00Z")
    private val ready = WakeReadyReport(true, null)

    private fun dto(id: String = "a1", time: String = "06:30", enabled: Boolean = true, myRing: Boolean = true, bea: AlarmMemberDto = AlarmMemberDto("bea", ready = true, armedFor = occ.toString())) = AlarmDto(
        id = id, createdBy = "bea", time = time, timeZone = "Asia/Kolkata", days = listOf(1, 2, 3, 4, 5), enabled = enabled,
        createdAt = "2026-10-29T10:00:00.000Z", updatedAt = "2026-10-29T10:00:00.000Z",
        members = listOf(AlarmMemberDto("me", ring = myRing), bea),
    )

    private fun local(vararg alarms: AlarmDto, ring: WakeRing? = null, armed: Map<String, Long> = mapOf("a1" to occ.toEpochMilli())) =
        WakeLocal(me = "me", partnerName = "Raina", alarms = alarms.toList(), armed = armed, ring = ring)

    private fun line(l: WakeLocal) = WakeHome.line(WakeHome.pick(l, now)!!, l, ready, now, india, uk)

    @Test fun `no alarm, no card`() {
        assertNull(WakeHome.pick(WakeLocal(me = "me"), now))
    }

    @Test fun `the card is about the soonest alarm, or the one ringing here`() {
        val l = local(dto("late", time = "08:00"), dto("a1"), dto("off", time = "05:00", enabled = false))
        assertEquals("a1", WakeHome.pick(l, now)!!.id)
        val ringing = l.copy(ring = WakeRing("late", occ.toEpochMilli(), WakePhase.SNOOZED, "Wake together", startedAt = 0, until = 1))
        assertEquals("late", WakeHome.pick(ringing, now)!!.id)
        assertEquals("off", WakeHome.pick(local(dto("off", enabled = false)), now)!!.id)
    }

    @Test fun `the next ring and whose phones are set`() {
        assertEquals("Tomorrow 6:30 am · both phones ✓", line(local(dto())))
        assertEquals("Tomorrow 6:30 am · your phone only", line(local(dto(bea = AlarmMemberDto("bea", ring = false)))))
        assertEquals("Tomorrow 6:30 am · only Raina's phone", line(local(dto(myRing = false), armed = emptyMap())))
        assertEquals("Tomorrow 6:30 am · Raina needs the new Risaina", line(local(dto(bea = AlarmMemberDto("bea")))))
        assertEquals("Tomorrow 6:30 am · not set on Raina's phone yet", line(local(dto(bea = AlarmMemberDto("bea", ready = true)))))
        assertEquals("Tomorrow 6:30 am · check your phone", WakeHome.line(WakeHome.pick(local(dto()), now)!!, local(dto()), WakeReadyReport(false, "exact_alarms_off"), now, india, uk))
        assertEquals("Off", line(local(dto(enabled = false))))
    }

    @Test fun `in the morning it says who is up`() {
        val upAt = occ.plusSeconds(240)
        val beaUp = AlarmMemberDto("bea", ready = true, lastWake = AlarmWakeDto(occ.toString(), "up", upAt.toString(), 0))
        val morning = occ.plusSeconds(600)
        fun at(l: WakeLocal) = WakeHome.line(WakeHome.pick(l, morning)!!, l, ready, morning, india, uk)
        val snoozed = WakeRing("a1", occ.toEpochMilli(), WakePhase.SNOOZED, "Wake together", startedAt = occ.toEpochMilli(), until = occ.plusSeconds(600).toEpochMilli())
        assertEquals("Raina is up ☀ · you're snoozed", at(local(dto(bea = beaUp), ring = snoozed)))
        assertEquals("Snoozed until 6:40 am", at(local(dto(), ring = snoozed)))
        val up = snoozed.copy(phase = WakePhase.UP, doneAt = occ.plusSeconds(60).toEpochMilli())
        assertEquals("You're both up · 6:31 am / 6:34 am", at(local(dto(bea = beaUp), ring = up)))
        assertEquals("You're up · 6:31 am", at(local(dto(), ring = up)))
        assertEquals("Ringing now", at(local(dto(), ring = snoozed.copy(phase = WakePhase.RINGING))))
        // Hours later the card is about the next morning again.
        val evening = occ.plusSeconds(12 * 3600)
        val l = local(dto(), ring = up, armed = mapOf("a1" to Instant.parse("2026-11-02T01:00:00Z").toEpochMilli()))
        assertEquals("Mon 2 Nov 6:30 am · not set on Raina's phone yet", WakeHome.line(WakeHome.pick(l, evening)!!, l, ready, evening, india, uk))
    }

    @Test fun `a good-morning ping is offered once I'm up and they are not yet`() {
        val upAt = occ.plusSeconds(60)
        val up = WakeRing("a1", occ.toEpochMilli(), WakePhase.UP, "Wake together", startedAt = occ.toEpochMilli(), doneAt = upAt.toEpochMilli())
        val soon = upAt.plusSeconds(300)
        assertEquals(up, WakeHome.goodMorningFor(local(dto(), ring = up), soon))
        // They are up already (their wake came, or reached the ring), or it was sent, or it is hours later.
        val beaUp = AlarmMemberDto("bea", ready = true, lastWake = AlarmWakeDto(occ.toString(), "up", upAt.toString(), 0))
        assertNull(WakeHome.goodMorningFor(local(dto(bea = beaUp), ring = up), soon))
        assertNull(WakeHome.goodMorningFor(local(dto(), ring = up.copy(partnerUpAt = upAt.toEpochMilli())), soon))
        assertNull(WakeHome.goodMorningFor(local(dto(), ring = up.copy(goodMorningSent = true)), soon))
        assertNull(WakeHome.goodMorningFor(local(dto(), ring = up), upAt.plusSeconds(3 * 3600)))
        // Their wake for an earlier morning is not this one's.
        val beaUpYesterday = beaUp.copy(lastWake = AlarmWakeDto(occ.minusSeconds(86_400).toString(), "up", upAt.minusSeconds(86_400).toString(), 0))
        assertEquals(up, WakeHome.goodMorningFor(local(dto(bea = beaUpYesterday), ring = up), soon))
        // Not while it rings or is snoozed, not after a missed ring, never for the test.
        assertNull(WakeHome.goodMorningFor(local(dto(), ring = up.copy(phase = WakePhase.SNOOZED)), soon))
        assertNull(WakeHome.goodMorningFor(local(dto(), ring = up.copy(phase = WakePhase.MISSED)), soon))
        assertNull(WakeHome.goodMorningFor(local(dto(), ring = up.copy(test = true)), soon))
    }
}
