package com.risaina.app.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.MessageRepository
import com.risaina.app.core.data.PreferencesRepository
import com.risaina.app.core.notifications.RisainaNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration

/**
 * Catch-up sync. Triggered by FCM data messages (expedited), by app start, and hourly as a safety
 * net. Posts notifications only for items that are genuinely new (the reducer de-duplicates by
 * seq, so a ping already shown via the foreground socket is never notified twice).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: EventSyncManager,
    private val messageRepository: MessageRepository,
    private val preferencesRepository: PreferencesRepository,
    private val notifier: RisainaNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val notify = inputData.getBoolean(KEY_NOTIFY, true)
        val result = syncManager.syncOnce()
        messageRepository.drainOutbox()
        preferencesRepository.pushIfDirty()
        return when (result) {
            is AppResult.Success -> {
                if (notify) result.value.forEach { notifier.notifyIncoming(it) }
                Result.success()
            }
            is AppResult.Failure -> if (result.error.isRetryable && runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_NOTIFY = "notify"
        private const val UNIQUE_PUSH = "sync-push"
        private const val UNIQUE_PERIODIC = "sync-periodic"

        /** Called from the FCM service: must finish quickly, so it is expedited. */
        fun enqueueFromPush(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(20))
                .setInputData(workDataOf(KEY_NOTIFY to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_PUSH, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        /** App start / resume: catch up silently (the UI will reveal new items itself). */
        fun enqueueSilent(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_NOTIFY to false))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-silent", ExistingWorkPolicy.KEEP, req)
        }

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofHours(1))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_NOTIFY to true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PUSH)
        }
    }
}
