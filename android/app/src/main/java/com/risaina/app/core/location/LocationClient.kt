package com.risaina.app.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin, testable wrapper over FusedLocationProviderClient. Google Play services is already required
 * (FCM, Google sign-in), and Fused gives balanced Wi-Fi/cell accuracy on every Android version.
 */
interface LocationClient {
    fun playServicesAvailable(): Boolean
    fun hasPermission(): Boolean
    fun hasPrecise(): Boolean
    suspend fun lastLocation(): Location?
    suspend fun current(highAccuracy: Boolean, maxAgeMs: Long, timeoutMs: Long): Location?
    fun updates(highAccuracy: Boolean, intervalMs: Long, minIntervalMs: Long): Flow<Location>
}

@Singleton
class FusedLocationClient @Inject constructor(@ApplicationContext private val context: Context) : LocationClient {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }

    override fun playServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    override fun hasPermission(): Boolean = hasPrecise() ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun hasPrecise(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission(); a revoked permission throws SecurityException, caught
    override suspend fun lastLocation(): Location? {
        if (!hasPermission()) return null
        return runCatching { fused.lastLocation.await() }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    override suspend fun current(highAccuracy: Boolean, maxAgeMs: Long, timeoutMs: Long): Location? {
        if (!hasPermission()) return null
        val request = CurrentLocationRequest.Builder()
            .setPriority(if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(maxAgeMs)
            .setDurationMillis(timeoutMs)
            .build()
        val cancel = CancellationTokenSource()
        return try {
            withTimeoutOrNull(timeoutMs + 2_000) { fused.getCurrentLocation(request, cancel.token).await() }
        } catch (_: Exception) {
            null
        } finally {
            cancel.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    override fun updates(highAccuracy: Boolean, intervalMs: Long, minIntervalMs: Long): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(minIntervalMs)
            .setMaxUpdateDelayMillis(0) // no batching: the partner should see each fix as it comes
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper()).await()
        } catch (e: Exception) {
            close(e)
        }
        awaitClose { fused.removeLocationUpdates(callback) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {
    @Binds abstract fun bindLocationClient(impl: FusedLocationClient): LocationClient
}
