package com.risaina.app.core.location

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.network.LiveLocationDto
import com.risaina.app.core.network.LiveLocationStateDto
import com.risaina.app.core.network.PutFixRequest
import com.risaina.app.core.network.PutFixResponse
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.StartSharingRequest
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class LiveLocationState(
    /** My own live session as the server knows it. */
    val mine: LiveLocationDto? = null,
    /** My partner's live session and latest fix. In memory only: never written to disk. */
    val partner: LiveLocationDto? = null,
    /** When the partner stopped (shown briefly as "stopped sharing at"). */
    val partnerStoppedAt: Instant? = null,
)

/**
 * Live location state, in memory only (the partner's position is never stored on this phone). Fed by
 * GET /location/sharing (on open, reconnect and while a location card is on screen) and by the
 * WebSocket `location` frames.
 */
@Singleton
class LiveLocationRepository @Inject constructor(private val api: RisainaApi) {
    private val _state = MutableStateFlow(LiveLocationState())
    val state: StateFlow<LiveLocationState> = _state

    /** Sessions the partner stopped: a late frame for one of them must not bring it back. */
    private val stopped: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** Bumped by every answer about my own session, so an older GET cannot overwrite a newer answer. */
    private val mineVersion = AtomicLong()

    /** GET /location/sharing. Returns what the server said (my session as well as the partner's). */
    suspend fun refresh(): AppResult<LiveLocationStateDto> {
        val before = mineVersion.get()
        return apiCall { api.locationSharing() }.onSuccess { r ->
            _state.update { s ->
                val partner = r.partner?.takeUnless { it.sessionId in stopped }
                // A start or stop answered while this GET was on its way is newer than what the GET saw.
                val mine = if (mineVersion.get() == before) r.mine else s.mine
                s.copy(mine = mine, partner = partner, partnerStoppedAt = if (partner != null) null else s.partnerStoppedAt)
            }
        }
    }

    fun onLiveFrame(dto: LiveLocationDto) {
        if (dto.sessionId in stopped) return
        _state.update { it.copy(partner = dto, partnerStoppedAt = null) }
    }

    /** location.started from the partner: keep the latest fix if it is the same session. */
    fun onPartnerStarted(session: LiveLocationDto) {
        if (session.sessionId in stopped) return
        _state.update { s ->
            val current = s.partner
            val merged = if (current?.sessionId == session.sessionId) {
                current.copy(reason = session.reason, expiresAt = session.expiresAt, sosId = session.sosId, updatedAt = session.updatedAt)
            } else session
            s.copy(partner = merged, partnerStoppedAt = null)
        }
    }

    fun onPartnerStopped(sessionId: String, at: Instant?) {
        stopped += sessionId
        if (stopped.size > 50) synchronized(stopped) { stopped.iterator().let { it.next(); it.remove() } }
        _state.update { s -> if (s.partner?.sessionId == sessionId) s.copy(partner = null, partnerStoppedAt = at ?: Instant.now()) else s }
    }

    fun onMine(dto: LiveLocationDto?) {
        mineVersion.incrementAndGet()
        _state.update { it.copy(mine = dto) }
    }

    fun clear() {
        stopped.clear()
        _state.value = LiveLocationState()
    }

    suspend fun start(req: StartSharingRequest): AppResult<LiveLocationDto> =
        apiCall { api.startSharing(req) }.map { it.sharing }.onSuccess { onMine(it) }

    suspend fun putFix(req: PutFixRequest): AppResult<PutFixResponse> = apiCall { api.putFix(req) }

    suspend fun stop(sessionId: String?, reason: String): AppResult<Unit> =
        apiCall { api.stopSharing(sessionId, reason) }.onSuccess { onMine(null) }
}
