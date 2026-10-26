package com.risaina.app.core.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.domain.LiveLocationRules
import com.risaina.app.core.domain.LiveLocationRules.Mode
import com.risaina.app.core.network.LiveLocationDto
import com.risaina.app.core.network.StartSharingRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The session this phone is (or was) sharing. Persisted, so a stop after a restart can still name it. */
@Serializable
data class SessionPlan(
    val sessionId: String,
    val panic: Boolean,
    /** Minutes, or [LiveLocationRules.UNTIL_OFF]. */
    val minutes: Int,
    val sosId: String? = null,
    val startedAtMs: Long,
    val expiresAtMs: Long? = null,
) {
    val mode: Mode get() = if (panic) Mode.PANIC else Mode.MANUAL
}

sealed interface ShareState {
    data object Off : ShareState
    data class Starting(val panic: Boolean) : ShareState
    data class Sharing(
        val sessionId: String,
        val panic: Boolean,
        val expiresAt: Instant?,
        val sosId: String?,
        val lastUploadAt: Instant? = null,
        /** Panic keeps sharing locally when the account was signed out elsewhere; uploads wait. */
        val signInNeeded: Boolean = false,
    ) : ShareState
    /** The app was closed and Android stopped the sharing; the server session is still on. */
    data class Paused(val sessionId: String, val panic: Boolean, val expiresAt: Instant?) : ShareState
    data class Failed(val reason: FailReason) : ShareState
}

enum class FailReason { NO_PERMISSION, NO_PLAY_SERVICES, START_REFUSED }

/**
 * Owns "am I sharing my location". Sharing starts only from a visible screen (a tap, the panic hold,
 * the Resume banner, or the alarm screen): Android gives a location foreground service location only
 * when it was started while the app was visible, which is why no background-location permission is
 * needed. Start, stop and resume run one at a time (a stop waits for an in-flight start), and the
 * session id is made on the phone, so a stop always names the session it ends.
 */
