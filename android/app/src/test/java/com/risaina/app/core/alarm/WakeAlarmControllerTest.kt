package com.risaina.app.core.alarm

import com.risaina.app.core.network.AlarmDto
import com.risaina.app.core.network.AlarmMemberDto
import com.risaina.app.core.sos.RingState
import com.risaina.app.core.sos.SosAlarmController
import com.risaina.app.testutil.contextWithPrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The ring on this phone: fire, snooze, the timeout, "I'm up", the partner, the SOS. */
class WakeAlarmControllerTest {
    private val context = contextWithPrefs()
    private val store = WakeAlarmStore(context)
    private val clock = WakeAlarmSchedulerTest.FakeClock()
    private val scheduler = WakeAlarmScheduler(store, clock)
    private val notifier = mockk<WakeAlarmNotifier>(relaxed = true)
    private val sosState = MutableStateFlow<RingState?>(null)
    private val sos = mockk<SosAlarmController> { every { state } returns sosState }
    private val probe = mockk<WakeReadinessProbe> {
        every { read() } returns WakeDeviceState(35, true, false, true, true, 70, false, false, "Google")
    }
    private val controller = WakeAlarmController(context, store, scheduler, notifier, probe, sos)

    private val occ = Instant.parse("2026-10-29T01:00:00Z")
    private val tomorrow = Instant.parse("2026-10-30T01:00:00Z")

    init {
        store.setIdentity("me", "Raina")
        store.upsert(
            AlarmDto(
                id = "a1", createdBy = "bea", time = "06:30", timeZone = "Asia/Kolkata", days = listOf(1, 2, 3, 4, 5),
                createdAt = "2026-10-29T10:00:00.000Z", updatedAt = "2026-10-29T10:00:00.000Z",
                members = listOf(AlarmMemberDto("me"), AlarmMemberDto("bea")),
            ),
            1,
        )
        controller.rearm(occ.minusSeconds(3600))
    }

    private fun ring() = store.current().ring

    @Test fun `a fire rings once, sets its timeout and the next morning`() {
        controller.fire("a1", occ, occ.plusSeconds(1))
        assertEquals(WakePhase.RINGING, ring()?.phase)
        verify(exactly = 1) { notifier.postRing(any(), any(), "Raina's alarm is ringing too") }
        assertEquals(occ.plusSeconds(1).toEpochMilli() + WakeAlarmRules.RING_MS, clock.set[WakeActions.TIMEOUT to "a1"]!!.second)
        assertEquals(tomorrow.toEpochMilli(), clock.set[WakeActions.FIRE to "a1"]!!.second)
        // The same ring again (a re-armed copy, a duplicate broadcast) does nothing.
        controller.fire("a1", occ, occ.plusSeconds(5))
        verify(exactly = 1) { notifier.postRing(any(), any(), any()) }
    }

    @Test fun `snooze comes back, and I'm up ends it on this phone`() {
        controller.fire("a1", occ, occ)
        controller.snooze("a1", occ.plusSeconds(60))
        val snoozed = ring()!!
        assertEquals(WakePhase.SNOOZED, snoozed.phase)
        assertEquals(1, snoozed.snoozes)
        assertEquals(occ.plusSeconds(60 + 600).toEpochMilli(), snoozed.until)
        assertEquals(snoozed.until, clock.set[WakeActions.SNOOZE_FIRE to "a1"]!!.second)
        assertFalse((WakeActions.TIMEOUT to "a1") in clock.set)
        verify { notifier.cancelRing(); notifier.postSnoozed(any(), any(), null) }

        controller.handleSnoozeFire(occ, occ.plusSeconds(660))
        assertEquals(WakePhase.RINGING, ring()?.phase)

        controller.up("a1", occ.plusSeconds(700))
        assertEquals(WakePhase.UP, ring()?.phase)
        assertEquals(occ.plusSeconds(700).toEpochMilli(), ring()?.doneAt)
        assertFalse((WakeActions.SNOOZE_FIRE to "a1") in clock.set)
        assertFalse((WakeActions.TIMEOUT to "a1") in clock.set)
    }

