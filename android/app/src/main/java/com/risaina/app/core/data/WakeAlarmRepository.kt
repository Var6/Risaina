package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.model.WakeOutcome
import com.risaina.app.core.network.AlarmDto
import com.risaina.app.core.network.AlarmReportItemDto
import com.risaina.app.core.network.AlarmReportRequest
import com.risaina.app.core.network.AlarmResponse
import com.risaina.app.core.network.AlarmSkipDto
import com.risaina.app.core.network.AlarmWakeRequest
import com.risaina.app.core.network.CreateAlarmRequest
import com.risaina.app.core.network.PatchAlarmRequest
import com.risaina.app.core.network.PutMyAlarmRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** One alarm's readiness on this phone, as reported to the partner (POST /alarms/report). */
data class AlarmReadiness(val alarmId: String, val ready: Boolean, val reason: String? = null, val armedFor: Instant? = null)

/**
 * "Wake together" alarms on the server: a thin wrapper that returns models. The phone's own copy,
 * which rings without the network, is kept outside Room by the wake package (WakeAlarmStore), fed
 * from here and from alarm.* events. A stale edit fails with [AppError.VersionConflict], whose
 * details carry the current alarm (`alarm(json)` in ApiErrors).
 */
@Singleton
class WakeAlarmRepository @Inject constructor(private val api: RisainaApi) {
    private val _available = MutableStateFlow<Boolean?>(null)
    /** False once GET /alarms answered 404 (a server older than alarms). Null until asked. */
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    /** Alarms whose ring time cannot be read are left out (see AlarmDto.toDomain). */
    suspend fun list(): AppResult<List<WakeAlarm>> {
        val r = apiCall { api.alarms() }
        when (r) {
            is AppResult.Success -> _available.value = true
            is AppResult.Failure -> if (r.error is AppError.NotFound) _available.value = false
        }
        return r.map { it.items.mapNotNull { a -> a.toDomain() } }
    }

    /**
     * [days]: ISO weekdays in [timeZone]; empty means once, at [onceAt]. [ringOnMyPhone] is this
     * person's own switch; the partner's phone rings by default and only they can turn it off.
     */
    suspend fun create(
        time: LocalTime, timeZone: String, days: List<Int>, onceAt: Instant? = null, label: String? = null,
        ringOnMyPhone: Boolean = true, snoozeMinutes: Int = 10,
    ): AppResult<WakeAlarm> = model {
        api.createAlarm(
            CreateAlarmRequest(
                time = time.format(HH_MM), timeZone = timeZone, days = days.distinct().sorted(), label = label?.trim()?.ifEmpty { null },
                onceAt = onceAt?.toString(), ringOnMyPhone = ringOnMyPhone, snoozeMinutes = snoozeMinutes,
            ),
        )
    }

    /** Shared fields (time, days, label, enabled…), at [PatchAlarmRequest.expectedVersion]. */
    suspend fun update(id: String, req: PatchAlarmRequest): AppResult<WakeAlarm> = model { api.patchAlarm(id, req) }

    suspend fun setEnabled(alarm: WakeAlarm, enabled: Boolean): AppResult<WakeAlarm> =
        update(alarm.id, PatchAlarmRequest(expectedVersion = alarm.version, enabled = enabled))

    /** Already gone (the partner deleted it first) counts as done. */
    suspend fun delete(id: String): AppResult<Unit> {
        val r = apiCall { api.deleteAlarm(id) }
        return if (r is AppResult.Failure && r.error is AppError.NotFound) AppResult.Success(Unit) else r
    }

    /** Whether it rings on this phone. Changes only this person's member entry. */
    suspend fun setRing(id: String, ring: Boolean): AppResult<WakeAlarm> = model { api.putMyAlarm(id, PutMyAlarmRequest(ring = ring)) }

    /** Skip (or un-skip) one occurrence on this phone only. */
    suspend fun setSkip(id: String, occurrence: Instant, skip: Boolean): AppResult<WakeAlarm> =
        model { api.putMyAlarm(id, PutMyAlarmRequest(skip = AlarmSkipDto(occurrence.toString(), skip))) }

    /** What this phone could arm, so the partner sees "Not ready on Aanya's phone". At most 10 per call. */
    suspend fun report(items: List<AlarmReadiness>): AppResult<Unit> =
        apiCall { api.reportAlarms(AlarmReportRequest(items.take(10).map { AlarmReportItemDto(it.alarmId, it.ready, it.reason, it.armedFor?.toString()) })) }

    /** "I'm up" (or missed) for one occurrence. Idempotent per person and occurrence. */
    suspend fun wake(id: String, occurrence: Instant, outcome: WakeOutcome, at: Instant, snoozes: Int): AppResult<WakeAlarm> =
        model { api.wakeAlarm(id, AlarmWakeRequest(occurrence.toString(), outcome.wire, at.toString(), snoozes)) }

    private suspend fun model(call: suspend () -> AlarmResponse): AppResult<WakeAlarm> =
        when (val r = apiCall { call() }) {
            is AppResult.Success -> r.value.alarm.toResult()
            is AppResult.Failure -> r
        }

    private fun AlarmDto.toResult(): AppResult<WakeAlarm> =
        toDomain()?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.Server("The server sent an alarm this version can't read"))
}