@Singleton
class LiveLocationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: LiveLocationRepository,
    private val client: LocationClient,
    private val sosRepository: com.risaina.app.core.data.SosRepository,
) {
    /**
     * Stops requested by the service itself (notification actions, expiry) run here, not in the
     * service's own scope: stopping the service cancels that scope, which would cut the server call
     * that deletes the position.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("live_location_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ShareState>(ShareState.Off)
    val state: StateFlow<ShareState> = _state

    @Volatile internal var serviceRunning = false
        private set

    fun currentPlan(): SessionPlan? = prefs.getString(K_PLAN, null)?.let { runCatching { json.decodeFromString(SessionPlan.serializer(), it) }.getOrNull() }
    private fun savePlan(plan: SessionPlan?) = prefs.edit { if (plan == null) remove(K_PLAN) else putString(K_PLAN, json.encodeToString(SessionPlan.serializer(), plan)) }

    sealed interface StartResult {
        data object Started : StartResult
        data class Failed(val reason: FailReason) : StartResult
    }

    /**
     * Start or extend sharing. Call only from a visible screen. A manual start never turns a panic
     * session back into a manual one, and panic never shortens a session.
     */
    suspend fun start(panic: Boolean, minutes: Int, sosId: String? = null): StartResult = mutex.withLock {
        if (!client.playServicesAvailable()) return fail(FailReason.NO_PLAY_SERVICES)
        if (!client.hasPermission()) return fail(FailReason.NO_PERMISSION)
        val now = System.currentTimeMillis()
        val existing = currentPlan()?.takeIf { serviceRunning || _state.value is ShareState.Paused || _state.value is ShareState.Sharing }
        val effectivePanic = panic || existing?.panic == true
        val requestedEnd = if (minutes > 0) now + minutes * 60_000L else null
        val end = when {
            effectivePanic && existing != null -> laterEnd(existing.expiresAtMs, requestedEnd)
            else -> requestedEnd
        }
        val plan = SessionPlan(
            sessionId = existing?.sessionId ?: LiveLocationRules.newSessionId(now / 1000),
            panic = effectivePanic,
            minutes = if (end == null) LiveLocationRules.UNTIL_OFF else ((end - now) / 60_000L).toInt().coerceIn(15, 1440),
            sosId = sosId ?: existing?.sosId,
            startedAtMs = existing?.startedAtMs ?: now,
            expiresAtMs = end,
        )
        savePlan(plan)
        if (_state.value !is ShareState.Sharing) _state.value = ShareState.Starting(plan.panic)
        startService(plan)
    }

    private fun startService(plan: SessionPlan): StartResult = try {
        ContextCompat.startForegroundService(context, LiveLocationService.startIntent(context, plan))
        StartResult.Started
    } catch (_: Exception) {
        // Not visible any more (Android 12+ refuses background starts): the user must open Risaina.
        savePlan(null)
        fail(FailReason.START_REFUSED)
    }

    private fun fail(reason: FailReason): StartResult {
        if (!serviceRunning) _state.value = ShareState.Failed(reason)
        return StartResult.Failed(reason)
    }

    /** Stop sharing: the phone stops at once, then the server deletes the position (or later, if offline). */
    suspend fun stop(reason: String = "stopped") = mutex.withLock { stopInternal(reason) }

    private suspend fun stopInternal(reason: String) {
        val plan = currentPlan()
        stopLocalInternal()
        val r = repo.stop(plan?.sessionId, reason)
        if (r is AppResult.Failure && r.error !is AppError.Unauthorized) StopSharingWorker.enqueue(context, plan?.sessionId, reason)
    }

    /** Stop on this phone only (the server already ended it: unlink, sign-out, expiry, "I'm safe now"). */
    fun stopLocal() = stopLocalInternal()

    private fun stopLocalInternal() {
        context.stopService(Intent(context, LiveLocationService::class.java))
        savePlan(null)
        _state.value = ShareState.Off
        repo.onMine(null)
    }

    /** "Resume" on the Paused banner (a visible screen). */
    suspend fun resume(): StartResult = mutex.withLock {
        val plan = currentPlan() ?: return StartResult.Failed(FailReason.START_REFUSED)
        if (!client.hasPermission()) return fail(FailReason.NO_PERMISSION)
        startService(plan)
    }

    /**
     * On every app foreground: compare with the server. A panic session that Android stopped resumes
     * by itself (the app is visible now, so it may); a manual one shows a Paused banner instead.
     */
    suspend fun reconcile() = mutex.withLock {
        if (repo.refresh() is AppResult.Failure) return@withLock
        val mine = repo.state.value.mine
        val plan = currentPlan()
        when {
            mine == null && (serviceRunning || plan != null || _state.value is ShareState.Paused) -> stopLocalInternal()
            mine != null && !serviceRunning -> {
                val p = (plan?.takeIf { it.sessionId == mine.sessionId } ?: planFrom(mine)).withServer(mine)
                savePlan(p)
                if (p.panic && client.hasPermission()) startService(p)
                else _state.value = ShareState.Paused(p.sessionId, p.panic, p.expiresAtMs?.let(Instant::ofEpochMilli))
            }
            mine != null -> onServerSessionChangedInternal(mine)
        }
    }

    /** location.started for my own session (also from another device): keep expiry and panic in sync. */
    fun onServerSessionChanged(dto: LiveLocationDto) = onServerSessionChangedInternal(dto)

    private fun onServerSessionChangedInternal(dto: LiveLocationDto) {
        repo.onMine(dto)
        val plan = currentPlan()?.takeIf { it.sessionId == dto.sessionId } ?: return
        val next = plan.withServer(dto)
        savePlan(next)
        _state.update { s -> if (s is ShareState.Sharing && s.sessionId == dto.sessionId) s.copy(panic = next.panic, expiresAt = next.expiresAtMs?.let(Instant::ofEpochMilli), sosId = next.sosId) else s }
    }

    /** location.stopped for my session (the sender said "I'm safe now", or another device stopped it). */
    fun onServerStopped(sessionId: String) {
        if (currentPlan()?.sessionId == sessionId || repo.state.value.mine?.sessionId == sessionId) stopLocalInternal()
    }

    /** Panic sharing may start before the SOS exists (offline, slow server): link them once it does. */
    suspend fun linkSos(sosId: String) {
        val plan = currentPlan() ?: return
        if (!plan.panic || plan.sosId == sosId) return
        savePlan(plan.copy(sosId = sosId))
        repo.start(StartSharingRequest(sessionId = plan.sessionId, mode = "timed", durationMinutes = LiveLocationRules.PANIC_MINUTES, reason = "panic", sosId = sosId))
    }

    // --- called by LiveLocationService ----------------------------------------------------------

    internal fun onServiceStarted(plan: SessionPlan) {
        serviceRunning = true
        _state.value = ShareState.Sharing(plan.sessionId, plan.panic, plan.expiresAtMs?.let(Instant::ofEpochMilli), plan.sosId)
    }

    internal fun onServiceRegistered(dto: LiveLocationDto) = onServerSessionChangedInternal(dto)

    internal fun onUploaded(signInNeeded: Boolean = false) = _state.update { s ->
        if (s is ShareState.Sharing) s.copy(lastUploadAt = if (signInNeeded) s.lastUploadAt else Instant.now(), signInNeeded = signInNeeded) else s
    }

    /** The service is going away. Killed by Android while a plan remains: Paused (reconcile decides). */
    internal fun onServiceGone() {
        serviceRunning = false
        val plan = currentPlan()
        _state.value = if (plan != null) ShareState.Paused(plan.sessionId, plan.panic, plan.expiresAtMs?.let(Instant::ofEpochMilli)) else ShareState.Off
    }

    /** The server says the session is over (expired, stopped elsewhere, unlinked) or the user is signed out. */
    internal fun endFromService(reason: String, tellServer: Boolean) {
        appScope.launch { if (tellServer) stop(reason) else mutex.withLock { stopLocalInternal() } }
    }

    /** "Stop sharing" from the notification. */
    fun stopAsync(reason: String = "stopped") {
        appScope.launch { stop(reason) }
    }

    /** "I'm safe now" from the notification: stops the partner's alarm, then sharing. */
    fun safeNowAsync(sosId: String?) {
        appScope.launch {
            if (sosId != null) sosRepository.resolve(sosId)
            stop("stopped")
        }
    }

    private fun planFrom(dto: LiveLocationDto): SessionPlan {
        val started = runCatching { Instant.parse(dto.startedAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
        return SessionPlan(dto.sessionId, dto.reason == "panic", LiveLocationRules.UNTIL_OFF, dto.sosId, started)
    }

    private fun SessionPlan.withServer(dto: LiveLocationDto): SessionPlan {
        val end = dto.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        return copy(
            panic = dto.reason == "panic",
            sosId = dto.sosId ?: sosId,
            expiresAtMs = end,
            minutes = if (end == null) LiveLocationRules.UNTIL_OFF else minutes,
        )
    }

    private fun laterEnd(a: Long?, b: Long?): Long? = if (a == null || b == null) null else maxOf(a, b)

    private companion object {
        const val K_PLAN = "plan"
    }
}
