package com.risaina.app.core.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
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
    private val deferred: DeferredEffects,
    private val safetyNotifier: com.risaina.app.core.safety.SafetyNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        diagnostics.recordWorkerRun()
        val notify = inputData.getBoolean(KEY_NOTIFY, true)
        // Joins the push handler's sync if it is still running (one sync at a time), then fetches a
        // new widget photo, which the push handler may not have had the time for.
        val result = syncManager.syncOnce(notify = notify, fetchMedia = true)
        // A partner's share that the server purged without an event (12 h with no fix: their phone
        // died) would say "is sharing" for ever: while it shows, each sync (the hourly one too) asks.
        if (safetyNotifier.partnerSharingShown()) deferred.checkPartnerLocation()
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
     * runs expedited work as a job and never shows it. Push syncs are no longer expedited there
     * ([pushRequestFor]); this stays for one an older version queued before an update.
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
         * Called from the FCM service, first thing: must finish quickly, so it is expedited (Android
         * 12+, see [pushRequestFor]), and it is queued inside FCM's brief exemption for expedited
         * work. See [pushEnqueue] for when a push adds nothing, appends, or replaces a sync that is
         * waiting out a retry.
         */
        fun enqueueFromPush(context: Context) {
            val wm = WorkManager.getInstance(context)
            val decision = runCatching {
                pushEnqueue(wm.getWorkInfosForUniqueWork(UNIQUE_PUSH).get(500, TimeUnit.MILLISECONDS).map { it.state to it.runAttemptCount })
            }.getOrDefault(PushEnqueue.APPEND)
            val policy = when (decision) {
                PushEnqueue.SKIP -> return
                PushEnqueue.APPEND -> ExistingWorkPolicy.APPEND_OR_REPLACE
                PushEnqueue.REPLACE -> ExistingWorkPolicy.REPLACE
            }
            wm.enqueueUniqueWork(UNIQUE_PUSH, policy, pushRequestFor(Build.VERSION.SDK_INT))
        }

        /**
         * The push's safety-net sync. Expedited only on Android 12 and up, where it runs as a job.
         * Android 11 and below run expedited work as a foreground service and show its "Catching up"
         * notification ([getForegroundInfo]) next to the real one on every push; there the push
         * handler already syncs inline, so a plain request is enough. Pure (SyncWorkerEnqueueTest).
         */
        fun pushRequestFor(sdkInt: Int): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .apply { if (sdkInt >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(20))
            .setInputData(workDataOf(KEY_NOTIFY to true))
            .build()

        /**
         * App start / resume: catch up. With notifications on: they are suppressed while Risaina is
         * on screen (RisainaNotifier.notifyIncoming; the UI reveals new items itself), so this only
         * alerts if it runs after the app has gone to the background. It waits for a network, so it
         * can run much later (opened offline, or ColorOS held the job), and a sync is shared by
         * everyone who needs one: a "silent" run would apply the partner's pings and messages
         * without a word, and the push that came for them would then find nothing new to announce.
         */
        fun enqueueOnOpen(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_NOTIFY to true))
                .build()
            // The unique name predates the notify switch; kept so a request queued by an older build is reused.
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

/** What a push does about the push-sync chain. */
enum class PushEnqueue { SKIP, APPEND, REPLACE }

/**
 * Decides from the chain's work ([work]: state and runAttemptCount of each request). Pure
 * (SyncWorkerEnqueueTest).
 *
 * - A request that has not started yet (ENQUEUED on its first attempt, or BLOCKED behind a running
 *   one) will fetch everything when it runs, so a burst of pushes adds nothing: SKIP.
 * - One is running and none waits: APPEND a fresh one behind it, because the running sync may have
 *   fetched before this push's event existed.
 * - Nothing runs and one waits out a retry backoff (ENQUEUED after an attempt): REPLACE it. WorkManager
 *   reschedules a backed-off request as an ordinary delayed job, not expedited, and ColorOS or Doze can
 *   hold that for hours; meanwhile only the worker sends delivered receipts and fetches widget photos.
 *   A fresh request runs now, inside this push's exemption for expedited work.
 * - Otherwise (nothing, or only finished work): APPEND, which starts a new chain.
 */
fun pushEnqueue(work: List<Pair<WorkInfo.State, Int>>): PushEnqueue {
    val running = work.any { it.first == WorkInfo.State.RUNNING }
    val notStarted = work.any { (state, attempts) -> state == WorkInfo.State.BLOCKED || (state == WorkInfo.State.ENQUEUED && attempts == 0) }
    val backingOff = work.any { (state, attempts) -> state == WorkInfo.State.ENQUEUED && attempts > 0 }
    return when {
        running -> if (notStarted) PushEnqueue.SKIP else PushEnqueue.APPEND
        backingOff -> PushEnqueue.REPLACE
        notStarted -> PushEnqueue.SKIP
        else -> PushEnqueue.APPEND
    }
}
