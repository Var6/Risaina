package com.risaina.app.core.alarm

import com.risaina.app.core.model.WakeMode
import com.risaina.app.core.network.AlarmDto
import com.risaina.app.core.network.AlarmMemberDto
import com.risaina.app.core.network.AlarmWakeDto
import com.risaina.app.testutil.contextWithPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wake together's own copy on the phone, kept outside Room. */
class WakeAlarmStoreTest {
    private val context = contextWithPrefs()
    private val store = WakeAlarmStore(context)

    private fun dto(id: String = "a1", version: Int = 1, time: String = "06:30", mode: String = "together", myRing: Boolean = true) = AlarmDto(
        id = id, coupleId = "c", createdBy = "bea", mode = mode, time = time, timeZone = "Asia/Kolkata", days = listOf(1, 2, 3, 4, 5),
        version = version, createdAt = "2026-10-29T10:00:00.000Z", updatedAt = "2026-10-29T10:00:00.000Z",
        members = listOf(AlarmMemberDto("me", ring = myRing), AlarmMemberDto("bea")),
    )

    @Test fun `alarms, ring state and set rings survive a new process`() {
        store.setIdentity("me", "Raina")
        store.upsert(dto(), 1)
        store.update { it.copy(armed = mapOf("a1" to 42L), ring = WakeRing("a1", 42, label = "Wake together", startedAt = 40), handled = mapOf("a1" to 42L)) }
        val again = WakeAlarmStore(context).current()
        assertEquals("me", again.me)
        assertEquals("Raina", again.partnerName)
        assertEquals(listOf("a1"), again.domain().map { it.id })
        assertEquals(42L, again.armed["a1"])
        assertEquals(WakePhase.RINGING, again.ring?.phase)
    }

    @Test fun `a later mode is kept, but not as one this phone can ring`() {
        store.upsert(dto(mode = "local"), 1)
        val a = WakeAlarmStore(context).current().alarm("a1")!!
        assertEquals(WakeMode.UNKNOWN, a.mode)
        assertFalse(WakeAlarmRules.canSchedule(a))
        assertEquals("local", WakeAlarmStore(context).current().alarms.single().mode)
    }

    @Test fun `an older copy from a replay never replaces a newer one`() {
        assertTrue(store.upsert(dto(version = 3, time = "07:00"), 1))
        assertFalse(store.upsert(dto(version = 2, time = "06:00"), 2))
        assertEquals("07:00", store.current().alarms.single().time)
        store.upsert(dto(version = 3, time = "07:15"), 3)
        assertEquals("07:15", store.current().alarms.single().time)
    }

    @Test fun `a full refresh replaces the list but keeps what changed while it was on its way`() {
        store.upsert(dto("old"), 100)
        store.upsert(dto("gone"), 100)
        val requestedAt = 200L
        // Created here while the GET was on its way, and deleted here meanwhile.
        store.upsert(dto("new"), 250)
        store.remove("gone", 260)
        val added = store.replaceAll(listOf(dto("old", version = 2), dto("gone"), dto("partner")), requestedAt)
        assertEquals(setOf("old", "new", "partner"), store.current().alarms.map { it.id }.toSet())
        assertEquals(2, store.current().alarms.first { it.id == "old" }.version)
        assertEquals(setOf("partner"), added)
        // One the server no longer has, written here before the request: gone.
        store.replaceAll(listOf(dto("new")), 300)
        assertEquals(listOf("new"), store.current().alarms.map { it.id })
    }

    @Test fun `this person's switch applies at once and is dropped once the server agrees`() {
        store.setIdentity("me", null)
        store.upsert(dto(), 1)
        store.setPendingRing("a1", false)
        assertFalse(store.current().alarm("a1")!!.member("me")!!.ring)
        // A copy from before the change does not undo it.
        store.upsert(dto(version = 2, myRing = true), 2)
        assertFalse(store.current().alarm("a1")!!.member("me")!!.ring)
        store.upsert(dto(version = 3, myRing = false), 3)
        assertTrue(store.current().pendingRing.isEmpty())
    }

    @Test fun `a wake updates the member's last wake, never with an older ring`() {
        store.upsert(dto(), 1)
        store.applyWake("a1", "bea", AlarmWakeDto("2026-10-29T01:00:00Z", "up", "2026-10-29T01:04:00Z", 1))
        store.applyWake("a1", "bea", AlarmWakeDto("2026-10-28T01:00:00Z", "missed", "2026-10-28T01:20:00Z", 0))
        val bea = store.current().alarm("a1")!!.member("bea")!!
        assertEquals("2026-10-29T01:04:00Z", bea.lastWake?.at.toString())
        assertNull(store.current().alarm("a1")!!.member("me")!!.lastWake)
    }

    @Test fun `clear forgets everything`() {
        store.setIdentity("me", "Raina")
        store.upsert(dto(), 1)
        store.clear()
        assertEquals(WakeLocal(), store.current())
        assertEquals(WakeLocal(), WakeAlarmStore(context).current())
    }

    @Test fun `a write begun before a clear is dropped, one begun after it goes through`() {
        val before = store.generation
        store.clear()
        assertNull(store.ifCurrent(before) { store.upsert(dto(), 1) })
        assertTrue(store.current().alarms.isEmpty())
        assertEquals(true, store.ifCurrent(store.generation) { store.upsert(dto(), 1) })
        assertEquals(listOf("a1"), store.current().alarms.map { it.id })
    }
}
