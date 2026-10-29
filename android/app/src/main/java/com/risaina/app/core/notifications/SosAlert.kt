package com.risaina.app.core.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.risaina.app.R
import com.risaina.app.core.sos.RingState
import com.risaina.app.core.sos.SosAlarmActivity
import com.risaina.app.core.sos.SosAlarmService
import com.risaina.app.core.sos.SosCopy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SOS notifications. The ringing itself is done by [SosAlarmService] (alarm stream, alarm vibration,
 * full-screen alarm screen), which works even when Risaina's notifications are off; these are the
 * notifications around it. [showFallback] is the old notification-only alarm, used only when the
 * system refuses to start the alarm service and an exact alarm cannot be scheduled either.
 */
@Singleton
class SosAlert @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        const val SOS_GROUP = "risaina_sos_alarm"
        const val ID = 9111
        const val DND_RING_ID = 9112
        const val MISSED_ID = 9113
        const val SAFE_ID = 9114
        const val NOT_SENT_ID = 9115
        const val RESPOND_ID = 9116
        private const val TAG = "RisainaSos"
        private const val TEST_FALLBACK_TEXT = "The alarm couldn't start on this phone, so an SOS would only show like this. Tap to check Safety."
    }

    private val nm get() = NotificationManagerCompat.from(context)

    /** Posting is possible: app notifications on and, on Android 13+, the permission granted. */
    fun canPost(): Boolean =
        nm.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun alarmScreen(): PendingIntent = PendingIntent.getActivity(
        context, ID, SosAlarmActivity.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The foreground-service notification of the ringing alarm (its full-screen intent opens the alarm screen). */
    fun alarmNotification(state: RingState): Notification {
        val title = SosCopy.title(state.partnerName, state.panic, state.test)
        val publicText = SosCopy.lockedText(state.panic, state.test)
        val text = if (state.previews) SosCopy.text(state.message, state.panic, state.test) else publicText
        val screen = alarmScreen()
        // On the lock screen only "X sent an SOS" when previews are off; the message after unlocking.
        val public = NotificationCompat.Builder(context, NotificationChannels.SOS_ALARM)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(publicText).build()
        return NotificationCompat.Builder(context, NotificationChannels.SOS_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(SosCopy.text(state.message, state.panic, state.test)))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(if (state.previews) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Without this, Android 12+ may hold a foreground-service notification back for 10 s.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            // Its own group: without one, Android 15+ bundles it with Risaina's other notifications
            // (an auto-group "Aggregate_AlertingSection"), and on the test emulator the full-screen
            // alarm was then not launched with the screen off.
            .setGroup(SOS_GROUP)
            .addAction(0, if (state.test) "Stop test" else "I'm on it", SosAlarmService.actionPending(context, SosAlarmService.ACTION_ACK))
            // Android 14+ lets people swipe this away: that silences the sound but does not acknowledge.
            .setDeleteIntent(SosAlarmService.actionPending(context, SosAlarmService.ACTION_SILENCE))
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
            .build()
    }

    /** Insistent ringtone-stream notification for a Do Not Disturb mode that mutes the alarm stream. */
    fun postDndRinger(state: RingState) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, NotificationChannels.SOS_RING_DND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(SosCopy.title(state.partnerName, state.panic, state.test))
            .setContentText(SosCopy.lockedText(state.panic, state.test))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(alarmScreen())
            .setDeleteIntent(SosAlarmService.actionPending(context, SosAlarmService.ACTION_SILENCE))
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        notify(DND_RING_ID, n)
    }

    fun cancelDndRinger() = nm.cancel(DND_RING_ID)

    /** After a swipe: quiet, still there, still unacknowledged. */
    fun postRespond(state: RingState) {
        if (!canPost() || state.test) return
        post(RESPOND_ID, SosCopy.title(state.partnerName, state.panic, false), "Tap to respond", muted = true)
    }

    fun postMissed(partnerName: String, panic: Boolean) {
        if (!canPost()) return
        post(MISSED_ID, "Missed SOS from $partnerName", if (panic) "They said they're in trouble. Check on them now." else "Please check on them now.")
    }

    fun postSafeNow(partnerName: String) {
        if (!canPost()) return
        post(SAFE_ID, "$partnerName says they're safe now", "The SOS is over.")
    }

    /**
     * Opens the panic screen (its "not sent" panel with Call / Text, or a fresh hold-to-send), not
     * sos/latest: that one shows the partner's SOS, the receiver's view, to the person in trouble.
     */
    fun postNotSent(partnerName: String) {
        if (!canPost()) return
        post(NOT_SENT_ID, "Your SOS couldn't be sent", "Please call $partnerName directly.", link = "risaina://app/panic")
    }

    /**
     * The notices around an SOS share NotificationGroups.SAFETY with "is sharing", so Android 15+ never
     * bundles them with Risaina's other notifications. A [muted] one makes no sound and does not pop
     * up (NotificationGroups.alertBehavior); setSilent used to put it into AndroidX's "silent" group.
     */
    private fun post(id: Int, title: String, text: String, muted: Boolean = false, link: String = "risaina://app/sos/latest") {
        val n = NotificationCompat.Builder(context, NotificationChannels.SOS_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(deepLink(link, id))
            .setAutoCancel(true)
            .setGroup(NotificationGroups.SAFETY)
            .setGroupAlertBehavior(NotificationGroups.alertBehavior(muted))
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
            .build()
        notify(id, n)
    }

    private fun deepLink(uri: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Returns false when Android refused it (notification permission revoked meanwhile). */
    private fun notify(id: Int, n: Notification): Boolean = try {
        nm.notify(id, n)
        true
    } catch (_: SecurityException) {
        false
    }

    // --- fallback: notification-only alarm ---------------------------------------------------

    private fun fallbackChannel(): String {
        val sys = context.getSystemService(NotificationManager::class.java)
        val dnd = sys.isNotificationPolicyAccessGranted
        val id = if (dnd) "sos_dnd" else "sos"
        if (sys.getNotificationChannel(id) == null) {
            sys.createNotificationChannel(NotificationChannel(id, "SOS from your partner (backup)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Used only when the SOS alarm could not start"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                if (dnd) setBypassDnd(true)
            })
        }
        return id
    }

    /**
     * Notification-only alarm: insistent, alarm stream, full-screen intent. Needs Risaina's
     * notifications to be on; when they are off this can do nothing, says so in the log instead of
     * failing silently, and returns false (so nothing is reported to the sender as ringing).
     *
     * It opens the SOS in the app ([sosId], or the latest from the partner when the push did not
     * say), where "I'm on it" is: the alarm screen would close at once, because no alarm service
     * runs in this case. A [test] says so and is not insistent.
     */
    fun showFallback(partnerName: String, message: String?, panic: Boolean, previews: Boolean, sosId: String? = null, test: Boolean = false): Boolean {
        if (!canPost()) {
            Log.w(TAG, "SOS fallback not shown: Risaina's notifications are off and the alarm service could not start")
            return false
        }
        val screen = deepLink(if (test) "risaina://app/safety" else "risaina://app/sos/${sosId ?: "latest"}", ID)
        val title = SosCopy.title(partnerName, panic, test)
        // What the test found out: on this phone an SOS would not ring, only show like this.
        val text = when {
            test -> TEST_FALLBACK_TEXT
            previews -> SosCopy.text(message, panic, false)
            else -> SosCopy.lockedText(panic, false)
        }
        val public = NotificationCompat.Builder(context, fallbackChannel()).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(if (test) TEST_FALLBACK_TEXT else SosCopy.lockedText(panic, false)).build()
        val n = NotificationCompat.Builder(context, fallbackChannel())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(if (previews) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(screen)
            .apply { if (!test) setFullScreenIntent(screen, true) }
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
            .build()
        if (!test) n.flags = n.flags or Notification.FLAG_INSISTENT
        return notify(ID, n)
    }

    fun clear() {
        nm.cancel(ID)
        nm.cancel(DND_RING_ID)
        nm.cancel(RESPOND_ID)
    }

    /**
     * After "I'm on it" for an SOS that is not ringing: removes the quiet "tap to respond" and the
     * missed-SOS notices, and the notification-only fallback alarm unless another SOS is ringing
     * (that one's alarm notification shares [ID]).
     */
    fun clearResponded(alarmRinging: Boolean) {
        nm.cancel(RESPOND_ID)
        nm.cancel(MISSED_ID)
        if (!alarmRinging) nm.cancel(ID)
    }
}
