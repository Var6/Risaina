package com.risaina.app.core.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.risaina.app.R
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.domain.QuietHoursPolicy
import com.risaina.app.core.domain.Reminder
import com.risaina.app.core.domain.ReminderText
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.Partner
import com.risaina.app.core.notifications.NotificationChannels
import com.risaina.app.core.notifications.NotificationGroups
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts calendar reminders. They follow the in-app notification switch, preview privacy ("Reminder ·
 * Tap to see") and quiet hours (the silent channel), like everything else the app posts. Each has its
 * own id (from its key, under the tag [TAG]) and the reminders group; a to-do gets a "Done" button.
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesStore: PreferencesStore,
    private val time: TimeProvider,
) {
    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    suspend fun post(r: Reminder, partner: Partner?, partnerZone: ZoneId?) {
        if (!canPost()) return
        val prefs = preferencesStore.current()
        if (!prefs.notificationsEnabled) return
        val now = time.now()
        val zone = time.zone()
        val quiet = !QuietHoursPolicy.shouldAlert(prefs.quietHours, prefs.allowPushInQuietHours, now)
        val channel = if (quiet) NotificationChannels.REMINDERS_QUIET else NotificationChannels.REMINDERS
        val title = if (prefs.previewInNotifications) ReminderText.title(r, now, zone) else ReminderText.PRIVATE_TITLE
        val body = if (prefs.previewInNotifications) ReminderText.body(r, zone, partner?.displayName, partnerZone) else ReminderText.PRIVATE_BODY
        val entry = r.entry
        val id = idFor(r.key)
        val link = entry?.let { "risaina://app/calendar/${it.id}" } ?: "risaina://app/calendar"
        val open = PendingIntent.getActivity(
            context, id,
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(if (entry?.kind == CalendarKind.TODO) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setWhen(r.fireAt.toEpochMilli())
            .setShowWhen(true)
            .setGroup(NotificationGroups.REMINDER)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Risaina").setContentText(ReminderText.PRIVATE_TITLE).build(),
            )
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
        if (entry != null && entry.kind == CalendarKind.TODO && !entry.done) {
            builder.addAction(0, "Done", CalendarDoneReceiver.pendingIntent(context, entry.id, TAG, id))
        }
        try {
            NotificationManagerCompat.from(context).notify(TAG, id, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    fun cancel(tag: String, id: Int) = NotificationManagerCompat.from(context).cancel(tag, id)

    companion object {
        const val TAG = "reminder"
        fun idFor(key: String): Int = key.hashCode()
    }
}