    @Test fun `when nobody answers it snoozes itself once, then is missed`() {
        controller.fire("a1", occ, occ)
        controller.handleTimeout(ring()!!.startedAt, occ.plusSeconds(600))
        assertEquals(WakePhase.SNOOZED, ring()?.phase)
        assertTrue(ring()!!.autoSnoozed)
        assertEquals(0, ring()!!.snoozes)
        controller.handleSnoozeFire(occ, occ.plusSeconds(1200))
        // A timeout of the earlier ring is stale.
        controller.handleTimeout(occ.toEpochMilli(), occ.plusSeconds(1300))
        assertEquals(WakePhase.RINGING, ring()?.phase)
        controller.handleTimeout(ring()!!.startedAt, occ.plusSeconds(1800))
        assertEquals(WakePhase.MISSED, ring()?.phase)
        verify { notifier.postMissed("Wake together", any(), phoneWasOff = false) }
    }

    @Test fun `a snooze a force-stop dropped rings again, or ends as missed when far too late`() {
        controller.fire("a1", occ, occ)
        controller.snooze("a1", occ.plusSeconds(60))
        val until = ring()!!.until!!
        // A phone maker's force-stop: every alarm of the app is gone.
        clock.set.clear()
        // Opened again before the snooze ends: the snooze is set again.
        controller.rearm(occ.plusSeconds(300))
        assertEquals(until, clock.set[WakeActions.SNOOZE_FIRE to "a1"]!!.second)
        clock.set.clear()
        // Opened again a few minutes after it: it rings again now.
        controller.rearm(Instant.ofEpochMilli(until).plusSeconds(120))
        assertEquals(WakePhase.RINGING, ring()?.phase)
        verify(exactly = 2) { notifier.postRing(any(), any(), any()) }
        clock.set.clear()
        // Then nothing until long after: missed, reported, and Home no longer says it rings.
        controller.rearm(Instant.ofEpochMilli(until).plusSeconds(3 * 3600))
        assertEquals(WakePhase.MISSED, ring()?.phase)
        verify(exactly = 1) { notifier.postMissed("Wake together", any(), phoneWasOff = true) }
    }

    @Test fun `an SOS always wins`() {
        sosState.value = mockk(relaxed = true)
        controller.fire("a1", occ, occ)
        assertEquals(WakePhase.SNOOZED, ring()?.phase)
        assertEquals(0, ring()!!.snoozes)
        verify(exactly = 0) { notifier.postRing(any(), any(), any()) }

        sosState.value = null
        controller.handleSnoozeFire(occ, occ.plusSeconds(600))
        assertEquals(WakePhase.RINGING, ring()?.phase)
        controller.pauseForSos()
        assertEquals(WakePhase.SNOOZED, ring()?.phase)
    }

    @Test fun `the partner being up shows on the ring, and later as is up too`() {
        controller.fire("a1", occ, occ)
        assertFalse(controller.partnerUp("a1", occ, occ.plusSeconds(240)))
        assertEquals(occ.plusSeconds(240).toEpochMilli(), ring()?.partnerUpAt)
        verify { notifier.postRing(any(), any(), match { it.startsWith("Raina is up ☀") }) }
        controller.up("a1", occ.plusSeconds(300))
        assertTrue(controller.partnerUp("a1", occ, occ.plusSeconds(240)))
        // Another ring's wake is not this one's.
        assertFalse(controller.partnerUp("a1", tomorrow, tomorrow))
    }

    @Test fun `not on my phone stops it here at once`() {
        controller.notOnMyPhone("a1", occ.minusSeconds(60))
        assertEquals(false, store.current().pendingRing["a1"])
        assertFalse((WakeActions.FIRE to "a1") in clock.set)
        verify { notifier.cancelSet("a1") }
    }

