package com.risaina.app.core.sync

import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.MediaRepository
import com.risaina.app.core.data.PingRepository
import com.risaina.app.core.data.toEntity
import com.risaina.app.core.data.toDomain
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.database.MemoryDao
import com.risaina.app.core.database.MessageDao
import com.risaina.app.core.database.MilestoneDao
import com.risaina.app.core.database.PingDao
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.model.Message
import com.risaina.app.core.model.Ping
import com.risaina.app.core.network.CoupleDto
import com.risaina.app.core.network.EventDto
import com.risaina.app.core.network.MediaDto
import com.risaina.app.core.network.MemoryDto
import com.risaina.app.core.network.MessageDto
import com.risaina.app.core.network.MilestoneDto
import com.risaina.app.core.network.PartnerDto
import com.risaina.app.core.network.PingDto
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Something the partner did that the user has not seen yet. Callers decide whether to notify. */
sealed interface Incoming {
    data class NewPing(val ping: Ping) : Incoming
    data class NewMessage(val message: Message) : Incoming
    data object Unlinked : Incoming
    /** Partner changed what my widgets show (no notification; widgets refresh). */
    data object WidgetChanged : Incoming
    data class Sos(val id: String) : Incoming
}

/**
 * Applies durable events to local storage idempotently. Events are keyed by `seq`; anything at or
 * below the stored cursor is ignored. Returns the incoming items that concern the user so the
 * caller (foreground reveal or background notification) can act.
 */
