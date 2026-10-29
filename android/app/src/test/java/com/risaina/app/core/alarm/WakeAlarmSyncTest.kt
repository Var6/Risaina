package com.risaina.app.core.alarm

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.FixedTimeProvider
import com.risaina.app.core.data.WakeAlarmRepository
import com.risaina.app.core.data.toDomain
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.database.PartnerEntity
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.model.Preferences
import com.risaina.app.core.model.Session
import com.risaina.app.core.network.AlarmDto
import com.risaina.app.core.network.AlarmMemberDto
import com.risaina.app.core.network.EventDto
import com.risaina.app.core.network.NetworkModule
import com.risaina.app.testutil.contextWithPrefs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** alarm.* events and GET /alarms on this phone, and when the partner's notices are posted. */
class WakeAlarmSyncTest {
    private val json = NetworkModule.json
    private val now = Instant.parse("2026-10-28T16:30:00Z") // Wednesday 22:00 in India
    private val store = WakeAlarmStore(contextWithPrefs())
    private val controller = mockk<WakeAlarmController>(relaxed = true)
    private val notifier = mockk<WakeAlarmNotifier>(relaxed = true)
    private val repository = mockk<WakeAlarmRepository>()
    private val coupleDao = mockk<CoupleDao>().also { coEvery { it.partner() } returns PartnerEntity("bea", "Raina", null) }
    private val session = mockk<SessionStore>().also { coEvery { it.current() } returns Session("me", "a", Instant.MAX, "r", Instant.MAX, "d") }
    private val prefs = mockk<PreferencesStore>().also { coEvery { it.current() } returns Preferences() }
    private val sync = WakeAlarmSync(
        json, store, controller, notifier, repository, coupleDao, session, prefs, FixedTimeProvider(Clock.fixed(now, ZoneId.of("Asia/Kolkata"))),
    )

    private fun dto(id: String = "a1", version: Int = 1, createdBy: String = "bea", myRing: Boolean = true, enabled: Boolean = true) = AlarmDto(
        id = id, coupleId = "c", createdBy = createdBy, time = "06:30", timeZone = "Asia/Kolkata", days = listOf(1, 2, 3, 4, 5), enabled = enabled,
        version = version, createdAt = "2026-10-28T16:00:00.000Z", updatedAt = "2026-10-28T16:00:00.000Z",
        members = listOf(AlarmMemberDto("me", ring = myRing), AlarmMemberDto("bea")),
    )

    private fun event(seq: Long, type: String, payload: Map<String, JsonElement>, actor: String = "bea") =
        EventDto("e$seq", "c", seq, type, actor, JsonObject(payload), now.toString())

    private fun alarmEvent(seq: Long, type: String, alarm: AlarmDto, notify: Boolean = false, change: String? = null, actor: String = "bea") =
        event(seq, type, buildMap {
            put("alarm", json.encodeToJsonElement(AlarmDto.serializer(), alarm))
            put("notify", JsonPrimitive(notify))
            change?.let { put("change", JsonPrimitive(it)) }
        }, actor)

    private fun noticeStarting(prefix: String) = verify(exactly = 1) { notifier.postSet("a1", match { it.startsWith(prefix) && it.endsWith("It rings on your phone too.") }, false, true) }

    @Test fun `the partner's new alarm is announced once, even without notify`() = runTest {
        sync.apply(alarmEvent(1, "alarm.created", dto()), "me", replay = false)
        assertEquals(listOf("a1"), store.current().alarms.map { it.id })
        assertEquals("me", store.current().me)
        assertEquals("Raina", store.current().partnerName)
        verify { controller.rearm(any()) }
        noticeStarting("Raina set Wake together for ")
        // Their readiness report changes a member: no second notice.
        sync.apply(alarmEvent(2, "alarm.updated", dto(version = 2), change = "member"), "me", replay = false)
        verify(exactly = 1) { notifier.postSet(any(), any(), any(), any()) }
    }

    @Test fun `an edit the server marked for notice says changed`() = runTest {
        sync.apply(alarmEvent(1, "alarm.created", dto()), "me", replay = false)
        sync.apply(alarmEvent(2, "alarm.updated", dto(version = 2), notify = true, change = "edited"), "me", replay = false)
        noticeStarting("Raina changed Wake together to ")
    }

