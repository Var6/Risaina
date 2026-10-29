package com.risaina.app.core.alarm

import android.content.Context
import androidx.core.content.edit
import com.risaina.app.core.data.toDomain
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.network.AlarmDto
import com.risaina.app.core.network.AlarmWakeDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class WakePhase { RINGING, SNOOZED, UP, MISSED }

/** The ring on this phone right now (one at a time), or the last one, kept for "you're up" and "is up too". */
@Serializable
data class WakeRing(
    val alarmId: String,
    val occurrence: Long,
    val phase: WakePhase = WakePhase.RINGING,
    val label: String,
    val snoozeMinutes: Int = 10,
    /** When the current ring started (each snooze ends in a new one); its timeout is matched by it. */
    val startedAt: Long,
    /** Snoozes the person chose (reported with "I'm up"). */
    val snoozes: Int = 0,
    /** The one automatic snooze after a ring nobody answered; the next unanswered ring is missed. */
    val autoSnoozed: Boolean = false,
    /** Snoozed until. */
    val until: Long? = null,
    /** "Test on this phone": nothing is reported and no next ring is set. */
    val test: Boolean = false,
    /** The partner said "I'm up" for this ring. */
    val partnerUpAt: Long? = null,
    /** "I'm up" here, or given up. */
    val doneAt: Long? = null,
    /** A good-morning ping went to the partner from the Wake screen for this ring. */
    val goodMorningSent: Boolean = false,
) {
    val active: Boolean get() = phase == WakePhase.RINGING || phase == WakePhase.SNOOZED
}

/** One alarm's readiness as last sent to the server (or waiting to be sent). */
@Serializable
data class ReportedItem(val ready: Boolean, val reason: String? = null, val armedFor: Long? = null)

/**
 * Everything Wake together keeps on this phone. [alarms] are the server's copies, as JSON, so a
 * later field or mode survives; the rest is this phone's own state.
 */
@Serializable
data class WakeLocal(
    /** This person's user id, so the receivers know which member is "me" without the session store. */
    val me: String? = null,
    val partnerName: String? = null,
    val alarms: List<AlarmDto> = emptyList(),
    /** When each alarm was last written here (a GET that started earlier must not undo it). */
    val syncedAt: Map<String, Long> = emptyMap(),
    /** Alarm id → the occurrence its FIRE is set for. */
    val armed: Map<String, Long> = emptyMap(),
    /** Alarm id → the last occurrence that rang (or was missed) here: it never rings twice. */
    val handled: Map<String, Long> = emptyMap(),
    val ring: WakeRing? = null,
    val reported: Map<String, ReportedItem> = emptyMap(),
    /** What the next report sends (WakeReportWorker), null when nothing is waiting. */
    val pendingReport: Map<String, ReportedItem>? = null,
    /** Alarms the partner's "set Wake together" notice was posted for (or that were here already). */
    val announced: Set<String> = emptySet(),
    /** The first full refresh after sign-in is done: alarms seen from then on are new. */
    val primed: Boolean = false,
    /** This person's own ring switch, set here and not yet confirmed by the server. */
    val pendingRing: Map<String, Boolean> = emptyMap(),
) {
    /** The alarms as models, with this person's unconfirmed switch applied. Unreadable ones are left out. */
    fun domain(): List<WakeAlarm> = alarms.mapNotNull { dto ->
        val a = dto.toDomain() ?: return@mapNotNull null
        val override = pendingRing[a.id]
        if (override == null || me == null) a else a.copy(members = a.members.map { if (it.userId == me) it.copy(ring = override) else it })
    }

    fun alarm(id: String): WakeAlarm? = domain().firstOrNull { it.id == id }
}

/**
 * Wake together on this phone, in SharedPreferences, outside Room: Room is a cache that a schema
 * change wipes, and the receivers (boot, the alarm itself) must read the schedule synchronously
 * with no database or network. One JSON document, written whole under [lock], so the alarm
 * receiver, the sync and the UI never interleave half-changes. Cleared on sign-out and unlink.
 */
