package com.risaina.app.core.alarm

import com.risaina.app.core.network.AlarmDto
import com.risaina.app.core.network.AlarmMemberDto
import com.risaina.app.testutil.contextWithPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** AlarmManager calls for the next rings, behind a fake clock. */
class WakeAlarmSchedulerTest {
    class FakeClock(var exact: Boolean = true) : WakeAlarmClock {
        val set = LinkedHashMap<Pair<String, String>, Pair<WakeTarget, Long>>()
        val kinds = HashMap<Pair<String, String>, WakeAlarmKind>()
        val cancelled = mutableListOf<Pair<String, String>>()
        override fun canExact() = exact
        override fun set(target: WakeTarget, atMillis: Long, kind: WakeAlarmKind) {
            set[target.action to target.alarmId] = target to atMillis
            kinds[target.action to target.alarmId] = kind
        }
        override fun cancel(action: String, alarmId: String) {
            set.remove(action to alarmId)
            cancelled += action to alarmId
        }
    }

    private val store = WakeAlarmStore(contextWithPrefs())
    private val clock = FakeClock()
    private val scheduler = WakeAlarmScheduler(store, clock)
    private val now = Instant.parse("2026-10-29T00:00:00Z")

    private fun dto(id: String, time: String = "06:30", enabled: Boolean = true, myRing: Boolean = true, mode: String = "together") = AlarmDto(
        id = id, createdBy = "bea", mode = mode, time = time, timeZone = "Asia/Kolkata", days = listOf(1, 2, 3, 4, 5), enabled = enabled,
        createdAt = "2026-10-29T10:00:00.000Z", updatedAt = "2026-10-29T10:00:00.000Z",
        members = listOf(AlarmMemberDto("me", ring = myRing), AlarmMemberDto("bea")),
    )

    init {
        store.setIdentity("me", "Raina")
    }

    @Test fun `each alarm gets its own next ring, as an alarm clock`() {
        store.upsert(dto("a1"), 1)
        store.upsert(dto("a2", time = "07:00"), 1)
        scheduler.arm(now)
        val a1 = clock.set[WakeActions.FIRE to "a1"]!!
        val a2 = clock.set[WakeActions.FIRE to "a2"]!!
        assertEquals(Instant.parse("2026-10-29T01:00:00Z").toEpochMilli(), a1.second)
        assertEquals(a1.second, a1.first.occurrence)
        assertEquals(Instant.parse("2026-10-29T01:30:00Z").toEpochMilli(), a2.second)
        assertEquals(WakeAlarmKind.ALARM_CLOCK, clock.kinds[WakeActions.FIRE to "a1"])
        assertEquals(mapOf("a1" to a1.second, "a2" to a2.second), store.current().armed)
    }

    @Test fun `turned off, off on this phone and deleted alarms are cancelled`() {
        store.upsert(dto("a1"), 1)
        store.upsert(dto("a2"), 1)
        scheduler.arm(now)
        store.upsert(dto("a1", enabled = false).copy(version = 2), 2)
        store.remove("a2", 3)
        scheduler.arm(now)
        assertTrue(clock.set.isEmpty())
        assertTrue((WakeActions.FIRE to "a1") in clock.cancelled)
        assertTrue((WakeActions.FIRE to "a2") in clock.cancelled)
        assertTrue(store.current().armed.isEmpty())
        store.upsert(dto("a3", myRing = false), 4)
        scheduler.arm(now)
        assertTrue(clock.set.isEmpty())
    }

    @Test fun `a lost ring is caught up, an old one reported missed once`() {
        store.upsert(dto("a1"), 1)
        scheduler.arm(now)
        val occ = Instant.parse("2026-10-29T01:00:00Z")
        // The process was killed and the alarm dropped: 5 minutes after, it rings now.
        assertEquals(listOf("a1" to occ), scheduler.arm(occ.plusSeconds(300)).ringNow)
        // Hours later (the phone was off): missed, and only once.
        store.update { it.copy(armed = mapOf("a1" to occ.toEpochMilli())) }
        val later = occ.plusSeconds(3 * 3600)
        assertEquals(listOf("a1" to occ), scheduler.arm(later).missed)
        assertEquals(occ.toEpochMilli(), store.current().handled["a1"])
        assertTrue(scheduler.arm(later).missed.isEmpty())
    }

    @Test fun `snooze, timeout and the test have their own alarms`() {
        scheduler.setSnooze("a1", 1, 600)
        scheduler.setTimeout("a1", 1, 0, 700)
        scheduler.setTest(800)
        assertEquals(WakeAlarmKind.ALARM_CLOCK, clock.kinds[WakeActions.SNOOZE_FIRE to "a1"])
        assertEquals(WakeAlarmKind.EXACT, clock.kinds[WakeActions.TIMEOUT to "a1"])
        assertEquals(800L, clock.set[WakeActions.FIRE to WakeAlarmRules.TEST_ID]!!.second)
        scheduler.cancelRing("a1")
        assertEquals(setOf(WakeActions.FIRE to WakeAlarmRules.TEST_ID), clock.set.keys)
    }

    @Test fun `sign-out cancels every alarm this phone set`() {
        store.upsert(dto("a1"), 1)
        scheduler.arm(now)
        scheduler.setSnooze("a1", 1, 600)
        scheduler.setTest(800)
        scheduler.cancelAll()
        assertTrue(clock.set.isEmpty())
    }

    @Test fun `the report says readiness and the set ring of each alarm`() {
        store.upsert(dto("a1"), 1)
        store.upsert(dto("a2", mode = "local"), 1)
        scheduler.arm(now)
        val items = WakeAlarmScheduler.reportItems(store.current(), WakeReadyReport(false, WakeReadiness.EXACT_ALARMS_OFF))
        assertEquals(ReportedItem(false, "exact_alarms_off", Instant.parse("2026-10-29T01:00:00Z").toEpochMilli()), items["a1"])
        assertEquals(ReportedItem(false, WakeReadiness.UPDATE_NEEDED, null), items["a2"])
    }
}
