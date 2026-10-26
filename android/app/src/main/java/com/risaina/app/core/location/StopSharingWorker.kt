package com.risaina.app.core.location

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
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration

/**
 * Delivers a stop that happened offline. The phone already stopped sending; this removes the last
 * position from the server as soon as the network is back. Not expedited (see SosReceiptWorker).
 */
@HiltWorker
class StopSharingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: RisainaApi,
    private val controller: LiveLocationController,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION)
        val reason = inputData.getString(KEY_REASON) ?: "stopped"
        return when (val r = apiCall { api.stopSharing(sessionId, reason) }) {
            is AppResult.Success -> Result.success().also { controller.onStopDelivered(sessionId) }
            is AppResult.Failure -> when {
                // signed out: the server ended it at sign-out
                r.error is AppError.Unauthorized -> Result.success().also { controller.onStopDelivered(sessionId) }
                r.error.isRetryable && runAttemptCount < 20 -> Result.retry()
                // Given up: a later start must not wait for it (the server purges an idle share in 12 h).
                else -> Result.failure().also { controller.onStopDelivered(sessionId) }
            }
        }
    }

    companion object {
        private const val KEY_SESSION = "sessionId"
        private const val KEY_REASON = "reason"

        fun enqueue(context: Context, sessionId: String?, reason: String) {
            val req = OneTimeWorkRequestBuilder<StopSharingWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(15))
                .setInputData(workDataOf(KEY_SESSION to sessionId, KEY_REASON to reason))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("stop-location-${sessionId ?: "all"}", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
