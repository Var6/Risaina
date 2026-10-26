package com.risaina.app.core.location

import android.location.Location
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.domain.LiveLocationRules
import com.risaina.app.core.location.LiveLocationController.StartResult
import com.risaina.app.core.network.LiveLocationDto
import com.risaina.app.core.network.LiveLocationStateDto
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SharingResponse
import com.risaina.app.core.network.StartSharingRequest
import com.risaina.app.testutil.contextWithPrefs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * LiveLocationController against a fake server (mocked API behind the real repository), fake
 * location client and fake Android effects: the session-id handling and the app-open reconcile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveLocationControllerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var api: RisainaApi
    private lateinit var repo: LiveLocationRepository
    private lateinit var controller: LiveLocationController
    private val effects = FakeEffects()

    private class FakeEffects : LiveLocationController.Effects {
        val started = mutableListOf<SessionPlan>()
        var stops = 0
        val enqueued = mutableListOf<String?>()
        override fun startService(plan: SessionPlan) { started += plan }
        override fun stopService() { stops++ }
        override fun enqueueStop(sessionId: String?, reason: String) { enqueued += sessionId }
    }

    private class FakeClient : LocationClient {
        override fun playServicesAvailable() = true
        override fun hasPermission() = true
        override fun hasPrecise() = true
        override suspend fun lastLocation(): Location? = null
        override suspend fun current(highAccuracy: Boolean, maxAgeMs: Long, timeoutMs: Long): Location? = null
        override fun updates(highAccuracy: Boolean, intervalMs: Long, minIntervalMs: Long): Flow<Location> = emptyFlow()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mockk()
        repo = LiveLocationRepository(api)
        controller = LiveLocationController(contextWithPrefs(), repo, FakeClient(), mockk(relaxed = true))
        controller.effects = effects
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun dto(id: String, reason: String = "manual", expiresAt: String? = null) = LiveLocationDto(
        sessionId = id, userId = "me", reason = reason,
        startedAt = "2026-10-26T08:00:00Z", expiresAt = expiresAt, updatedAt = "2026-10-26T08:00:00Z",
    )

    /** The panic hold: start, then the service coming up (what LiveLocationService reports first). */
    private suspend fun startPanic(): SessionPlan {
        assertEquals(StartResult.Started, controller.start(panic = true, minutes = LiveLocationRules.PANIC_MINUTES))
        val plan = effects.started.last()
        controller.onServiceStarted(plan)
        return plan
    }

    // --- the server merged the start into a session it already had --------------------------------

    @Test
    fun `a panic merged into an existing session takes the server's id, and the stop names it`() = runTest(dispatcher) {
        val plan = startPanic()
        coEvery { api.startSharing(any()) } returns SharingResponse(dto(EXISTING, reason = "panic"))
        val answer = repo.start(plan.startRequest(System.currentTimeMillis()))
        val now = controller.onServiceRegistered(plan.sessionId, (answer as AppResult.Success).value)

        assertEquals(EXISTING, now?.sessionId)
        assertTrue(now!!.registered)
        assertTrue(now.panic)
        assertEquals(EXISTING, controller.currentPlan()?.sessionId)
        assertEquals(EXISTING, (controller.state.value as ShareState.Sharing).sessionId)

        coEvery { api.stopSharing(any(), any()) } returns Unit
        controller.stop()
        coVerify(exactly = 1) { api.stopSharing(EXISTING, "stopped") }
        coVerify(exactly = 0) { api.stopSharing(plan.sessionId, any()) }
        assertNull(controller.currentPlan())
    }

    @Test
    fun `a stop between the merged answer and taking its id stops both sessions`() = runTest(dispatcher) {
        val plan = startPanic()
        // The answer reached the repository (it records the server's session), not the controller yet.
        coEvery { api.startSharing(any()) } returns SharingResponse(dto(EXISTING, reason = "panic"))
        repo.start(plan.startRequest(System.currentTimeMillis()))

        coEvery { api.stopSharing(any(), any()) } returns Unit
        controller.stop()
        coVerify(exactly = 1) { api.stopSharing(plan.sessionId, "stopped") }
        coVerify(exactly = 1) { api.stopSharing(EXISTING, "stopped") }
    }

    @Test
    fun `a later answer for a replaced plan is ignored`() = runTest(dispatcher) {
        val plan = startPanic()
        coEvery { api.stopSharing(any(), any()) } returns Unit
        controller.stop()
        assertNull(controller.onServiceRegistered(plan.sessionId, dto(EXISTING)))
        assertNull(controller.currentPlan())
    }

    // --- reconcile on app open --------------------------------------------------------------------

    @Test
    fun `reconcile leaves a running share alone while its start has not reached the server`() = runTest(dispatcher) {
        startPanic()
        coEvery { api.locationSharing() } returns LiveLocationStateDto(mine = null)
        controller.reconcile()
        assertEquals(0, effects.stops)
        assertNotNull(controller.currentPlan())
        assertTrue(controller.state.value is ShareState.Sharing)
    }

    @Test
    fun `reconcile stops a share the server confirmed and no longer has`() = runTest(dispatcher) {
        val plan = startPanic()
        controller.onServiceRegistered(plan.sessionId, dto(plan.sessionId, reason = "panic"))
        coEvery { api.locationSharing() } returns LiveLocationStateDto(mine = null)
        controller.reconcile()
        assertEquals(1, effects.stops)
        assertNull(controller.currentPlan())
        assertEquals(ShareState.Off, controller.state.value)
    }

    @Test
    fun `a panic start never waits behind reconcile's GET, and the older GET is dropped`() = runTest(dispatcher) {
        val gate = CompletableDeferred<LiveLocationStateDto>()
        coEvery { api.locationSharing() } coAnswers { gate.await() }
        val reconcile = launch { controller.reconcile() } // now waiting for the server

        // The start goes straight through (it used to wait up to the 30 s HTTP timeout).
        val plan = startPanic()
        controller.onServiceRegistered(plan.sessionId, dto(plan.sessionId, reason = "panic"))
        // The GET answers with what the server had before the start reached it.
        gate.complete(LiveLocationStateDto(mine = null))
        reconcile.join()

        assertEquals(0, effects.stops)
        assertEquals(plan.sessionId, controller.currentPlan()?.sessionId)
    }

    @Test
    fun `reconcile takes the server's id for a running share whose start is on its way, keeping the panic`() = runTest(dispatcher) {
        startPanic()
        coEvery { api.locationSharing() } returns LiveLocationStateDto(mine = dto(EXISTING, reason = "manual"))
        controller.reconcile()
        val now = controller.currentPlan()!!
        assertEquals(EXISTING, now.sessionId)
        assertTrue("the start still has to tell the server about the panic", now.panic)
        assertFalse(now.registered)
        assertEquals(0, effects.stops)
        // The service's next attempt asks for the panic under the server's id.
        assertEquals("panic", now.startRequest(System.currentTimeMillis()).reason)
    }

    @Test
    fun `a panic whose start never got through resumes when the app opens`() = runTest(dispatcher) {
        startPanic()
        controller.onServiceGone() // Android stopped the service before the start reached the server
        coEvery { api.locationSharing() } returns LiveLocationStateDto(mine = null)
        controller.reconcile()
        assertEquals(2, effects.started.size)
        assertEquals(0, effects.stops)
    }

    // --- stops that could not be sent --------------------------------------------------------------

    @Test
    fun `a stop that could not be sent goes before the next reconcile, and never comes back as Paused`() = runTest(dispatcher) {
        val plan = startPanic()
        controller.onServiceRegistered(plan.sessionId, dto(plan.sessionId, reason = "panic"))
        coEvery { api.stopSharing(any(), any()) } throws IOException("offline")
        controller.stop()
        assertEquals(listOf<String?>(plan.sessionId), effects.enqueued)
        assertFalse(controller.deliverPendingStops(enqueueOnFailure = false))

        // Still offline for the stop: the server still shows the session, which must not come back.
        coEvery { api.locationSharing() } returns LiveLocationStateDto(mine = dto(plan.sessionId, reason = "panic"))
        controller.reconcile()
        assertEquals(ShareState.Off, controller.state.value)
        assertNull(controller.currentPlan())

        // Back online: the stop goes first, then the GET.
        coEvery { api.stopSharing(any(), any()) } returns Unit
        coEvery { api.locationSharing() } returns LiveLocationStateDto(mine = null)
        controller.reconcile()
        coVerifyOrder {
            api.stopSharing(plan.sessionId, "stopped")
            api.locationSharing()
        }
        assertTrue(controller.deliverPendingStops(enqueueOnFailure = false))
        assertEquals(1, effects.started.size)
    }

    // --- durations ---------------------------------------------------------------------------------

    @Test
    fun `linking the SOS sends the time left, not two more hours`() = runTest(dispatcher) {
        val plan = startPanic()
        val sent = slot<StartSharingRequest>()
        coEvery { api.startSharing(capture(sent)) } returns SharingResponse(dto(plan.sessionId, reason = "panic"))
        controller.linkSos(SOS)
        assertEquals(plan.sessionId, sent.captured.sessionId)
        assertEquals(SOS, sent.captured.sosId)
        assertEquals("panic", sent.captured.reason)
        assertTrue(sent.captured.durationMinutes!! in 119..120)
        assertEquals(SOS, controller.currentPlan()?.sosId)
    }

    @Test
    fun `the start request is derived from the end, not from the length first chosen`() {
        val now = 1_000_000_000L
        val timed = SessionPlan("s", panic = false, minutes = 60, startedAtMs = now - 50 * 60_000L, expiresAtMs = now + 10 * 60_000L)
        assertEquals("timed", timed.startRequest(now).mode)
        assertEquals(10, timed.startRequest(now).durationMinutes)
        // A plan rebuilt from the server keeps minutes = UNTIL_OFF but has an end: still timed.
        val rebuilt = timed.copy(minutes = LiveLocationRules.UNTIL_OFF)
        assertEquals("timed", rebuilt.startRequest(now).mode)
        val untilOff = SessionPlan("s", panic = false, minutes = LiveLocationRules.UNTIL_OFF, startedAtMs = now)
        assertEquals("until_off", untilOff.startRequest(now).mode)
        assertNull(untilOff.startRequest(now).durationMinutes)
    }

    private companion object {
        const val EXISTING = "aaaaaaaaaaaaaaaaaaaaaaaa"
        const val SOS = "bbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
