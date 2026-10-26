package com.risaina.app.core.location

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.risaina.app.R
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.domain.LiveLocationRules
import com.risaina.app.core.network.LocationFixInput
import com.risaina.app.core.network.PutFixRequest
import com.risaina.app.core.network.StartSharingRequest
import com.risaina.app.core.notifications.NotificationChannels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Shares the user's live location while they asked for it: a location-type foreground service with a
 * notification visible the whole time (Stop sharing, and "I'm safe now" in panic). Fixes go to the
 * server, which passes them to the partner live and keeps only the latest one. Started only from a
 * visible screen; never restarted by the system (START_NOT_STICKY): a restart would have no location
 * access, so the app re-checks the session when it is next opened instead.
 */
@AndroidEntryPoint
class LiveLocationService : Service() {
    @Inject lateinit var controller: LiveLocationController
    @Inject lateinit var repo: LiveLocationRepository
    @Inject lateinit var client: LocationClient
    @Inject lateinit var coupleDao: CoupleDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var plan: SessionPlan? = null
    private var partnerName = "your partner"
    private var latest: Location? = null
    private var lastUploaded: LiveLocationRules.Uploaded? = null
    private var signInNeeded = false
    private val uploads = Channel<Location>(Channel.CONFLATED)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(planFrom(intent) ?: controller.currentPlan())
            ACTION_STOP -> controller.stopAsync("stopped")
            ACTION_SAFE -> controller.safeNowAsync(plan?.sosId ?: controller.currentPlan()?.sosId)
            ACTION_REPOST -> plan?.let { post(it) } // Android 14+: a swiped-away notification comes back
            else -> if (plan == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(p: SessionPlan?) {
        if (p == null || !client.hasPermission()) {
            // startForegroundService was called: answer it before stopping, or Android crashes the app.
            runCatching { startForeground(NOTIFICATION_ID, notification(p ?: placeholderPlan())) }
            stopSelf()
            controller.endFromService("stopped", tellServer = false)
            return
        }
        val started = try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(p), type)
            true
        } catch (_: Exception) {
            false // SecurityException: no while-in-use location access right now
        }
        if (!started) {
            stopSelf()
            controller.onServiceGone()
            return
        }
        val sameSession = plan?.sessionId == p.sessionId
        plan = p
        controller.onServiceStarted(p)
        if (!sameSession) lastUploaded = null
        job?.cancel()
        job = scope.launch { run(p) }
        scope.launch {
            partnerName = runCatching { coupleDao.partner()?.displayName }.getOrNull() ?: partnerName
            post(plan ?: p)
        }
    }

    private suspend fun run(p: SessionPlan) = coroutineScope {
        val registered = CompletableDeferred<Unit>()
        launch { register(p, registered) }
        launch { registered.await(); uploader(p) }
        // A precise first fix, then a steady stream (balanced for manual, high accuracy in panic).
        launch { client.current(highAccuracy = true, maxAgeMs = 30_000, timeoutMs = 20_000)?.let { offer(it, force = true) } }
        launch {
            val interval = if (p.panic) 15_000L else 60_000L
            client.updates(highAccuracy = p.panic, intervalMs = interval, minIntervalMs = if (p.panic) 10_000L else 30_000L)
                .catch { /* permission revoked mid-share: Android stops the process; reconcile handles it */ }
                .collect { offer(it) }
        }
        // Heartbeat and expiry on the wall clock: coroutine delays stop counting in deep sleep.
        launch {
            while (true) {
                delay(15_000)
                if (expired()) { controller.endFromService("expired", tellServer = true); return@launch }
                if (registered.isCompleted) heartbeat()
                post(plan ?: p)
            }
        }
    }

    private fun expired(): Boolean = plan?.expiresAtMs?.let { System.currentTimeMillis() >= it } == true

    /** Registers the session (with the last known position if it is recent), retrying until it works. */
    private suspend fun register(p: SessionPlan, done: CompletableDeferred<Unit>) {
        var attempt = 0
        var withFix = true
        while (true) {
            val current = controller.currentPlan()?.takeIf { it.sessionId == p.sessionId } ?: p
            val last = if (withFix) client.lastLocation()?.takeIf { ageOf(it) < 2 * 60_000L } else null
            val req = StartSharingRequest(
                sessionId = current.sessionId,
                mode = if (current.minutes > 0) "timed" else "until_off",
                durationMinutes = current.minutes.takeIf { it > 0 },
                reason = if (current.panic) "panic" else "manual",
                sosId = current.sosId,
                fix = last?.let { fixOf(it) },
            )
            when (val r = repo.start(req)) {
                is AppResult.Success -> {
                    controller.onServiceRegistered(r.value)
                    plan = controller.currentPlan() ?: current
                    signInNeeded = false
                    done.complete(Unit)
                    return
                }
                is AppResult.Failure -> when (val e = r.error) {
                    is AppError.Conflict, is AppError.NotFound -> { controller.endFromService("stopped", tellServer = false); return }
                    is AppError.Validation -> withFix = false
                    is AppError.Unauthorized -> if (!current.panic) { controller.endFromService("stopped", tellServer = false); return } else markSignInNeeded()
                    is AppError.RateLimited -> delay((e.retryAfterSeconds ?: 60) * 1000L)
                    else -> delay(backoff(attempt++))
                }
            }
        }
    }

    private fun offer(location: Location, force: Boolean = false) {
        latest = location
        if (expired()) return
        val p = plan ?: return
        val sample = LiveLocationRules.Sample(location.latitude, location.longitude, accuracyOf(location))
        if (force || LiveLocationRules.shouldUpload(lastUploaded, sample, p.mode, SystemClock.elapsedRealtime())) uploads.trySend(location)
    }

    private suspend fun heartbeat() {
        val p = plan ?: return
        val since = SystemClock.elapsedRealtime() - (lastUploaded?.atElapsedMs ?: 0L)
        if (since < LiveLocationRules.heartbeatMs(p.mode)) return
        val fresh = latest?.takeIf { ageOf(it) < LiveLocationRules.MAX_FIX_AGE_MS - 60_000L }
            ?: client.current(highAccuracy = p.panic, maxAgeMs = 60_000, timeoutMs = 20_000)
        fresh?.let { latest = it; uploads.trySend(it) }
    }

    /** One upload at a time, always the newest fix (the channel keeps only the latest). */
    private suspend fun uploader(p: SessionPlan) {
        for (first in uploads) {
            var location = first
            var attempt = 0
            while (true) {
                if (ageOf(location) >= LiveLocationRules.MAX_FIX_AGE_MS) break // too old to show as live
                when (val r = repo.putFix(PutFixRequest(p.sessionId, fixOf(location)))) {
                    is AppResult.Success -> {
                        lastUploaded = LiveLocationRules.Uploaded(location.latitude, location.longitude, accuracyOf(location), SystemClock.elapsedRealtime())
                        signInNeeded = false
                        controller.onUploaded()
                        break
                    }
                    is AppResult.Failure -> when (val e = r.error) {
                        // location_not_sharing / not_paired: the server ended it (expiry, unlink, "I'm safe now")
                        is AppError.Conflict -> { controller.endFromService("stopped", tellServer = false); return }
                        is AppError.Validation -> break // drop this fix, keep sharing
                        is AppError.Unauthorized -> if (!p.panic) { controller.endFromService("stopped", tellServer = false); return } else { markSignInNeeded(); delay(60_000) }
                        is AppError.RateLimited -> delay((e.retryAfterSeconds ?: 30) * 1000L)
                        else -> delay(backoff(attempt++)) // offline, timeout, server waking up
                    }
                }
                // Retry with the newest position, not the one that failed.
                location = uploads.tryReceive().getOrNull() ?: latest ?: location
            }
        }
    }

    private fun markSignInNeeded() {
        signInNeeded = true
        controller.onUploaded(signInNeeded = true)
        plan?.let { post(it) }
    }

    private fun backoff(attempt: Int): Long = (15_000L shl attempt.coerceAtMost(2)).coerceAtMost(60_000L)

    private fun ageOf(l: Location): Long = LiveLocationRules.ageMs(SystemClock.elapsedRealtimeNanos(), l.elapsedRealtimeNanos)
    private fun accuracyOf(l: Location): Double = if (l.hasAccuracy() && l.accuracy > 0f) l.accuracy.toDouble() else LiveLocationRules.UNKNOWN_ACCURACY_M

    private fun fixOf(l: Location) = LocationFixInput(
        lat = l.latitude, lng = l.longitude, accuracyM = accuracyOf(l), ageMs = ageOf(l),
        batteryPct = LiveLocationRules.batteryPct(runCatching { getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()),
    )

    private fun post(p: SessionPlan) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(p)) }
    }

    private fun notification(p: SessionPlan): Notification {
        val until = p.expiresAtMs?.let { "Until " + DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) }
            ?: "Until you turn it off"
        val updated = lastUploaded?.let { " · updated " + LiveLocationRules.ago(Instant.now().minusMillis(SystemClock.elapsedRealtime() - it.atElapsedMs), Instant.now()) } ?: ""
        val text = if (signInNeeded) "Sign in to Risaina again to keep sharing" else until + updated
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse("risaina://app/safety")).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, NotificationChannels.LOCATION_SHARING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (p.panic) "Sharing your location with $partnerName" else "$partnerName can see your live location")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .setDeleteIntent(action(ACTION_REPOST))
            .setColor(ContextCompat.getColor(this, R.color.rosewood))
        if (p.panic && p.sosId != null) b.addAction(0, "I'm safe now", action(ACTION_SAFE))
        b.addAction(0, "Stop sharing", action(ACTION_STOP))
        return b.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, name.hashCode(), Intent(this, LiveLocationService::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun placeholderPlan() = SessionPlan("000000000000000000000000", false, LiveLocationRules.UNTIL_OFF, null, System.currentTimeMillis())

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        controller.onServiceGone()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 9200
        private const val ACTION_START = "com.risaina.app.location.START"
        private const val ACTION_STOP = "com.risaina.app.location.STOP"
        private const val ACTION_SAFE = "com.risaina.app.location.SAFE"
        private const val ACTION_REPOST = "com.risaina.app.location.REPOST"
        private const val X_PLAN = "plan"

        fun startIntent(context: Context, plan: SessionPlan): Intent = Intent(context, LiveLocationService::class.java)
            .setAction(ACTION_START)
            .putExtra(X_PLAN, kotlinx.serialization.json.Json.encodeToString(SessionPlan.serializer(), plan))

        private fun planFrom(i: Intent): SessionPlan? =
            i.getStringExtra(X_PLAN)?.let { runCatching { kotlinx.serialization.json.Json.decodeFromString(SessionPlan.serializer(), it) }.getOrNull() }
    }
}
