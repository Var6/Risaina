package com.risaina.app.core.sync

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
import com.risaina.app.core.data.lists.ListSaveScheduler
import com.risaina.app.core.data.lists.ListSyncEngine
import com.risaina.app.core.datastore.SessionStore
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends list edits that are kept on this phone (list_drafts): typed offline, interrupted by the app
 * closing, or waiting out a server error. Runs once a network is there; no foreground service, no
 * expedited work. Orphaned and refused drafts are left for the person to decide.
 */
@HiltWorker
class ListSaveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: ListSyncEngine,
    private val sessionStore: SessionStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (sessionStore.current() == null) return Result.success()
        return when {
            engine.sendAll() -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            // Still failing after many hours of backoff: stop; the next edit, app start or reconnect tries again.
            else -> Result.failure()
        }
    }

    companion object {
        private const val UNIQUE = "list-save"
        private const val MAX_ATTEMPTS = 12

        /** KEEP: a queued run already sends every stored draft when it starts. */
        fun enqueue(context: Context, delaySeconds: Long = 0) {
            val req = OneTimeWorkRequestBuilder<ListSaveWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .apply { if (delaySeconds > 0) setInitialDelay(delaySeconds, TimeUnit.SECONDS) }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }

        /** Sign-out or unlink: drafts are cleared with the rest, and nothing is sent into the next session. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }
    }
}

@Singleton
class WorkManagerListSaveScheduler @Inject constructor(@ApplicationContext private val context: Context) : ListSaveScheduler {
    override fun schedule(delaySeconds: Long) = ListSaveWorker.enqueue(context, delaySeconds)
    override fun cancel() = ListSaveWorker.cancel(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ListSaveSchedulerModule {
    @Binds abstract fun bind(impl: WorkManagerListSaveScheduler): ListSaveScheduler
}
