package com.risaina.app.core.alarm

import android.util.Log
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.data.WakeAlarmRepository
import com.risaina.app.core.data.toDto
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.domain.QuietHoursPolicy
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.network.AlarmDto
import com.risaina.app.core.network.AlarmWakeDto
import com.risaina.app.core.network.EventDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the server's alarms to this phone: the alarm.* branch of the event reducer, and a full
 * GET /alarms when the app opens and after sign-in. Every change re-arms, and the partner's notices
 * are posted here: "Raina set Wake together for 6:30 am… It rings on your phone too." the first time
 * an alarm that rings on this phone arrives (alarm.created mostly comes with notify false, because
 * this phone had not reported yet), "changed" when the server marked an edit for notice, and "is
 * up too" once this person is up themselves. Nothing is posted for a replay of history.
 */
@Singleton
class WakeAlarmSync @Inject constructor(
    private val json: Json,
    private val store: WakeAlarmStore,
    private val controller: WakeAlarmController,
    private val notifier: WakeAlarmNotifier,
    private val repository: WakeAlarmRepository,
    private val coupleDao: CoupleDao,
    private val sessionStore: SessionStore,
    private val preferencesStore: PreferencesStore,
    private val time: TimeProvider,
) {
    /**
     * Never throws (but for cancellation): the event's cursor must move on whatever happens here,
     * and the next GET /alarms puts right anything an error left out.
     */
    suspend fun apply(event: EventDto, me: String, replay: Boolean) {
        try {
            applyOrThrow(event, me, replay)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "alarm event not applied: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun applyOrThrow(event: EventDto, me: String, replay: Boolean) {
        identify(me)
        val p = event.payload
        when (event.type) {
            "alarm.created", "alarm.updated" -> {
                // One that does not decode is skipped, not thrown: the next GET /alarms has it.
                val dto = p.obj("alarm")?.let { runCatching { json.decodeFromJsonElement(AlarmDto.serializer(), it) }.getOrNull() } ?: return
                store.upsert(dto, time.now().toEpochMilli())
                if (event.actorId == me) store.update { it.copy(announced = it.announced + dto.id) }
                controller.rearm()
                if (!replay && event.actorId != me) {
                    val change = if (event.type == "alarm.created") "created" else p.str("change") ?: "edited"
                    store.current().alarm(dto.id)?.let { announce(it, me, change, notify = p.bool("notify") == true) }
                }
            }
            "alarm.deleted" -> p.str("alarmId")?.let { id ->
                controller.alarmGone(id)
                store.remove(id, time.now().toEpochMilli())
                controller.rearm()
            }
            "alarm.woke" -> {
                val alarmId = p.str("alarmId") ?: return
                val userId = p.str("userId") ?: return
                val occurrence = p.str("occurrence") ?: return
                val at = p.str("at") ?: return
                val outcome = p.str("outcome") ?: return
                store.applyWake(alarmId, userId, AlarmWakeDto(occurrence, outcome, at, p.int("snoozes") ?: 0))
                if (userId == me || outcome != "up" || replay) return
                val occ = runCatching { Instant.parse(occurrence) }.getOrNull() ?: return
                val upAt = runCatching { Instant.parse(at) }.getOrNull() ?: return
                val upToo = controller.partnerUp(alarmId, occ, upAt)
                // "Is up too", once this person is up: only while it is news.
                if (upToo && Duration.between(upAt, time.now()) < FRESH) postPartnerUp(upAt)
            }
        }
    }

    /**
     * GET /alarms, replacing this phone's copy. After a sign-in the first answer only primes the
     * store (no notices for alarms that were already there); later, an alarm the partner set that
     * this phone never saw gets its notice, as if its event had come.
     */
    suspend fun refresh(): AppResult<Unit> {
        val me = sessionStore.current()?.userId ?: return AppResult.Success(Unit)
        identify(me)
        val requestedAt = time.now().toEpochMilli()
        return when (val r = repository.list()) {
            is AppResult.Success -> {
                val added = store.replaceAll(r.value.map { it.toDto() }, requestedAt)
                val s = store.current()
                if (!s.primed) {
                    store.update { it.copy(primed = true, announced = it.announced + it.alarms.map { a -> a.id }) }
                } else {
                    for (id in added) s.alarm(id)?.let { announce(it, me, "created", notify = false) }
                }
                controller.rearm()
                AppResult.Success(Unit)
            }
            is AppResult.Failure -> r
        }
    }

    /** The receivers know whose phone this is, and the ring screen the partner's name, without the database. */
    private suspend fun identify(me: String) {
        val partner = runCatching { coupleDao.partner()?.displayName }.getOrNull()
        val s = store.current()
        if (s.me != me || (partner != null && partner != s.partnerName)) store.setIdentity(me, partner)
    }

    /**
     * The first time an alarm that rings here arrives from the partner, or when the server marked
     * their change for notice ([notify]). Follows the in-app notification switch, previews and
     * quiet hours (a silent channel then).
     */
    private suspend fun announce(alarm: WakeAlarm, me: String, change: String, notify: Boolean) {
        val now = time.now()
        val mine = alarm.member(me)
        if (WakeAlarmRules.next(alarm, mine, now) == null) return
        val first = alarm.id !in store.current().announced && alarm.createdBy != me
        if (!first && !notify) return
        store.update { it.copy(announced = it.announced + alarm.id) }
        val prefs = preferencesStore.current()
        if (!prefs.notificationsEnabled) return
        val quiet = !QuietHoursPolicy.shouldAlert(prefs.quietHours, prefs.allowPushInQuietHours, now)
        val text = WakeAlarmRules.noticeText(partnerName(), alarm, if (first) "created" else change, time.zone(), now)
        notifier.postSet(alarm.id, text, quiet, prefs.previewInNotifications)
    }

    private suspend fun postPartnerUp(at: Instant) {
        val prefs = preferencesStore.current()
        if (!prefs.notificationsEnabled) return
        val quiet = !QuietHoursPolicy.shouldAlert(prefs.quietHours, prefs.allowPushInQuietHours, time.now())
        notifier.postPartnerUp("${partnerName()} is up too · ${WakeAlarmRules.timeText(at, time.zone())}", quiet, prefs.previewInNotifications)
    }

    private fun partnerName(): String = store.current().partnerName ?: "Your partner"

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        const val TAG = "RisainaWake"
        /** An "I'm up" older than this is not announced (a late sync). */
        val FRESH: Duration = Duration.ofHours(2)
    }
}
