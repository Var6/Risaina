package com.risaina.app.core.safety

import com.risaina.app.core.data.SosRepository
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.location.LiveLocationController
import com.risaina.app.core.location.LiveLocationRepository
import com.risaina.app.core.network.EventDto
import com.risaina.app.core.network.LiveLocationDto
import com.risaina.app.core.network.LiveLocationStateDto
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SosDto
import com.risaina.app.core.notifications.SosAlert
import com.risaina.app.core.sos.SosAlarmController
import com.risaina.app.core.sos.SosLocalState
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
    private lateinit var events: SafetyEvents

    private val now = Instant.now()
    private val old = now.minus(Duration.ofDays(2))

    @Before
    fun setUp() {
        coEvery { coupleDao.partner() } returns null
        events = SafetyEvents(
            json, coupleDao, SosRepository(api), SosLocalState(contextWithPrefs()), alarm, sosAlert,
            LiveLocationRepository(api), { controller }, notifier,
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
    private fun session(id: String, at: Instant, reason: String = "manual") =
        LiveLocationDto(sessionId = id, userId = PARTNER, reason = reason, startedAt = at.toString(), expiresAt = null, updatedAt = at.toString())
    private fun started(seq: Long, at: Instant, reason: String = "manual") =
        event(seq, "location.started", at, mapOf("sharing" to json.encodeToJsonElement(LiveLocationDto.serializer(), session("loc$seq", at, reason))))
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
    }

    @Test
    fun `fresh events still notify, after the server confirms a partner's share`() = runTest {
        events.apply(resolved(2, now), ME)
        verify(exactly = 1) { sosAlert.postSafeNow(any()) }

        coEvery { api.locationSharing() } returns LiveLocationStateDto(partner = session("loc3", now))
        events.apply(started(3, now), ME)
        coVerify(exactly = 1) { notifier.partnerSharingStarted(any(), any()) }
    }

    @Test
    fun `a fresh panic share posts no is-sharing notification (the SOS alarm covers it)`() = runTest {
        coEvery { api.locationSharing() } returns LiveLocationStateDto(partner = session("loc3", now, reason = "panic"))
        events.apply(started(3, now, reason = "panic"), ME)
        coVerify(exactly = 0) { notifier.partnerSharingStarted(any(), any()) }
    }

    @Test
    fun `a fresh start the server no longer shows is not announced`() = runTest {
        // Its stop is still on the way (or it was purged without an event).
        coEvery { api.locationSharing() } returns LiveLocationStateDto(partner = null)
        events.apply(started(3, now), ME)
        coVerify(exactly = 0) { notifier.partnerSharingStarted(any(), any()) }
    }

    private companion object {
        const val ME = "me"
        const val PARTNER = "her"
    }
}
