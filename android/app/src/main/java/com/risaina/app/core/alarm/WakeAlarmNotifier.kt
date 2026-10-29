package com.risaina.app.core.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
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
import com.risaina.app.core.notifications.NotificationChannels
import com.risaina.app.core.notifications.NotificationGroups
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wake together's notifications. The ring itself is an insistent, full-screen notification on the
 * alarm channel: Android plays the tone on the alarm stream, so it keeps ringing after this process
 * is gone, and no foreground service is needed. Each kind has its own group (NotificationGroups),
 * and none uses setSilent: quiet ones go on a low-importance channel.
 */
@Singleton
class WakeAlarmNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val nm get() = NotificationManagerCompat.from(context)

    /** App notifications on and, on Android 13+, the permission granted. */
    fun canPost(): Boolean =
        nm.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /**
     * The ringing alarm. Posted again (with a new partner line) without onlyAlertOnce: an update that
     * may not alert would stop the insistent sound, so it rings on. Swiping it away snoozes; it
     * never silences for good. [time] is "6:30 am" on this phone.
     */
    fun postRing(ring: WakeRing, time: String, partnerLine: String?) {
        if (!canPost()) return
        val screen = PendingIntent.getActivity(context, RING_ID, WakeAlarmActivity.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = listOfNotNull(time, partnerLine).joinToString(" · ")
        // On a locked phone with previews hidden: no label, no partner, only that an alarm rings.
        val public = NotificationCompat.Builder(context, NotificationChannels.WAKE_ALARM)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Alarm · $time").build()
        val b = NotificationCompat.Builder(context, NotificationChannels.WAKE_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ring.test) "Test alarm" else ring.label)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            // Its own group, like the SOS alarm: Android 15+ bundles ungrouped notifications, and a
            // bundled full-screen alarm was not launched with the screen off.
            .setGroup(NotificationGroups.WAKE_ALARM)
            // A backstop only: the ring's own timeout snoozes it first (WakeAlarmController).
            .setTimeoutAfter((if (ring.test) WakeAlarmRules.TEST_RING_MS else WakeAlarmRules.RING_MS) + 60_000)
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
        if (ring.test) {
            b.addAction(0, "Stop test", action(WakeActions.UP, ring.alarmId))
            b.setDeleteIntent(action(WakeActions.UP, ring.alarmId))
        } else {
            b.addAction(0, "Snooze ${ring.snoozeMinutes} min", action(WakeActions.SNOOZE, ring.alarmId))
            b.addAction(0, "I'm up", action(WakeActions.UP, ring.alarmId))
            // Android 14+ lets people swipe an ongoing notification away: that is a snooze.
            b.setDeleteIntent(action(WakeActions.SNOOZE, ring.alarmId))
        }
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        notify(RING_ID, n)
    }

    fun cancelRing() = nm.cancel(RING_ID)

    /** "Snoozed until 6:40 am" while snoozed, with "I'm up". [partnerLine]: "Raina is up ☀ 6:34 am". */
    fun postSnoozed(ring: WakeRing, until: String, partnerLine: String?) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, NotificationChannels.WAKE_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Snoozed until $until")
            .setContentText(listOfNotNull(ring.label, partnerLine).joinToString(" · "))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(NotificationGroups.WAKE_STATUS)
            .setContentIntent(wakeScreen(STATUS_ID))
            .addAction(0, "I'm up", action(WakeActions.UP, ring.alarmId))
            .apply { ring.until?.let { setWhen(it); setShowWhen(true); setUsesChronometer(true); setChronometerCountDown(true) } }
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
            .build()
        notify(STATUS_ID, n)
    }

    fun cancelStatus() = nm.cancel(STATUS_ID)

    /**
     * "Missed: Wake together 6:30 am". [phoneWasOff]: the ring was due while the phone was off or
     * the alarm was lost; otherwise it rang twice with no answer.
     */
    fun postMissed(label: String, time: String, phoneWasOff: Boolean) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, NotificationChannels.WAKE_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Missed: $label $time")
            .setContentText(if (phoneWasOff) "Your phone was off or asleep when it was due." else "It rang twice and nobody answered.")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(NotificationGroups.WAKE_MISSED)
            .setContentIntent(wakeScreen(MISSED_ID))
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
            .build()
        notify(MISSED_ID, n)
    }

    /**
     * The partner set (or changed, or turned on) an alarm that rings here, so a 5 am alarm never
     * comes as a surprise; "Not on my phone" turns it off here only. One per alarm (tagged with its
     * id), so two alarms set in a row each keep their own button. [text] from
     * WakeAlarmRules.noticeText; [previews] off: no times on the lock screen.
     */
    fun postSet(alarmId: String, text: String, quiet: Boolean, previews: Boolean) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, if (quiet) NotificationChannels.WAKE_UPDATES_QUIET else NotificationChannels.WAKE_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Wake together")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(if (previews) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion(NotificationChannels.WAKE_UPDATES))
            .setGroup(NotificationGroups.WAKE_SET)
            .setContentIntent(wakeScreen(SET_ID))
            .addAction(0, "Not on my phone", action(WakeActions.NOT_ON_MY_PHONE, alarmId))
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
            .build()
        notify(SET_ID, n, tag = alarmId)
    }

    fun cancelSet(alarmId: String) = nm.cancel(alarmId, SET_ID)

    /** "Raina is up too · 6:34 am", once this person is up themselves. */
    fun postPartnerUp(title: String, quiet: Boolean, previews: Boolean) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, if (quiet) NotificationChannels.WAKE_UPDATES_QUIET else NotificationChannels.WAKE_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Wake together")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(if (previews) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion(NotificationChannels.WAKE_UPDATES))
            .setGroup(NotificationGroups.WAKE_PARTNER)
            .setContentIntent(wakeScreen(PARTNER_UP_ID))
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
            .build()
        notify(PARTNER_UP_ID, n)
    }

    /** Sign-out or unlink: nothing of Wake together stays in the shade, the per-alarm notices included. */
    fun cancelAll() {
        ALL_IDS.forEach { nm.cancel(it) }
        runCatching { context.getSystemService(NotificationManager::class.java).activeNotifications }.getOrNull()
            ?.filter { it.id in ALL_IDS && it.tag != null }
            ?.forEach { nm.cancel(it.tag, it.id) }
    }

    private fun publicVersion(channel: String): Notification = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Risaina").setContentText("New activity").build()

    private fun action(action: String, alarmId: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, WakeAlarmReceiver::class.java).setAction(action).setData(WakeActions.uri(alarmId)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun wakeScreen(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(Intent.ACTION_VIEW, Uri.parse("risaina://app/wake")).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** False when Android refused it (the permission was revoked meanwhile). */
    private fun notify(id: Int, n: Notification, tag: String? = null): Boolean = try {
        nm.notify(tag, id, n)
        true
    } catch (_: SecurityException) {
        false
    }

    companion object {
        const val RING_ID = 9331
        const val STATUS_ID = 9332
        const val MISSED_ID = 9333
        const val SET_ID = 9334
        const val PARTNER_UP_ID = 9335
        val ALL_IDS = listOf(RING_ID, STATUS_ID, MISSED_ID, SET_ID, PARTNER_UP_ID)
    }
}
