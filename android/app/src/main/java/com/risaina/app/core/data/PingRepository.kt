package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.database.PingDao
import com.risaina.app.core.database.PingEntity
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.domain.PingRules
import com.risaina.app.core.model.Ping
import com.risaina.app.core.model.PingStatus
import com.risaina.app.core.model.PingType
import com.risaina.app.core.network.CreatePingRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Touch Ping, sender side. A ping is written locally as PENDING with a fresh idempotency key,
 * posted, and then replaced by the server's record (ACCEPTED). It is never shown as delivered
 * until the server reports it; it is never queued silently while offline.
 */
@Singleton
class PingRepository @Inject constructor(
    private val api: RisainaApi,
    private val pingDao: PingDao,
    private val coupleDao: CoupleDao,
    private val sessionStore: SessionStore,
    private val preferencesStore: PreferencesStore,
    private val time: TimeProvider,
) {
    val recent: Flow<List<Ping>> = pingDao.recent(50).map { l -> l.map { it.toDomain() } }

    val latestReceived: Flow<Ping?> = sessionStore.session.combine(pingDao.recent(50)) { s, pings ->
        val me = s?.userId ?: return@combine null
        pings.firstOrNull { it.senderId != me }?.toDomain()
    }

    /** When the next ping may be sent (null = now). Combine with a clock to show a live countdown. */
    val cooldownEndsAt: Flow<java.time.Instant?> = combine(sessionStore.lastPingSentAt, preferencesStore.preferences) { last, prefs ->
        last?.plusSeconds(prefs.pingCooldownSeconds.toLong())
    }

    /** Seconds remaining right now; only used for the send-time check. */
    val cooldownRemaining: Flow<Long> = combine(sessionStore.lastPingSentAt, preferencesStore.preferences) { last, prefs ->
        PingRules.cooldownRemaining(last, prefs.pingCooldownSeconds, time.now())
    }

    suspend fun send(type: PingType, emoji: String, message: String?): AppResult<Ping> {
        val session = sessionStore.current() ?: return AppResult.Failure(AppError.Unauthorized())
        val partner = coupleDao.partner() ?: return AppResult.Failure(AppError.Conflict("not_paired", "You're not paired yet"))
        PingRules.validateEmoji(emoji)?.let { return AppResult.Failure(AppError.Validation(it)) }
        PingRules.validateMessage(message)?.let { return AppResult.Failure(AppError.Validation(it)) }
        val remaining = cooldownRemaining.first()
        if (remaining > 0) return AppResult.Failure(AppError.RateLimited(remaining.toInt()))

        val localKey = UUID.randomUUID().toString()
        val pending = PingEntity(
            id = "local:$localKey", localKey = localKey, coupleId = null, senderId = session.userId, recipientId = partner.id,
            type = type.wire, emoji = emoji, message = message?.takeIf { it.isNotBlank() }, status = PingStatus.PENDING.name,
            createdAt = time.now().toEpochMilli(), deliveredAt = null, seenAt = null, failureReason = null,
        )
        pingDao.upsert(pending)
        return post(pending)
    }

    suspend fun retry(localKey: String): AppResult<Ping> {
        val existing = pingDao.byLocalKey(localKey) ?: return AppResult.Failure(AppError.NotFound())
        if (!PingRules.canTransition(PingStatus.valueOf(existing.status), PingStatus.PENDING)) {
            return AppResult.Success(existing.toDomain())
        }
        val pending = existing.copy(status = PingStatus.PENDING.name, failureReason = null)
        pingDao.upsert(pending)
        return post(pending)
    }

    private suspend fun post(pending: PingEntity): AppResult<Ping> {
        val res = apiCall {
            api.createPing(CreatePingRequest(type = pending.type, emoji = pending.emoji, message = pending.message, idempotencyKey = pending.localKey))
        }
        return when (res) {
            is AppResult.Success -> {
                val accepted = res.value.ping.toEntity()
                pingDao.replaceLocal(pending.localKey, accepted)
                sessionStore.setLastPingSentAt(time.now())
                AppResult.Success(accepted.toDomain())
            }
            is AppResult.Failure -> {
                pingDao.upsert(pending.copy(status = PingStatus.FAILED.name, failureReason = res.error.message))
                res
            }
        }
    }

    suspend fun discardFailed(localKey: String) { pingDao.deleteByLocalKey(localKey) }

    /** Recipient-side acknowledgements: evidence the server records. */
    suspend fun acknowledgeDelivered(id: String): AppResult<Unit> =
        apiCall { api.pingDelivered(id) }.onSuccess { pingDao.upsert(it.ping.toEntity()) }.map { }

    suspend fun markSeen(id: String): AppResult<Unit> {
        val local = pingDao.byId(id)
        if (local?.seenAt != null) return AppResult.Success(Unit)
        return apiCall { api.pingSeen(id) }.onSuccess { pingDao.upsert(it.ping.toEntity()) }.map { }
    }

    suspend fun refresh(): AppResult<Unit> =
        apiCall { api.pings(limit = 50) }.onSuccess { page -> pingDao.upsertAll(page.items.map { it.toEntity() }) }.map { }
}
