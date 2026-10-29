package com.risaina.app.core.alarm

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.AlarmReadiness
import com.risaina.app.core.data.WakeAlarmRepository
import com.risaina.app.core.data.toDto
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.model.WakeOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.Instant

/**
 * Tells the server what happened on this phone, with retries until the network is back: "I'm up"
 * or missed (POST /alarms/:id/wake), this phone's readiness and set rings (POST /alarms/report), and
 * "Not on my phone" (PUT /alarms/:id/me). The ring itself never waits for any of it.
 *
 * Not expedited, like SosReceiptWorker: on Android 11 and below expedited work runs as a foreground
 * service and would need a notification of its own.
 */
@HiltWorker
class WakeReportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: WakeAlarmRepository,
    private val store: WakeAlarmStore,
    private val controller: WakeAlarmController,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Signed out or unlinked since it was queued: there is nobody to tell.
        if (store.current().me == null) return Result.success()
        val id = inputData.getString(KEY_ALARM)
        return when (inputData.getString(KEY_KIND)) {
            KIND_WAKE -> outcome(
                repository.wake(
                    id ?: return Result.success(),
                    Instant.ofEpochMilli(inputData.getLong(KEY_OCCURRENCE, 0)),
                    WakeOutcome.fromWire(inputData.getString(KEY_OUTCOME).orEmpty()),
                    Instant.ofEpochMilli(inputData.getLong(KEY_AT, 0)),
                    inputData.getInt(KEY_SNOOZES, 0),
                ),
            ) { keep(it) }
            KIND_RING -> {
                val ring = inputData.getBoolean(KEY_RING, true)
                outcome(repository.setRing(id ?: return Result.success(), ring), gone = { store.update { it.copy(pendingRing = it.pendingRing - id) } }) {
                    keep(it)
                    controller.rearm()
                }
            }
            KIND_REPORT -> sendReport()
            else -> Result.success()
        }
    }

    /**
     * Sends what [WakeAlarmStore] holds as the pending report, again if it changed meanwhile (a ring
     * set during the request), at most a few times per run.
     */
    private suspend fun sendReport(): Result {
        repeat(3) {
            val pending = store.current().pendingReport ?: return Result.success()
            val items = pending.map { (id, r) -> AlarmReadiness(id, r.ready, r.reason, r.armedFor?.let(Instant::ofEpochMilli)) }
            when (val r = repository.report(items)) {
                is AppResult.Success -> store.update { s ->
                    s.copy(reported = s.reported + pending, pendingReport = s.pendingReport?.takeIf { it != pending })
                }
                is AppResult.Failure -> return when {
                    r.error.isRetryable && runAttemptCount < MAX_ATTEMPTS -> Result.retry()
                    // An older server without alarms, or nothing left to report on: counted as sent,
                    // so it is not asked again on every re-arm, only after the next change.
                    else -> { store.update { it.copy(reported = it.reported + pending, pendingReport = null) }; Result.success() }
                }
            }
        }
        return Result.success()
    }

    private inline fun outcome(r: AppResult<WakeAlarm>, gone: () -> Unit = {}, onSuccess: (WakeAlarm) -> Unit): Result = when (r) {
        is AppResult.Success -> { onSuccess(r.value); Result.success() }
        is AppResult.Failure -> when {
            r.error is AppError.NotFound || r.error is AppError.Forbidden || r.error is AppError.Validation -> { gone(); Result.success() }
            r.error.isRetryable && runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.success()
        }
    }

    private fun keep(alarm: WakeAlarm) {
        store.upsert(alarm.toDto(), System.currentTimeMillis())
    }

    companion object {
        private const val KEY_KIND = "kind"
        private const val KEY_ALARM = "alarm"
        private const val KEY_OCCURRENCE = "occurrence"
        private const val KEY_OUTCOME = "outcome"
        private const val KEY_AT = "at"
        private const val KEY_SNOOZES = "snoozes"
        private const val KEY_RING = "ring"
        private const val KIND_WAKE = "wake"
        private const val KIND_REPORT = "report"
        private const val KIND_RING = "ring"
        private const val MAX_ATTEMPTS = 12
        private const val UNIQUE_REPORT = "wake-report"
        private const val TAG = "wake-report"

        fun enqueueWake(context: Context, alarmId: String, occurrence: Instant, outcome: WakeOutcome, at: Instant, snoozes: Int) =
            enqueue(
                context, "wake-${alarmId}-${occurrence.toEpochMilli()}", ExistingWorkPolicy.KEEP,
                workDataOf(
                    KEY_KIND to KIND_WAKE, KEY_ALARM to alarmId, KEY_OCCURRENCE to occurrence.toEpochMilli(), KEY_OUTCOME to outcome.wire,
                    KEY_AT to at.toEpochMilli(), KEY_SNOOZES to snoozes,
                ),
            )

        /** The latest switch wins: a newer tap replaces one still waiting for the network. */
        fun enqueueRing(context: Context, alarmId: String, ring: Boolean) =
            enqueue(context, "wake-ring-$alarmId", ExistingWorkPolicy.REPLACE, workDataOf(KEY_KIND to KIND_RING, KEY_ALARM to alarmId, KEY_RING to ring))

        /** One at a time; it sends whatever is pending when it runs (and again if that changes meanwhile). */
        fun enqueueReport(context: Context) =
            enqueue(context, UNIQUE_REPORT, ExistingWorkPolicy.KEEP, workDataOf(KEY_KIND to KIND_REPORT))

        fun cancelAll(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) }
        }

        private fun enqueue(context: Context, name: String, policy: ExistingWorkPolicy, data: androidx.work.Data) {
            val req = OneTimeWorkRequestBuilder<WakeReportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(15))
                .setInputData(data)
                .addTag(TAG)
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(name, policy, req) }
        }
    }
}