@Singleton
class WakeAlarmStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("wake_alarms", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    /** Every read-change-write of this store and every arming holds this. */
    val lock = Any()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<WakeLocal> = _state

    /**
     * Alarms deleted here or by the partner since this process started, with when: a GET that was
     * already on its way must not bring one back. In memory only; a GET outlives no process.
     */
    private val deleted = HashMap<String, Long>()

    /**
     * Bumped by every [clear] (sign-out, unlink). A GET or an event that started before it carries
     * the old couple's alarms, so its writes go through [ifCurrent] and are dropped. In memory
     * only, like [deleted]: neither outlives the process.
     */
    @Volatile var generation: Long = 0L
        private set

    fun current(): WakeLocal = _state.value

    /** Runs [block] under the lock, unless the store was cleared since [generation] was read: then null. */
    fun <T> ifCurrent(generation: Long, block: () -> T): T? = synchronized(lock) {
        if (generation == this.generation) block() else null
    }

    private fun load(): WakeLocal = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString(WakeLocal.serializer(), it) }
    }.getOrNull() ?: WakeLocal()

    fun update(change: (WakeLocal) -> WakeLocal): WakeLocal = synchronized(lock) {
        val before = _state.value
        val next = change(before)
        if (next != before) {
            prefs.edit { putString(KEY, json.encodeToString(WakeLocal.serializer(), next)) }
            _state.value = next
        }
        next
    }

    fun clear() = synchronized(lock) {
        generation++
        deleted.clear()
        prefs.edit { remove(KEY) }
        _state.value = WakeLocal()
    }

    fun setIdentity(me: String?, partnerName: String?) = update { it.copy(me = me, partnerName = partnerName ?: it.partnerName) }

    /**
     * One server copy (an event, or a reply to this phone's own change). An older version than the
     * stored one is a replay and is dropped; the same version replaces it (a woke update keeps the
     * version). Returns whether this alarm is new on this phone.
     */
    fun upsert(dto: AlarmDto, now: Long): Boolean {
        var isNew = false
        update { s ->
            val local = s.alarms.firstOrNull { it.id == dto.id }
            isNew = local == null
            if (local != null && local.version > dto.version) s
            else s.copy(
                alarms = if (local == null) s.alarms + dto else s.alarms.map { if (it.id == dto.id) dto else it },
                syncedAt = s.syncedAt + (dto.id to now),
                pendingRing = confirmRing(s, listOf(dto)),
            )
        }
        return isNew
    }

    fun remove(id: String, now: Long) = synchronized(lock) {
        deleted[id] = now
        update { s ->
            s.copy(
                alarms = s.alarms.filterNot { it.id == id }, syncedAt = s.syncedAt - id, handled = s.handled - id,
                reported = s.reported - id, pendingRing = s.pendingRing - id, announced = s.announced - id,
            )
        }
    }

    /**
     * A full GET /alarms that was sent at [requestedAt]. The server's list replaces this phone's
     * (newer-version rules do not apply: a readiness report changes members without a version),
     * except for what was written here after the request left: an alarm created or changed
     * meanwhile stays, and one deleted meanwhile does not come back. Returns the ids new here.
     */
    fun replaceAll(items: List<AlarmDto>, requestedAt: Long): Set<String> = synchronized(lock) {
        var added = emptySet<String>()
        update { s ->
            val incoming = items.filter { (deleted[it.id] ?: Long.MIN_VALUE) < requestedAt }
            val ids = incoming.map { it.id }.toSet()
            val writtenSince = s.alarms.filter { (s.syncedAt[it.id] ?: 0L) >= requestedAt }.associateBy { it.id }
            val merged = incoming.map { dto -> writtenSince[dto.id]?.takeIf { it.version > dto.version } ?: dto } +
                writtenSince.values.filter { it.id !in ids }
            added = merged.map { it.id }.toSet() - s.alarms.map { it.id }.toSet()
            val keep = merged.map { it.id }.toSet()
            s.copy(
                alarms = merged,
                syncedAt = keep.associateWith { s.syncedAt[it] ?: requestedAt },
                handled = s.handled.filterKeys { it in keep },
                reported = s.reported.filterKeys { it in keep },
                pendingRing = confirmRing(s, merged).filterKeys { it in keep },
                announced = s.announced.filter { it in keep }.toSet(),
            )
        }
        added
    }

    /** A wake from alarm.woke: the member's last wake, if it is for a later (or the same) ring. */
    fun applyWake(alarmId: String, userId: String, wake: AlarmWakeDto) = update { s ->
        val at = runCatching { Instant.parse(wake.occurrence) }.getOrNull() ?: return@update s
        s.copy(
            alarms = s.alarms.map { a ->
                if (a.id != alarmId) a
                else a.copy(members = a.members.map { m ->
                    val current = m.lastWake?.occurrence?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    if (m.userId != userId || (current != null && current.isAfter(at))) m else m.copy(lastWake = wake)
                })
            },
        )
    }

    /** This person's switch for one alarm, before the server has it (so it stops ringing at once). */
    fun setPendingRing(alarmId: String, ring: Boolean) = update { it.copy(pendingRing = it.pendingRing + (alarmId to ring)) }

    /** Unconfirmed switches that the server's copy now agrees with are dropped. */
    private fun confirmRing(s: WakeLocal, copies: List<AlarmDto>): Map<String, Boolean> =
        s.pendingRing.filter { (id, ring) ->
            val mine = copies.firstOrNull { it.id == id }?.members?.firstOrNull { it.userId == s.me }
            mine == null || mine.ring != ring
        }

    private companion object {
        const val KEY = "state"
    }
}