    @Test fun `two lost rings due at once, the latest rings and the earlier is missed`() {
        store.upsert(
            AlarmDto(
                id = "a2", createdBy = "bea", time = "06:40", timeZone = "Asia/Kolkata", days = listOf(1, 2, 3, 4, 5),
                createdAt = "2026-10-29T10:00:00.000Z", updatedAt = "2026-10-29T10:00:00.000Z",
                members = listOf(AlarmMemberDto("me"), AlarmMemberDto("bea")),
            ),
            1,
        )
        controller.rearm(occ.minusSeconds(3600))
        // The phone was off over both rings (6:30 and 6:40) and is back at 6:45.
        controller.rearm(occ.plusSeconds(15 * 60))
        assertEquals("a2", ring()?.alarmId)
        assertEquals(WakePhase.RINGING, ring()?.phase)
        assertEquals(occ.toEpochMilli(), store.current().handled["a1"])
        verify(exactly = 1) { notifier.postMissed("Wake together", any(), phoneWasOff = true) }
        // Neither comes back on the next re-arm, and both are set for tomorrow.
        controller.rearm(occ.plusSeconds(16 * 60))
        verify(exactly = 1) { notifier.postMissed(any(), any(), any()) }
        verify(exactly = 1) { notifier.postRing(any(), any(), any()) }
        assertEquals(tomorrow.toEpochMilli(), clock.set[WakeActions.FIRE to "a1"]!!.second)
    }

    @Test fun `a test never takes the place of a snoozed alarm`() {
        controller.fire("a1", occ, occ)
        controller.snooze("a1", occ.plusSeconds(60))
        val until = ring()!!.until!!
        // Tapped from the Wake screen while snoozed: refused, nothing set.
        assertEquals(WakePhase.SNOOZED, controller.scheduleTest(occ.plusSeconds(120))?.phase)
        assertFalse((WakeActions.FIRE to WakeAlarmRules.TEST_ID) in clock.set)
        // A test set just before the alarm went off fires during the snooze: nothing changes,
        // and "Stop test" has no test to stop.
        controller.fireTest(occ.plusSeconds(130))
        controller.snooze(WakeAlarmRules.TEST_ID, occ.plusSeconds(140))
        controller.up(WakeAlarmRules.TEST_ID, occ.plusSeconds(150))
        assertEquals("a1", ring()?.alarmId)
        assertEquals(WakePhase.SNOOZED, ring()?.phase)
        assertEquals(until, clock.set[WakeActions.SNOOZE_FIRE to "a1"]!!.second)
        verify(exactly = 1) { notifier.postRing(any(), any(), any()) }
        // The "Snoozed until" notice stays: only the real ring's start cleared the status.
        verify(exactly = 1) { notifier.cancelStatus() }
        // Once the real ring is over, the test can ring.
        controller.handleSnoozeFire(occ, Instant.ofEpochMilli(until))
        controller.up("a1", Instant.ofEpochMilli(until).plusSeconds(30))
        assertNull(controller.scheduleTest(Instant.ofEpochMilli(until).plusSeconds(60)))
        assertTrue((WakeActions.FIRE to WakeAlarmRules.TEST_ID) in clock.set)
    }

    @Test fun `a deleted alarm's ring ends quietly`() {
        controller.fire("a1", occ, occ)
        controller.alarmGone("a1")
        assertEquals(WakePhase.MISSED, ring()?.phase)
        verify(exactly = 0) { notifier.postMissed(any(), any(), any()) }
    }

    @Test fun `a change is reported once`() {
        controller.report()
        val pending = store.current().pendingReport!!
        assertEquals(ReportedItem(true, null, occ.toEpochMilli()), pending["a1"])
        store.update { it.copy(reported = pending, pendingReport = null) }
        controller.report()
        assertNull(store.current().pendingReport)
    }

    @Test fun `sign-out leaves nothing set, ringing or stored`() {
        controller.fire("a1", occ, occ)
        controller.clearAll()
        assertTrue(clock.set.isEmpty())
        assertEquals(WakeLocal(), store.current())
        verify { notifier.cancelAll() }
    }

    private fun WakeAlarmController.handleSnoozeFire(occurrence: Instant, now: Instant) = snoozeFire("a1", occurrence.toEpochMilli(), now)
    private fun WakeAlarmController.handleTimeout(startedAt: Long, now: Instant) = timeout("a1", startedAt, now)
}
