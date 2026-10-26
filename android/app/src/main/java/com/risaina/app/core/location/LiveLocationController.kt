package com.risaina.app.core.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.domain.LiveLocationRules
import com.risaina.app.core.domain.LiveLocationRules.Mode
import com.risaina.app.core.network.LiveLocationDto
import com.risaina.app.core.network.LocationFixInput
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
import java.util.concurrent.atomic.AtomicLong
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
    /**
     * The server has confirmed a session with this id (a start was answered, or GET showed it). Until
     * then, a GET without my session only means the start has not arrived yet (offline, server waking
     * up), not that the session ended.
     */
    val registered: Boolean = false,
) {
    val mode: Mode get() = if (panic) Mode.PANIC else Mode.MANUAL

    fun expiredAt(nowMs: Long): Boolean = expiresAtMs?.let { nowMs >= it } == true

    /**
     * The start (or re-register) for this plan. A timed plan sends the time LEFT, so a retry, a Resume
     * or a reconcile restart never moves the end the user chose (the server sets end = now + duration).
     */
    fun startRequest(nowMs: Long, fix: LocationFixInput? = null) = StartSharingRequest(
        sessionId = sessionId,
        mode = if (expiresAtMs != null) "timed" else "until_off",
        durationMinutes = expiresAtMs?.let { LiveLocationRules.requestMinutes(it, nowMs) },
        reason = if (panic) "panic" else "manual",
        sosId = sosId,
        fix = fix,
    )
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
 * needed. Start, stop and resume change the local plan one at a time under one lock, and the session
 * id is made on the phone, so a stop always names the session it ends.
 *
 * The lock is never held across a network call: a panic start must reach startForegroundService
 * while the app is still visible, never queued behind a GET or DELETE on a server that is waking up.
 * Ordering with the server is kept another way: a stop is written down before it is sent, and every
 * new start sends the stops still pending first (see [deliverPendingStops]).
 */
@Singleton
class LiveLocationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: LiveLocationRepository,
    private val client: LocationClient,
    private val sosRepository: com.risaina.app.core.data.SosRepository,
) {
    /** What the controller asks of Android. Replaced in unit tests. */
    internal interface Effects {
        /** Throws when Android refuses (Risaina is no longer visible). */
        fun startService(plan: SessionPlan)
        fun stopService()
        fun enqueueStop(sessionId: String?, reason: String)
    }

    internal var effects: Effects = object : Effects {
        override fun startService(plan: SessionPlan) {
            ContextCompat.startForegroundService(context, LiveLocationService.startIntent(context, plan))
        }
        override fun stopService() {
            context.stopService(Intent(context, LiveLocationService::class.java))
        }
        override fun enqueueStop(sessionId: String?, reason: String) = StopSharingWorker.enqueue(context, sessionId, reason)
    }

    /**
     * Stops requested by the service itself (notification actions, expiry) run here, not in the
     * service's own scope: stopping the service cancels that scope, which would cut the server call
     * that deletes the position.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("live_location_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    /** Bumped on every change to the plan: a GET that left before the change is older than it. */
    private val generation = AtomicLong()
    private val _state = MutableStateFlow<ShareState>(ShareState.Off)
    val state: StateFlow<ShareState> = _state

    @Volatile internal var serviceRunning = false
        private set

    fun currentPlan(): SessionPlan? = prefs.getString(K_PLAN, null)?.let { runCatching { json.decodeFromString(SessionPlan.serializer(), it) }.getOrNull() }
    private fun savePlan(plan: SessionPlan?) {
        prefs.edit { if (plan == null) remove(K_PLAN) else putString(K_PLAN, json.encodeToString(SessionPlan.serializer(), plan)) }
        generation.incrementAndGet()
    }

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
            registered = existing?.registered ?: false,
        )
        savePlan(plan)
        if (_state.value !is ShareState.Sharing) _state.value = ShareState.Starting(plan.panic)
        startService(plan)
    }

    private fun startService(plan: SessionPlan): StartResult = try {
        effects.startService(plan)
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

    /**
     * Stop sharing: the phone stops at once, then the server deletes the position (or later, if
     * offline). Both ids are stopped when the server answered with a session other than the one this
     * phone asked for and the phone has not taken its id yet.
     */
    suspend fun stop(reason: String = "stopped") {
        mutex.withLock { stopLocally(reason) }
        deliverPendingStops(enqueueOnFailure = true)
    }

    /** Under the lock: stop here and write the server stop down before it is sent. */
    private fun stopLocally(reason: String) {
        val plan = currentPlan()
        val serverSide = repo.state.value.mine?.sessionId
        stopLocalInternal()
        val ids = listOfNotNull(plan?.sessionId, serverSide).distinct()
        if (ids.isEmpty()) addPendingStop(null, reason) else ids.forEach { addPendingStop(it, reason) }
    }

    /** Stop on this phone only (the server already ended it: unlink, sign-out, expiry, "I'm safe now"). */
    fun stopLocal() = stopLocalInternal()

    private fun stopLocalInternal() {
        effects.stopService()
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
     *
     * The GET runs outside the lock (a panic start must never wait behind it) and its answer is
     * dropped if the plan changed meanwhile: a start, a stop or a registration is newer than the GET.
     */
    suspend fun reconcile() {
        // A stop that has not reached the server yet goes first: until it does, the server still
        // shows that session, and it must not come back as a Paused banner (or resume as a panic).
        if (!deliverPendingStops(enqueueOnFailure = false)) return
        val gen = generation.get()
        val server = when (val r = repo.refresh()) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return
        }
        mutex.withLock {
            if (generation.get() != gen) return@withLock
            applyServerState(server.mine)
        }
    }

    private fun applyServerState(mine: LiveLocationDto?) {
        val plan = currentPlan()
        val now = System.currentTimeMillis()
        when {
            mine == null -> when {
                plan == null -> if (serviceRunning || _state.value is ShareState.Paused) stopLocalInternal()
                // Confirmed earlier and gone now: the server ended it (expiry, stopped elsewhere,
                // unlink, "I'm safe now").
                plan.registered || plan.expiredAt(now) -> stopLocalInternal()
                // Its start has not reached the server yet (offline, server waking up): the service
                // keeps trying. Stopping here would end a panic share that is about to get through.
                serviceRunning -> Unit
                // A panic whose start never got through, and Android stopped the service meanwhile.
                plan.panic && client.hasPermission() -> startService(plan)
                else -> stopLocalInternal()
            }
            !serviceRunning -> {
                val p = when {
                    plan == null -> planFrom(mine).withServer(mine).copy(registered = true)
                    plan.sessionId == mine.sessionId -> plan.withServer(mine).copy(registered = true)
                    // The server keeps one live session per person: this phone's own start (not sent
                    // yet) will extend that one, so take its id and keep what was asked for (a panic
                    // on either side stays a panic: the server never downgrades one).
                    !plan.registered -> plan.copy(sessionId = mine.sessionId, panic = plan.panic || mine.reason == "panic")
                    else -> planFrom(mine).withServer(mine).copy(registered = true)
                }
                savePlan(p)
                if (p.panic && client.hasPermission()) startService(p)
                else _state.value = ShareState.Paused(p.sessionId, p.panic, p.expiresAtMs?.let(Instant::ofEpochMilli))
            }
            else -> syncMine(mine, Source.SERVER_STATE)
        }
    }

    /** Where an account of my own session came from: only the first two name the live session. */
    private enum class Source { START_ANSWER, SERVER_STATE, EVENT }

    /**
     * Brings the plan in line with the server's account of my session. The server keeps one live
     * session per person: a start with a new id while one is live (a panic held during an "until I
     * turn it off" share, or after a restart) extends that one and answers with ITS id. Fixes and the
     * stop must then name that id, or the server answers 409 and the partner sees a share that never
     * moves.
     */
    private fun syncMine(dto: LiveLocationDto, source: Source) {
        val plan = currentPlan() ?: return
        val next = when {
            plan.sessionId == dto.sessionId -> plan.withServer(dto).copy(registered = true)
            // An event may be the replay of an older session: it never renames the live one.
            source == Source.EVENT -> return
            source == Source.START_ANSWER || plan.registered -> plan.copy(sessionId = dto.sessionId).withServer(dto).copy(registered = true)
            // GET shows a session while this phone's own start is still on its way: take the id and
            // keep what was asked for; the start then extends that session under its id.
            else -> plan.copy(sessionId = dto.sessionId)
        }
        if (next != plan) savePlan(next)
        _state.update { s ->
            if (s is ShareState.Sharing && s.sessionId == plan.sessionId) {
                s.copy(sessionId = next.sessionId, panic = next.panic, expiresAt = next.expiresAtMs?.let(Instant::ofEpochMilli), sosId = next.sosId)
            } else s
        }
    }

    /** location.started for my own session (also from another device): keep expiry and panic in sync. */
    fun onServerSessionChanged(dto: LiveLocationDto) {
        repo.onMine(dto)
        syncMine(dto, Source.EVENT)
    }

    /** location.stopped for my session (the sender said "I'm safe now", or another device stopped it). */
    fun onServerStopped(sessionId: String) {
        if (currentPlan()?.sessionId == sessionId || repo.state.value.mine?.sessionId == sessionId) stopLocalInternal()
    }

    /** Panic sharing may start before the SOS exists (offline, slow server): link them once it does. */
    suspend fun linkSos(sosId: String) {
        val plan = mutex.withLock {
            val p = currentPlan() ?: return
            if (!p.panic || p.sosId == sosId) return
            p.copy(sosId = sosId).also { savePlan(it) }
        }
        // Not registered yet: the service's next start attempt carries the link. Sent anyway, in
        // case that attempt is already on its way without it.
        val r = repo.start(plan.startRequest(System.currentTimeMillis()))
        if (r is AppResult.Success) onServiceRegistered(plan.sessionId, r.value)
    }

    // --- stops that have not reached the server yet --------------------------------------------

    /**
     * Sends the stops still waiting (offline, server down). Returns true when none are left. A new
     * start calls this first: the server would otherwise merge the new session into the one that
     * stop is about to end, and the new share would die with it.
     */
    suspend fun deliverPendingStops(enqueueOnFailure: Boolean): Boolean {
        for (entry in pendingStops()) {
            val (id, reason) = parseStop(entry)
            when (val r = repo.stop(id, reason)) {
                is AppResult.Success -> removePendingStop(entry)
                is AppResult.Failure -> when {
                    // Signed out (the server ended every share of mine at sign-out), or refused for good:
                    // waiting for it would block every later start, a panic one included.
                    !r.error.isRetryable -> removePendingStop(entry)
                    // From stop(): the worker delivers it once the network is back.
                    enqueueOnFailure -> effects.enqueueStop(id, reason)
                    // Before a start or a reconcile: still unreachable, so do not go on yet.
                    else -> return false
                }
            }
        }
        return pendingStops().isEmpty()
    }

    /** StopSharingWorker delivered it. */
    internal fun onStopDelivered(sessionId: String?) {
        pendingStops().filter { parseStop(it).first == sessionId }.forEach(::removePendingStop)
    }

    private fun pendingStops(): Set<String> = synchronized(prefs) { prefs.getStringSet(K_PENDING_STOPS, null)?.toSet().orEmpty() }
    private fun addPendingStop(sessionId: String?, reason: String) = synchronized(prefs) {
        prefs.edit { putStringSet(K_PENDING_STOPS, pendingStops() + "${sessionId ?: ALL}:$reason") }
    }
    private fun removePendingStop(entry: String) = synchronized(prefs) {
        prefs.edit { putStringSet(K_PENDING_STOPS, pendingStops() - entry) }
    }
    private fun parseStop(entry: String): Pair<String?, String> {
        val id = entry.substringBefore(':')
        return (id.takeUnless { it == ALL }) to entry.substringAfter(':', "stopped")
    }

    // --- called by LiveLocationService ----------------------------------------------------------

    internal fun onServiceStarted(plan: SessionPlan) {
        serviceRunning = true
        _state.value = ShareState.Sharing(plan.sessionId, plan.panic, plan.expiresAtMs?.let(Instant::ofEpochMilli), plan.sosId)
    }

    /**
     * The start with [requestedId] was answered. Returns the plan to use from now on (its id is the
     * server's when the server merged the start into a session it already had), or null when the
     * sharing was stopped meanwhile.
     */
    internal suspend fun onServiceRegistered(requestedId: String, dto: LiveLocationDto): SessionPlan? = mutex.withLock {
        val plan = currentPlan() ?: return null
        // A newer plan replaced the one this answer is about.
        if (plan.sessionId != requestedId && plan.sessionId != dto.sessionId) return plan
        syncMine(dto, Source.START_ANSWER)
        currentPlan()
    }

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
        const val K_PENDING_STOPS = "pending_stops"
        /** A pending stop without a session id (stops every share of mine). */
        const val ALL = "*"
    }
}
