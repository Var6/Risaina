package com.risaina.app.core.sos

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
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.SosRepository
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.location.LiveLocationController
import com.risaina.app.core.notifications.SosAlert
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.Instant

/**
 * Keeps trying to send an SOS that failed (offline, a timeout while the server wakes up, the rate
 * limit). The idempotency key means a retry after a lost response never rings the partner twice.
 * Gives up after 30 minutes (an SOS that old would ring as if it were happening now) and then tells
 * the sender to call. A late SOS says when it was really sent.
 */
@HiltWorker
class SosRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sosRepository: SosRepository,
    private val liveLocation: LiveLocationController,
    private val sosAlert: SosAlert,
    private val coupleDao: CoupleDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_IDEMPOTENCY) ?: return Result.failure()
        val createdAt = Instant.ofEpochMilli(inputData.getLong(KEY_CREATED_AT, System.currentTimeMillis()))
        val now = Instant.now()
        if (Duration.between(createdAt, now) > GIVE_UP_AFTER) {
            sosAlert.postNotSent(coupleDao.partner()?.displayName ?: "your partner")
            return Result.failure()
        }
        val panic = inputData.getBoolean(KEY_PANIC, false)
        val message = SosRingPolicy.delayedMessage(inputData.getString(KEY_MESSAGE), createdAt, now)
        return when (val r = sosRepository.send(message, panic, key)) {
            is AppResult.Success -> {
                if (panic) liveLocation.linkSos(r.value.id)
                Result.success()
            }
            is AppResult.Failure -> if (r.error.isRetryable) Result.retry() else {
                sosAlert.postNotSent(coupleDao.partner()?.displayName ?: "your partner")
                Result.failure()
            }
        }
    }

    companion object {
        val GIVE_UP_AFTER: Duration = Duration.ofMinutes(30)
        private const val KEY_MESSAGE = "message"
        private const val KEY_PANIC = "panic"
        private const val KEY_IDEMPOTENCY = "idempotencyKey"
        private const val KEY_CREATED_AT = "createdAt"

        fun enqueue(context: Context, message: String?, panic: Boolean, idempotencyKey: String, createdAtMs: Long) {
            val req = OneTimeWorkRequestBuilder<SosRetryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(15))
                .setInputData(workDataOf(KEY_MESSAGE to message, KEY_PANIC to panic, KEY_IDEMPOTENCY to idempotencyKey, KEY_CREATED_AT to createdAtMs))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sos-retry-$idempotencyKey", ExistingWorkPolicy.KEEP, req)
        }
    }
}