@Singleton
class EventReducer @Inject constructor(
    private val json: Json,
    private val sessionStore: SessionStore,
    private val pingDao: PingDao,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao,
    private val milestoneDao: MilestoneDao,
    private val coupleDao: CoupleDao,
    private val pingRepository: PingRepository,
    private val mediaRepository: MediaRepository,
    private val coupleRepository: CoupleRepository,
    private val listDao: com.risaina.app.core.database.ListDao,
    private val widgetBoardRepository: com.risaina.app.core.data.WidgetBoardRepository,
    private val sosRepository: com.risaina.app.core.data.SosRepository,
    private val sosAlert: com.risaina.app.core.notifications.SosAlert,
) {
    suspend fun apply(event: EventDto): List<Incoming> {
        val me = sessionStore.current()?.userId ?: return emptyList()
        val cursor = sessionStore.lastEventSeq.first()
        if (event.seq <= cursor) return emptyList()
        val out = mutableListOf<Incoming>()
        val p = event.payload
        when (event.type) {
            "ping.created" -> p.obj("ping")?.let { dto ->
                val ping = json.decodeFromJsonElement(PingDto.serializer(), dto)
                val existing = pingDao.byId(ping.id)
                pingDao.upsert(ping.toEntity().let { e -> if (existing != null && rank(existing.status) > rank(e.status)) existing else e })
                if (ping.recipientId == me) {
                    // Evidence of delivery: this device has the ping. Best effort; the server timestamps it.
                    pingRepository.acknowledgeDelivered(ping.id)
                    if (existing == null) out += Incoming.NewPing(pingDao.byId(ping.id)!!.toDomain())
                }
            }
            "ping.delivered" -> p.str("pingId")?.let { id ->
                pingDao.byId(id)?.let { e -> if (rank(e.status) < rank("DELIVERED")) pingDao.upsert(e.copy(status = "DELIVERED", deliveredAt = p.instant("deliveredAt"))) }
            }
            "ping.seen" -> p.str("pingId")?.let { id ->
                pingDao.byId(id)?.let { e -> pingDao.upsert(e.copy(status = "SEEN", seenAt = p.instant("seenAt"), deliveredAt = e.deliveredAt ?: p.instant("seenAt"))) }
            }
            "message.created" -> p.obj("message")?.let { dto ->
                val msg = json.decodeFromJsonElement(MessageDto.serializer(), dto)
                val local = messageDao.byLocalKey(msg.idempotencyKey)
                messageDao.replaceLocal(msg.idempotencyKey, msg.toEntity(localMediaPath = local?.localMediaPath))
                if (msg.senderId != me && local == null) out += Incoming.NewMessage(msg.toEntity().toDomain())
            }
            "message.read" -> p.str("messageId")?.let { id -> p.instant("readAt")?.let { messageDao.markRead(id, it) } }
            "message.opened" -> p.str("messageId")?.let { id ->
                messageDao.byId(id)?.let { e -> messageDao.upsert(e.copy(openedAt = p.instant("openedAt") ?: Instant.now().toEpochMilli())) }
            }
            "message.deleted" -> p.str("messageId")?.let { id -> messageDao.markDeleted(id, p.instant("deletedAt") ?: Instant.now().toEpochMilli()) }
            "media.ready" -> p.obj("media")?.let { mediaRepository.remember(json.decodeFromJsonElement(MediaDto.serializer(), it)) }
            "memory.updated" -> p.obj("memory")?.let { memoryDao.upsert(json.decodeFromJsonElement(MemoryDto.serializer(), it).toEntity()) }
            "memory.deleted" -> p.str("memoryId")?.let { memoryDao.markDeleted(it, p.instant("deletedAt") ?: Instant.now().toEpochMilli()) }
            "milestone.created", "milestone.updated" -> p.obj("milestone")?.let { milestoneDao.upsert(json.decodeFromJsonElement(MilestoneDto.serializer(), it).toEntity()) }
            "milestone.deleted" -> p.str("milestoneId")?.let { milestoneDao.delete(it) }
            "couple.updated" -> p.obj("couple")?.let {
                val couple = json.decodeFromJsonElement(CoupleDto.serializer(), it)
                val partner = coupleDao.partner()
                coupleDao.replace(couple.toEntity(), partner)
                if (couple.status.equals("active", true) && partner == null) coupleRepository.refreshMe()
            }
            "partner.updated" -> p.obj("partner")?.let {
                val partner = json.decodeFromJsonElement(PartnerDto.serializer(), it)
                if (partner.id != me) coupleDao.replace(coupleDao.couple(), partner.toEntity())
            }
            "list.created", "list.updated" -> p.obj("list")?.let {
                listDao.upsert(json.decodeFromJsonElement(com.risaina.app.core.network.SharedListDto.serializer(), it).toEntity(json))
            }
            "list.deleted" -> p.str("listId")?.let { listDao.delete(it) }
            "widget.updated" -> p.obj("board")?.let {
                val board = json.decodeFromJsonElement(com.risaina.app.core.network.WidgetBoardDto.serializer(), it)
                if (event.actorId == me) widgetBoardRepository.store(board, null, replaceMine = true, replaceForMe = false)
                else { widgetBoardRepository.store(null, board, replaceMine = false, replaceForMe = true); out += Incoming.WidgetChanged }
            }
            "memory.created" -> {
                p.obj("memory")?.let { memoryDao.upsert(json.decodeFromJsonElement(MemoryDto.serializer(), it).toEntity()) }
            }
            "sos.raised" -> p.obj("sos")?.let {
                val sos = json.decodeFromJsonElement(com.risaina.app.core.network.SosDto.serializer(), it)
                sosRepository.upsert(sos)
                if (sos.recipientId == me && sos.acknowledgedAt == null) {
                    // Ring (or update the ringing alert with the message) whether or not the app is open.
                    sosAlert.show(coupleDao.partner()?.displayName ?: "your partner", sos.message, sos.id)
                    out += Incoming.Sos(sos.id)
                }
            }
            "sos.acknowledged" -> {
                val id = p.str("sosId"); val at = p.str("acknowledgedAt")
                if (id != null && at != null) sosRepository.markAcknowledged(id, at)
            }
            "couple.unlinked" -> { out += Incoming.Unlinked }
        }
        sessionStore.setLastEventSeq(event.seq)
        if (out.any { it is Incoming.Unlinked }) coupleRepository.onUnlinked()
        return out
    }

    private fun rank(status: String) = when (status.uppercase()) { "PENDING" -> 0; "ACCEPTED" -> 1; "DELIVERED" -> 2; "SEEN" -> 3; else -> -1 }
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.instant(key: String): Long? = str(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}