    @Test fun `history, my own alarms and ones off on this phone are never announced`() = runTest {
        sync.apply(alarmEvent(1, "alarm.created", dto()), "me", replay = true)
        sync.apply(alarmEvent(2, "alarm.created", dto("mine", createdBy = "me"), actor = "me"), "me", replay = false)
        sync.apply(alarmEvent(3, "alarm.updated", dto("mine", version = 2, createdBy = "me"), change = "edited"), "me", replay = false)
        sync.apply(alarmEvent(4, "alarm.created", dto("off", myRing = false)), "me", replay = false)
        sync.apply(alarmEvent(5, "alarm.created", dto("disabled", enabled = false)), "me", replay = false)
        verify(exactly = 0) { notifier.postSet(any(), any(), any(), any()) }
    }

    @Test fun `a deleted alarm stops and goes`() = runTest {
        sync.apply(alarmEvent(1, "alarm.created", dto()), "me", replay = true)
        sync.apply(event(2, "alarm.deleted", mapOf("alarmId" to JsonPrimitive("a1"))), "me", replay = false)
        verify { controller.alarmGone("a1") }
        assertTrue(store.current().alarms.isEmpty())
    }

    @Test fun `the partner being up reaches the ring, and once I'm up, a quiet is up too`() = runTest {
        sync.apply(alarmEvent(1, "alarm.created", dto()), "me", replay = true)
        val occ = Instant.parse("2026-10-28T01:00:00Z")
        val at = now.minusSeconds(60)
        every { controller.partnerUp("a1", occ, at) } returns true
        sync.apply(
            event(2, "alarm.woke", mapOf(
                "alarmId" to JsonPrimitive("a1"), "userId" to JsonPrimitive("bea"), "occurrence" to JsonPrimitive(occ.toString()),
                "outcome" to JsonPrimitive("up"), "at" to JsonPrimitive(at.toString()), "snoozes" to JsonPrimitive(1),
            )),
            "me", replay = false,
        )
        assertEquals(at, store.current().alarm("a1")!!.member("bea")!!.lastWake?.at)
        verify { notifier.postPartnerUp(match { it.startsWith("Raina is up too · ") }, false, true) }
    }

    @Test fun `my own wake is stored but announces nothing`() = runTest {
        sync.apply(alarmEvent(1, "alarm.created", dto()), "me", replay = true)
        sync.apply(
            event(2, "alarm.woke", mapOf(
                "alarmId" to JsonPrimitive("a1"), "userId" to JsonPrimitive("me"), "occurrence" to JsonPrimitive("2026-10-28T01:00:00Z"),
                "outcome" to JsonPrimitive("up"), "at" to JsonPrimitive("2026-10-28T01:02:00Z"),
            ), actor = "me"),
            "me", replay = false,
        )
        assertEquals(Instant.parse("2026-10-28T01:02:00Z"), store.current().alarm("a1")!!.member("me")!!.lastWake?.at)
        verify(exactly = 0) { controller.partnerUp(any(), any(), any()) }
    }

    @Test fun `the first refresh after sign-in only primes, later ones announce what is new`() = runTest {
        coEvery { repository.list() } returns AppResult.Success(listOfNotNull(dto("old").toDomain()))
        sync.refresh()
        assertTrue(store.current().primed)
        verify(exactly = 0) { notifier.postSet(any(), any(), any(), any()) }
        coEvery { repository.list() } returns AppResult.Success(listOfNotNull(dto("old").toDomain(), dto().toDomain()))
        sync.refresh()
        verify(exactly = 1) { notifier.postSet("a1", any(), any(), any()) }
        verify(atLeast = 2) { controller.rearm(any()) }
    }

    @Test fun `an unreadable alarm is skipped`() = runTest {
        sync.apply(event(1, "alarm.created", mapOf("alarm" to JsonObject(mapOf("id" to JsonPrimitive("x"))))), "me", replay = false)
        assertNull(store.current().alarm("x"))
    }
}
