package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.database.MessageDao
import com.risaina.app.core.database.MessageEntity
import com.risaina.app.core.database.OutboxDao
import com.risaina.app.core.database.OutboxEntity
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.domain.MessageRules
import com.risaina.app.core.model.Message
import com.risaina.app.core.model.MessageSendState
import com.risaina.app.core.model.MessageType
import com.risaina.app.core.network.CreateMessageRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class OutboxMessage(val type: String, val text: String?, val mediaId: String?, val localKey: String)

/**
 * Messages. Text and emoji are optimistic: written locally as PENDING and queued in the outbox,
 * so they survive offline periods and are sent in order. Media messages are created after the
 * upload completes. Read and delete are server-confirmed.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val api: RisainaApi,
    private val messageDao: MessageDao,
    private val outboxDao: OutboxDao,
    private val coupleDao: CoupleDao,
    private val sessionStore: SessionStore,
    private val time: TimeProvider,
    private val json: Json,
    private val notifier: com.risaina.app.core.notifications.RisainaNotifier,
) {
    fun recent(limit: Int = 200): Flow<List<Message>> = messageDao.recent(limit).map { l -> l.map { it.toDomain() } }
    val latest: Flow<Message?> = messageDao.latest().map { it?.toDomain() }
    @OptIn(ExperimentalCoroutinesApi::class)
    val unreadCount: Flow<Int> = sessionStore.session.flatMapLatest { s ->
        if (s == null) flowOf(0) else messageDao.unreadCount(s.userId)
    }
    val pendingCount: Flow<Int> = outboxDao.count()

    suspend fun sendText(text: String, type: MessageType = MessageType.TEXT): AppResult<Message> {
        val session = sessionStore.current() ?: return AppResult.Failure(AppError.Unauthorized())
        val partner = coupleDao.partner() ?: return AppResult.Failure(AppError.Conflict("not_paired", "You're not paired yet"))
        val trimmed = text.trim()
        when (val v = MessageRules.validate(type, trimmed, null)) {
            is MessageRules.Validation.Invalid -> return AppResult.Failure(AppError.Validation(v.reason))
            MessageRules.Validation.Ok -> Unit
        }
        val localKey = UUID.randomUUID().toString()
        val pending = MessageEntity(
            id = "local:$localKey", localKey = localKey, coupleId = null, senderId = session.userId, recipientId = partner.id,
            type = type.wire, text = trimmed, mediaId = null, createdAt = time.now().toEpochMilli(),
            editedAt = null, deletedAt = null, readAt = null, sendState = MessageSendState.PENDING.name, localMediaPath = null,
        )
        messageDao.upsert(pending)
        outboxDao.upsert(OutboxEntity(localKey, "message", json.encodeToString(OutboxMessage.serializer(), OutboxMessage(type.wire, trimmed, null, localKey)), 0, null, pending.createdAt))
        return post(pending)
    }

    suspend fun sendMedia(type: MessageType, mediaId: String, localMediaPath: String?, viewOnce: Boolean = false): AppResult<Message> {
        val session = sessionStore.current() ?: return AppResult.Failure(AppError.Unauthorized())
        val partner = coupleDao.partner() ?: return AppResult.Failure(AppError.Conflict("not_paired", "You're not paired yet"))
        val localKey = UUID.randomUUID().toString()
        val pending = MessageEntity(
            id = "local:$localKey", localKey = localKey, coupleId = null, senderId = session.userId, recipientId = partner.id,
            type = type.wire, text = null, mediaId = mediaId, createdAt = time.now().toEpochMilli(),
            editedAt = null, deletedAt = null, readAt = null, sendState = MessageSendState.PENDING.name, localMediaPath = localMediaPath,
            viewOnce = viewOnce,
        )
        messageDao.upsert(pending)
        outboxDao.upsert(OutboxEntity(localKey, "message", json.encodeToString(OutboxMessage.serializer(), OutboxMessage(type.wire, null, mediaId, localKey)), 0, null, pending.createdAt))
        return post(pending)
    }

    private suspend fun post(pending: MessageEntity): AppResult<Message> {
        val res = apiCall { api.createMessage(CreateMessageRequest(pending.type, pending.text, pending.mediaId, pending.localKey, viewOnce = pending.viewOnce.takeIf { it })) }
        return when (res) {
            is AppResult.Success -> {
                val sent = res.value.message.toEntity(localMediaPath = pending.localMediaPath)
                messageDao.replaceLocal(pending.localKey, sent)
                outboxDao.deleteByKey(pending.localKey)
                AppResult.Success(sent.toDomain())
            }
            is AppResult.Failure -> {
                val err = res.error
                // Retryable failures stay queued as PENDING; permanent ones become FAILED and leave the outbox.
                if (err.isRetryable) {
                    messageDao.upsert(pending.copy(sendState = MessageSendState.PENDING.name))
                } else {
                    messageDao.upsert(pending.copy(sendState = MessageSendState.FAILED.name))
                    outboxDao.deleteByKey(pending.localKey)
                }
                res
            }
        }
    }

    /** Re-send queued messages in order. Stops at the first retryable failure so order is preserved. */
    suspend fun drainOutbox(): Boolean {
        for (item in outboxDao.all().filter { it.kind == "message" }) {
            val pending = messageDao.byLocalKey(item.localKey)
            if (pending == null) { outboxDao.delete(item); continue }
            val res = post(pending)
            if (res is AppResult.Failure && res.error.isRetryable) {
                outboxDao.upsert(item.copy(attempts = item.attempts + 1, lastError = res.error.message))
                return false
            }
        }
        return true
    }

    suspend fun retry(localKey: String): AppResult<Message> {
        val pending = messageDao.byLocalKey(localKey) ?: return AppResult.Failure(AppError.NotFound())
        val row = pending.copy(sendState = MessageSendState.PENDING.name)
        messageDao.upsert(row)
        return post(row)
    }

    /**
     * Opens a view-once photo: the server hands out a short-lived URL exactly once. The image is
     * never written to disk on this phone; the caller shows it from memory with screenshots blocked.
     */
    suspend fun openViewOnce(id: String): AppResult<String> = apiCall { api.openViewOnce(id) }
        .onSuccess { r -> messageDao.upsert(r.message.toEntity()) }
        .map { it.url }

    suspend fun discard(localKey: String) { messageDao.deleteByLocalKey(localKey); outboxDao.deleteByKey(localKey) }

    /** Once the read is stored, the message's line leaves the chat notification (from any screen that reads it). */
    suspend fun markRead(id: String): AppResult<Unit> {
        val local = messageDao.byId(id)
        if (local == null || local.readAt != null || local.id.startsWith("local:")) return AppResult.Success(Unit)
        return apiCall { api.messageRead(id) }.onSuccess {
            messageDao.upsert(it.message.toEntity(local.localMediaPath))
            notifier.refreshConversation()
        }.map { }
    }

    suspend fun delete(id: String): AppResult<Unit> =
        apiCall { api.deleteMessage(id) }.onSuccess { messageDao.markDeleted(id, Instant.now().toEpochMilli()) }.map { }

    suspend fun refresh(): AppResult<Unit> =
        apiCall { api.messages(limit = 40) }.onSuccess { page -> messageDao.upsertAll(page.items.map { it.toEntity() }) }.map { }

    /** Loads the page before the oldest cached message. Returns true if more may exist. */
    suspend fun loadOlder(): AppResult<Boolean> {
        val oldest = messageDao.oldestCreatedAt() ?: return refresh().map { true }
        val cursor = Instant.ofEpochMilli(oldest).toString()
        return apiCall { api.messages(limit = 40, before = cursor) }.onSuccess { page ->
            messageDao.upsertAll(page.items.map { it.toEntity() })
        }.map { it.nextCursor != null }
    }
}
