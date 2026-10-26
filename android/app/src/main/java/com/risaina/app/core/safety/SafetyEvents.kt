package com.risaina.app.core.safety

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.SosRepository
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.location.LiveLocationController
import com.risaina.app.core.location.LiveLocationRepository
import com.risaina.app.core.network.EventDto
import com.risaina.app.core.network.LiveLocationDto
import com.risaina.app.core.network.SosDto
import com.risaina.app.core.notifications.SosAlert
import com.risaina.app.core.sos.RingRequest
import com.risaina.app.core.sos.RingSource
import com.risaina.app.core.sos.SosAlarmController
import com.risaina.app.core.sos.SosLocalState
import com.risaina.app.core.sos.SosRingPolicy
import com.risaina.app.core.sync.Incoming
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sos.* and location.* branches of the event reducer. Events replay (fresh sign-in, re-pairing,
 * a late sync), so nothing here may ring or notify for something that is over: the SOS ring follows
 * SosRingPolicy and the local SOS state, and notifications are only posted for fresh events (a
 * fresh "is sharing" is re-checked with the server afterwards, outside the apply lock).
 */
@Singleton
class SafetyEvents @Inject constructor(
    private val json: Json,
    private val coupleDao: CoupleDao,
    private val sosRepository: SosRepository,
    private val localState: SosLocalState,
    private val alarm: SosAlarmController,
    private val sosAlert: SosAlert,
    private val liveRepo: LiveLocationRepository,
    private val liveController: dagger.Lazy<LiveLocationController>,
    private val notifier: SafetyNotifier,
    private val deferred: com.risaina.app.core.sync.DeferredEffects,
) {
    suspend fun apply(event: EventDto, me: String): List<Incoming> {
        val p = event.payload
        val now = Instant.now()
        // Notifications only for things that just happened, never for a replay of history.
        val fresh = runCatching { Duration.between(Instant.parse(event.createdAt), now) < Duration.ofMinutes(10) }.getOrDefault(false)
        when (event.type) {
            "sos.raised" -> {
                val sos = p.obj("sos")?.let { json.decodeFromJsonElement(SosDto.serializer(), it) } ?: return emptyList()
                sosRepository.upsert(sos)
                localState.link(event.seq, sos.id)
                return if (onRaised(sos, event.seq, me, now)) listOf(Incoming.Sos(sos.id)) else emptyList()
            }
            "sos.acknowledged" -> {
                val id = p.str("sosId") ?: return emptyList()
                p.str("acknowledgedAt")?.let { sosRepository.markAcknowledged(id, it) }
                localState.markAcked(id, null)
                alarm.stopFor(id, null) // acknowledged on another device (or the in-app screen)
            }
            "sos.delivered" -> {
                val id = p.str("sosId"); val at = p.str("deliveredAt")
                if (id != null && at != null) sosRepository.markDelivered(id, at)
            }
            "sos.resolved" -> {
                val id = p.str("sosId") ?: return emptyList()
                p.str("resolvedAt")?.let { sosRepository.markResolved(id, it) }
                if (event.actorId != me) {
                    localState.markResolved(id)
                    alarm.stopFor(id, null) // only this SOS: a newer, unresolved one keeps ringing
                    if (fresh) sosAlert.postSafeNow(partnerName())
                }
            }
            "location.started" -> {
                val session = p.obj("sharing")?.let { json.decodeFromJsonElement(LiveLocationDto.serializer(), it) } ?: return emptyList()
                if (session.userId == me) {
                    liveController.get().onServerSessionChanged(session)
                } else {
                    liveRepo.onPartnerStarted(session)
                    // Posted from the event itself, with no network here: apply runs under the sync's
                    // apply lock, inside the push handler's few seconds, and this high push exists
                    // for exactly this notification. A stop applied later in the same sync removes
                    // it; the server re-check runs after notifications and widgets (deferred) and
                    // removes it if the share is already gone (a stop still on its way, a purge).
                    if (fresh && SafetyNotifier.announces(session)) {
                        notifier.partnerSharingStarted(partnerName(), session)
                        deferred.checkPartnerLocation()
                    }
                }
            }
            "location.stopped" -> {
                val sessionId = p.str("sessionId") ?: return emptyList()
                if (p.str("userId") == me) {
                    liveController.get().onServerStopped(sessionId)
                } else {
                    liveRepo.onPartnerStopped(sessionId, p.str("stoppedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() })
                    notifier.cancelPartnerSharing()
                }
            }
        }
        return emptyList()
    }

    /** Returns true when it rang (or updated the ringing alarm). */
    private suspend fun onRaised(sos: SosDto, seq: Long, me: String, now: Instant): Boolean {
        if (sos.recipientId != me) return false
        val req = RingRequest(sosId = sos.id, seq = seq, partnerName = partnerName(), message = sos.message, panic = sos.panic, source = RingSource.EVENT)
        // Already ringing for it (the push came first): give it the message and the name.
        if (alarm.isRinging(sos.id, seq)) { alarm.ring(req); return true }
        val decision = SosRingPolicy.forEvent(
            isRecipient = true,
            ackedOrResolvedOnServer = sos.acknowledgedAt != null || sos.resolvedAt != null,
            handledLocally = localState.isHandled(sos.id, seq),
            createdAtIso = sos.createdAt,
            now = now,
        )
        when (decision) {
            SosRingPolicy.EventDecision.SKIP -> return false
            SosRingPolicy.EventDecision.VERIFY_THEN_RING -> {
                // Not brand new: probably a replay. Ask the server; ring if it cannot answer (fail open).
                if (withTimeoutOrNull(3_000) { sosRepository.refresh() } is AppResult.Success) {
                    val current = sosRepository.items.value.firstOrNull { it.id == sos.id }
                    if (current?.acknowledgedAt != null) { localState.markAcked(sos.id, seq); return false }
                    if (current?.resolvedAt != null) { localState.markResolved(sos.id); return false }
                }
            }
            SosRingPolicy.EventDecision.RING -> Unit
        }
        alarm.ring(req)
        return true
    }

    private suspend fun partnerName(): String = runCatching { coupleDao.partner()?.displayName }.getOrNull() ?: "your partner"

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
