package com.risaina.app.core.reminders

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
import com.risaina.app.core.data.CalendarRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration

/**
 * Sends a to-do ticked from its reminder ("Done") once there is a network. The tick carries no
 * expected version (CalendarRepository.setDone), so a rename by the partner meanwhile cannot block it.
 */
@HiltWorker
class CalendarDoneWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val calendar: CalendarRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ENTRY) ?: return Result.success()
        return when (val r = calendar.setDone(id, true)) {
            is AppResult.Success -> Result.success()
            is AppResult.Failure -> when {
                // Deleted meanwhile, or signed out: nothing left to tick.
                r.error is AppError.NotFound || r.error is AppError.Unauthorized -> Result.success()
                r.error.isRetryable && runAttemptCount < 10 -> Result.retry()
                // Given up: the next calendar refresh puts the server's state back on this phone.
                else -> Result.failure()
            }
        }
    }

    companion object {
        private const val KEY_ENTRY = "entryId"

        fun enqueue(context: Context, entryId: String) {
            val req = OneTimeWorkRequestBuilder<CalendarDoneWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .setInputData(workDataOf(KEY_ENTRY to entryId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("calendar-done-$entryId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
