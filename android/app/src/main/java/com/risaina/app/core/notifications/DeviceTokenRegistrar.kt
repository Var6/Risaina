package com.risaina.app.core.notifications

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import com.risaina.app.BuildConfig
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.DeviceRegistrar
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.network.DeviceRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class WorkManagerDeviceRegistrar @Inject constructor(@ApplicationContext private val context: Context) : DeviceRegistrar {
    override fun scheduleRegistration() = RegisterDeviceWorker.enqueue(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceRegistrarModule {
    @Binds abstract fun bind(impl: WorkManagerDeviceRegistrar): DeviceRegistrar
}

/** Sends the current FCM token to the backend. Skips cleanly when Firebase is not configured. */
@HiltWorker
class RegisterDeviceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: RisainaApi,
    private val sessionStore: SessionStore,
    private val pushAvailability: PushAvailability,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val session = sessionStore.current() ?: return Result.success()
        if (!pushAvailability.configured) return Result.success()
        val token = runCatching { fetchToken() }.getOrNull() ?: return if (runAttemptCount < 3) Result.retry() else Result.failure()
        val res = apiCall {
            api.registerDevice(DeviceRequest(deviceId = session.deviceId, token = token, appVersion = BuildConfig.VERSION_NAME, model = Build.MODEL))
        }
        return when (res) {
            is AppResult.Success -> Result.success()
            is AppResult.Failure -> if (res.error.isRetryable && runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    private suspend fun fetchToken(): String = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

    companion object {
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<RegisterDeviceWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("register-device", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
