package com.risaina.app.core.safety

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.risaina.app.R
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.domain.QuietHoursPolicy
import com.risaina.app.core.network.LiveLocationDto
import com.risaina.app.core.notifications.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Raina is sharing their live location with you". Follows the user's notification switch, previews
 * and quiet hours (a silent channel then), like pings. Not posted for a panic session linked to an
 * SOS: the SOS alarm already covers that (and the server pushes that start at normal priority). A
 * panic share whose SOS has not gone through yet is posted, saying they may be in trouble: it is a
 * high-priority push, and it may be the only sign of the emergency for now. Removed when sharing
 * stops, or by itself at the end of a timed session (the stop event is low priority and may arrive
 * late).
 */
@Singleton
class SafetyNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesStore: PreferencesStore,
    private val time: TimeProvider,
) {
    private fun canPost(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** Risaina's own screens, not the SOS alarm over the lock screen (see AppVisibility). */
    private fun appInForeground(): Boolean = com.risaina.app.core.common.AppVisibility.isVisible

    suspend fun partnerSharingStarted(partnerName: String, session: LiveLocationDto) {
        if (!announces(session) || !canPost() || appInForeground()) return
        val prefs = preferencesStore.current()
        if (!prefs.notificationsEnabled) return
        val now = time.now()
        val expiresAt = session.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (expiresAt != null && !expiresAt.isAfter(now)) return
        // A panic is never held to quiet hours (its SOS, once through, rings through them too).
        val quiet = session.reason != "panic" && !QuietHoursPolicy.shouldAlert(prefs.quietHours, prefs.allowPushInQuietHours, now)
        val until = expiresAt?.let { "until " + DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(time.zone()).format(it) } ?: "until they turn it off"
        val title = if (prefs.previewInNotifications) "📍 $partnerName is sharing their live location with you" else "New activity"
        val text = when {
            !prefs.previewInNotifications -> "Tap to see"
            session.reason == "panic" -> "They may be in trouble. Tap to see where they are."
            else -> "Sharing $until. Tap to see where they are."
        }
        val open = PendingIntent.getActivity(
            context, PARTNER_LOCATION_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse("risaina://app/location")).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, if (quiet) NotificationChannels.PARTNER_LOCATION_QUIET else NotificationChannels.PARTNER_LOCATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(if (prefs.previewInNotifications) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
        expiresAt?.let { b.setTimeoutAfter(Duration.between(now, it).toMillis()) }
        try { NotificationManagerCompat.from(context).notify(PARTNER_LOCATION_ID, b.build()) } catch (_: SecurityException) {}
    }

    fun cancelPartnerSharing() = NotificationManagerCompat.from(context).cancel(PARTNER_LOCATION_ID)

    /** Whether "is sharing" is showing now (the periodic sync then re-checks it with the server). */
    fun partnerSharingShown(): Boolean = runCatching {
        context.getSystemService(android.app.NotificationManager::class.java)?.activeNotifications?.any { it.id == PARTNER_LOCATION_ID } == true
    }.getOrDefault(false)

    companion object {
        const val PARTNER_LOCATION_ID = 9210

        /**
         * Whether a partner's share gets "is sharing": every manual share, and a panic share that is
         * not linked to an SOS yet. Mirrors the server's push policy (backend services/push/policy.ts:
         * a panic start with an SOS goes out at normal priority, any other start high).
         */
        fun announces(session: LiveLocationDto): Boolean = session.reason != "panic" || session.sosId == null
    }
}
