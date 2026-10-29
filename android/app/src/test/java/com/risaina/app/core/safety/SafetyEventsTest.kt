package com.risaina.app.core.safety

import com.risaina.app.core.data.SosRepository
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.location.LiveLocationController
import com.risaina.app.core.location.LiveLocationRepository
import com.risaina.app.core.network.EventDto
import com.risaina.app.core.network.LiveLocationDto
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SosDto
import com.risaina.app.core.notifications.SosAlert
import com.risaina.app.core.sos.SosAlarmController
import com.risaina.app.core.sos.SosLocalState
import com.risaina.app.core.sync.DeferredEffects
import com.risaina.app.testutil.contextWithPrefs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The sos.* and location.* reducer branches. Events replay on a fresh sign-in or re-pairing (cursor
 * 0) and after a late sync: history must update state but never ring, nor post "is safe now" or
 * "is sharing" again. Fresh events still do, so these tests cannot pass by posting nothing at all.
 */
class SafetyEventsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val api = mockk<RisainaApi>()
    private val coupleDao = mockk<CoupleDao>()
    private val alarm = mockk<SosAlarmController>(relaxed = true)
    private val sosAlert = mockk<SosAlert>(relaxed = true)
    private val notifier = mockk<SafetyNotifier>(relaxed = true)
    private val controller = mockk<LiveLocationController>(relaxed = true)
    private val deferred = mockk<DeferredEffects>(relaxed = true)
    private lateinit var events: SafetyEvents

    private val now = Instant.now()
    private val old = now.minus(Duration.ofDays(2))

    @Before
    fun setUp() {
        coEvery { coupleDao.partner() } returns null
        events = SafetyEvents(
            json, coupleDao, SosRepository(api), SosLocalState(contextWithPrefs()), alarm, sosAlert,
            LiveLocationRepository(api), { controller }, notifier, deferred,
        )
    }

    private fun event(seq: Long, type: String, at: Instant, payload: Map<String, Any>) = EventDto(
        "e$seq", "c1", seq, type, PARTNER,
        JsonObject(payload.mapValues { (_, v) -> if (v is String) JsonPrimitive(v) else v as kotlinx.serialization.json.JsonElement }),
        at.toString(),
    )

    private fun raised(seq: Long, at: Instant) = event(
        seq, "sos.raised", at,
        mapOf("sos" to json.encodeToJsonElement(SosDto.serializer(), SosDto("s$seq", "c1", PARTNER, ME, "help", at.toString(), panic = true))),
    )
    private fun resolved(seq: Long, at: Instant) = event(seq, "sos.resolved", at, mapOf("sosId" to "s${seq - 1}", "resolvedAt" to at.toString()))
    private fun session(id: String, at: Instant, reason: String = "manual", sosId: String? = null) =
        LiveLocationDto(sessionId = id, userId = PARTNER, reason = reason, sosId = sosId, startedAt = at.toString(), expiresAt = null, updatedAt = at.toString())
    private fun started(seq: Long, at: Instant, reason: String = "manual", sosId: String? = null) =
        event(seq, "location.started", at, mapOf("sharing" to json.encodeToJsonElement(LiveLocationDto.serializer(), session("loc$seq", at, reason, sosId))))
    private fun stopped(seq: Long, at: Instant) =
        event(seq, "location.stopped", at, mapOf("sessionId" to "loc${seq - 1}", "userId" to PARTNER, "stoppedAt" to at.toString(), "reason" to "stopped"))

    @Test
    fun `a cursor-0 replay of history rings nothing and posts no safe-now or is-sharing notification`() = runTest {
        val history = listOf(raised(1, old), resolved(2, old), started(3, old), stopped(4, old), started(5, old))
        for (e in history) assertTrue(events.apply(e, ME).isEmpty())

        verify(exactly = 0) { alarm.ring(any()) }
        verify(exactly = 0) { sosAlert.postSafeNow(any()) }
        coVerify(exactly = 0) { notifier.partnerSharingStarted(any(), any()) }
        // A replayed start is not even re-checked with the server: it is history.
        coVerify(exactly = 0) { api.locationSharing() }
        verify(exactly = 0) { deferred.checkPartnerLocation() }
    }

    @Test
    fun `fresh events still notify, with no network inside apply`() = runTest {
        events.apply(resolved(2, now), ME)
        verify(exactly = 1) { sosAlert.postSafeNow(any()) }

        events.apply(started(3, now), ME)
        coVerify(exactly = 1) { notifier.partnerSharingStarted(any(), any()) }
        // apply holds the sync's apply lock inside the push handler's few seconds: no GET here.
        // The server re-check (a stop still on its way, a purge) runs later, deferred.
        coVerify(exactly = 0) { api.locationSharing() }
        verify(exactly = 1) { deferred.checkPartnerLocation() }
    }

    @Test
    fun `a fresh panic share linked to an SOS posts no is-sharing notification (the SOS alarm covers it)`() = runTest {
        events.apply(started(3, now, reason = "panic", sosId = "s1"), ME)
        coVerify(exactly = 0) { notifier.partnerSharingStarted(any(), any()) }
    }

    @Test
    fun `a fresh panic share whose SOS has not gone through yet is announced`() = runTest {
        // A high-priority push (backend push policy), and maybe the only sign of the emergency for now.
        events.apply(started(3, now, reason = "panic"), ME)
        coVerify(exactly = 1) { notifier.partnerSharingStarted(any(), any()) }
        verify(exactly = 0) { notifier.cancelPartnerSharing() }
    }

    @Test
    fun `once the panic share is linked to its SOS the earlier is-sharing notice is removed`() = runTest {
        events.apply(started(3, now, reason = "panic"), ME)
        events.apply(started(4, now, reason = "panic", sosId = "s1"), ME)
        coVerify(exactly = 1) { notifier.partnerSharingStarted(any(), any()) }
        verify(exactly = 1) { notifier.cancelPartnerSharing() }
    }

    @Test
    fun `a manual share still announces and removes nothing`() = runTest {
        events.apply(started(3, now, reason = "manual"), ME)
        coVerify(exactly = 1) { notifier.partnerSharingStarted(any(), any()) }
        verify(exactly = 0) { notifier.cancelPartnerSharing() }
    }

    @Test
    fun `a panic SOS that rings removes the is-sharing notice`() = runTest {
        events.apply(raised(5, now), ME)
        verify(exactly = 1) { alarm.ring(any()) }
        verify(exactly = 1) { notifier.cancelPartnerSharing() }
    }

    @Test
    fun `an SOS that does not ring leaves the notice alone`() = runTest {
        // History: it is not ringing now, so nothing replaces the notice.
        events.apply(raised(5, old), ME)
        verify(exactly = 0) { notifier.cancelPartnerSharing() }
    }

    @Test
    fun `a stop applied after the start removes the notification`() = runTest {
        events.apply(started(3, now), ME)
        events.apply(stopped(4, now), ME)
        verify(exactly = 1) { notifier.cancelPartnerSharing() }
    }

    private companion object {
        const val ME = "me"
        const val PARTNER = "her"
    }
}
