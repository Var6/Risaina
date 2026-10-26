package com.risaina.app.core.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.risaina.app.R
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.MessageRepository
import com.risaina.app.core.data.PreferencesRepository
import com.risaina.app.core.notifications.DeliveryDiagnostics
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Catch-up sync. Triggered by FCM data messages (expedited), by app start, and hourly as a safety
 * net. Posts notifications only for items that are genuinely new (the reducer de-duplicates by
 * seq, so a ping already shown via the foreground socket is never notified twice), each as soon as
 * its event is applied.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: EventSyncManager,
    private val messageRepository: MessageRepository,
    private val preferencesRepository: PreferencesRepository,
    private val diagnostics: DeliveryDiagnostics,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        diagnostics.recordWorkerRun()
        val notify = inputData.getBoolean(KEY_NOTIFY, true)
        // Joins the push handler's sync if it is still running (one sync at a time), and also fetches
        // a new widget photo, which the push handler leaves to this worker.
        val result = syncManager.syncOnce(notify = notify, fetchMedia = true)
        // Receipts and game refreshes: network follow-ups kept out of the push handler's few seconds.
        runCatching { syncManager.runDeferred() }
        messageRepository.drainOutbox()
        preferencesRepository.pushIfDirty()
        return when (result) {
            is AppResult.Success -> Result.success()
            is AppResult.Failure -> if (result.error.isRetryable && runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }

    /**
     * Expedited work runs as a foreground service on Android 11 and below, and WorkManager then
     * needs this notification (without it the worker fails there). Silent and minimal; Android 12+
     * runs expedited work as a job and never shows it.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(SYNC_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(SYNC_CHANNEL, "Syncing", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown briefly on older Android versions while Risaina catches up"
                setShowBadge(false)
            })
        }
        val notification = NotificationCompat.Builder(applicationContext, SYNC_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Catching up")
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        // No service type: only Android 11 and below use this, where a type is optional.
        return ForegroundInfo(FOREGROUND_ID, notification)
    }

    companion object {
        const val KEY_NOTIFY = "notify"
        private const val UNIQUE_PUSH = "sync-push"
        private const val UNIQUE_PERIODIC = "sync-periodic"
        private const val SYNC_CHANNEL = "sync"
        private const val FOREGROUND_ID = 7272

        /**
         * Called from the FCM service, first thing: must finish quickly, so it is expedited, and it is
         * queued inside FCM's brief exemption for expedited work. A sync that is still waiting to run
         * will fetch everything anyway, so a burst of pushes does not grow the chain.
         */
        fun enqueueFromPush(context: Context) {
            val wm = WorkManager.getInstance(context)
            val waiting = runCatching {
                wm.getWorkInfosForUniqueWork(UNIQUE_PUSH).get(500, TimeUnit.MILLISECONDS)
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            }.getOrDefault(false)
            if (waiting) return
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(20))
                .setInputData(workDataOf(KEY_NOTIFY to true))
                .build()
            wm.enqueueUniqueWork(UNIQUE_PUSH, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
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
