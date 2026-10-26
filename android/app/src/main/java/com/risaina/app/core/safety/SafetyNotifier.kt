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
 * and quiet hours (a silent channel then), like pings. Not posted for a panic session: the SOS alarm
 * already covers that. Removed when sharing stops, or by itself at the end of a timed session (the
 * stop event is low priority and may arrive late).
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
        if (session.reason == "panic" || !canPost() || appInForeground()) return
        val prefs = preferencesStore.current()
        if (!prefs.notificationsEnabled) return
        val now = time.now()
        val expiresAt = session.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (expiresAt != null && !expiresAt.isAfter(now)) return
        val quiet = !QuietHoursPolicy.shouldAlert(prefs.quietHours, prefs.allowPushInQuietHours, now)
        val until = expiresAt?.let { "until " + DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(time.zone()).format(it) } ?: "until they turn it off"
        val title = if (prefs.previewInNotifications) "📍 $partnerName is sharing their live location with you" else "New activity"
        val text = if (prefs.previewInNotifications) "Sharing $until. Tap to see where they are." else "Tap to see"
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

    companion object {
        const val PARTNER_LOCATION_ID = 9210
    }
}
