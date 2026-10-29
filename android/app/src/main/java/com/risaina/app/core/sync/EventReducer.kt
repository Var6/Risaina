package com.risaina.app.core.sync

import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.MediaRepository
import com.risaina.app.core.data.PingRepository
import com.risaina.app.core.data.toEntity
import com.risaina.app.core.data.toDomain
import com.risaina.app.core.database.CalendarDao
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.database.MemoryDao
import com.risaina.app.core.database.MessageDao
import com.risaina.app.core.database.MilestoneDao
import com.risaina.app.core.database.PingDao
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.model.Message
import com.risaina.app.core.model.Ping
import com.risaina.app.core.network.CalendarEntryDto
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
    /** Partner changed what my widgets show: widgets refresh, and a quiet "updated your widget" line is posted. */
    data object WidgetChanged : Incoming
    data class Sos(val id: String) : Incoming
    /**
     * The partner did something in a game. Read from the event itself (not the refreshed game) so a
     * notification never waits for a network fetch: [kind] picks the text, [createdAt] lets a full
     * replay skip old ones, [topic] is the trivia category for "5 questions on History".
     */
    data class Game(val id: String, val action: String, val kind: String = "rps", val createdAt: Instant? = null, val topic: String? = null) : Incoming
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
    /** Network follow-ups run after notifications and widgets, never inside apply() (stream: delivery). */
    private val deferred: DeferredEffects,
    private val mediaRepository: MediaRepository,
    private val coupleRepository: CoupleRepository,
    private val listDao: com.risaina.app.core.database.ListDao,
    private val widgetBoardRepository: com.risaina.app.core.data.WidgetBoardRepository,
    private val sosRepository: com.risaina.app.core.data.SosRepository,
    private val safetyEvents: com.risaina.app.core.safety.SafetyEvents,
    private val gameRepository: com.risaina.app.core.data.GameRepository,
    private val calendarDao: CalendarDao,
    /** Only for the chat notification after a read or a delete: local, no network (see refreshConversation). */
    private val notifier: com.risaina.app.core.notifications.RisainaNotifier,
    /** list.deleted: Room only (the list, its tombstone, an orphaned draft); no network. */
    private val listSync: com.risaina.app.core.data.lists.ListSyncEngine,
) {
    /** [replay]: the event is old history being re-applied (ReplayGuard); its receipts are not re-sent. */
    suspend fun apply(event: EventDto, replay: Boolean = false): List<Incoming> {
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
                    // Queued, not sent here: the network call must not delay the notification or widgets.
                    if (!replay) deferred.ackDelivered(ping.id)
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
            "message.read" -> p.str("messageId")?.let { id ->
                p.instant("readAt")?.let { messageDao.markRead(id, it) }
                // Read by this person, maybe on their other phone: its line leaves the chat
                // notification here too. Only rebuilds or removes it, never alerts.
                if (event.actorId == me) notifier.refreshConversation()
            }
            "message.opened" -> p.str("messageId")?.let { id ->
                messageDao.byId(id)?.let { e -> messageDao.upsert(e.copy(openedAt = p.instant("openedAt") ?: Instant.now().toEpochMilli())) }
            }
            "message.deleted" -> p.str("messageId")?.let { id ->
                messageDao.markDeleted(id, p.instant("deletedAt") ?: Instant.now().toEpochMilli())
                // The partner took it back: its text must not stay in the shade.
                if (event.actorId != me) notifier.refreshConversation()
            }
            "media.ready" -> p.obj("media")?.let { mediaRepository.remember(json.decodeFromJsonElement(MediaDto.serializer(), it)) }
            "memory.updated" -> p.obj("memory")?.let { memoryDao.upsert(json.decodeFromJsonElement(MemoryDto.serializer(), it).toEntity()) }
            "memory.deleted" -> p.str("memoryId")?.let { memoryDao.markDeleted(it, p.instant("deletedAt") ?: Instant.now().toEpochMilli()) }
            "milestone.created", "milestone.updated" -> p.obj("milestone")?.let { milestoneDao.upsert(json.decodeFromJsonElement(MilestoneDto.serializer(), it).toEntity()) }
            "milestone.deleted" -> p.str("milestoneId")?.let { milestoneDao.delete(it) }
            "couple.updated" -> p.obj("couple")?.let {
                val couple = json.decodeFromJsonElement(CoupleDto.serializer(), it)
                val partner = coupleDao.partner()
                coupleDao.replace(couple.toEntity(), partner)
                if (couple.status.equals("active", true)) {
                    // Bind the couple here, before the cursor moves past this event, so the queued
                    // refreshMe finds it already bound and does not rewind the cursor to 0 after
                    // this sync (SessionStore.adoptCouple).
                    sessionStore.adoptActiveCoupleId(couple.id)
                    if (partner == null) deferred.refreshMe()
                }
            }
            "partner.updated" -> p.obj("partner")?.let {
                val partner = json.decodeFromJsonElement(PartnerDto.serializer(), it)
                if (partner.id != me) coupleDao.replace(coupleDao.couple(), partner.toEntity())
            }
            // Newer copy wins: this phone's own older saves come back by replay after its newer reply.
            "list.created", "list.updated" -> p.obj("list")?.let {
                listDao.upsertIfNewer(json.decodeFromJsonElement(com.risaina.app.core.network.SharedListDto.serializer(), it).toEntity(json))
            }
            // Remembered so a slow GET /lists cannot bring it back; unsent edits to it become orphaned.
            "list.deleted" -> p.str("listId")?.let { listSync.onDeleted(it) }
            // An entry that does not decode is skipped, not thrown: the next GET /calendar has it.
            "calendar.created", "calendar.updated" -> p.obj("entry")?.let {
                runCatching { json.decodeFromJsonElement(CalendarEntryDto.serializer(), it) }.getOrNull()
                    ?.let { entry -> calendarDao.upsertIfNewer(entry.toEntity()) }
                // TODO(P2): when payload.notify == true, event.actorId != me and !replay, add
                //  Incoming.CalendarShared ("Raina added … to your calendar"); after any calendar,
                //  couple or partner event, queue a local reminder re-arm.
            }
            "calendar.deleted" -> p.str("entryId")?.let { calendarDao.delete(it) } // TODO(P2): re-arm reminders.
            // Wake together is applied by its own package (store, re-arm, partner notices). Until
            // then these are passed over like any unknown type, and the cursor still moves on.
            "alarm.created", "alarm.updated", "alarm.deleted", "alarm.woke" -> Unit // TODO(P3): WakeAlarmRepository/WakeAlarmStore.apply(event, me).
            "widget.updated" -> p.obj("board")?.let {
                val board = json.decodeFromJsonElement(com.risaina.app.core.network.WidgetBoardDto.serializer(), it)
                if (event.actorId == me) widgetBoardRepository.store(board, null, replaceMine = true, replaceForMe = false)
                else { widgetBoardRepository.store(null, board, replaceMine = false, replaceForMe = true); out += Incoming.WidgetChanged }
            }
            "memory.created" -> {
                p.obj("memory")?.let { memoryDao.upsert(json.decodeFromJsonElement(MemoryDto.serializer(), it).toEntity()) }
            }
            // Safety: SOS ring (replay-safe: 6 h window, local state, server re-check) and live location.
            "sos.raised", "sos.acknowledged", "sos.delivered", "sos.resolved", "location.started", "location.stopped" ->
                out += safetyEvents.apply(event, me)
            "game.updated" -> {
                val g = p.obj("game")
                val id = (g?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (id != null) {
                    val action = p.str("action")
                    // No network here (it would eat the push handler's budget): a partner's single trivia
                    // answer is applied from the event, anything else only marks the game stale, and the
                    // deferred queue re-fetches each stale game once after notifications and widgets
                    // (one list request after a replay, which can touch every past game).
                    gameRepository.onEvent(id, action, event.actorId, fromPartner = event.actorId != me, view = g, defer = true)
                    deferred.refreshStaleGames(full = replay)
                    if (event.actorId != me && action != null) {
                        out += Incoming.Game(
                            id, action, kind = g.str("kind") ?: "rps",
                            createdAt = runCatching { Instant.parse(event.createdAt) }.getOrNull(), topic = g.obj("quiz")?.str("categoryName"),
                        )
                    }
                }
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
