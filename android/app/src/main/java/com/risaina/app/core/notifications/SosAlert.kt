package com.risaina.app.core.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.risaina.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SOS from the partner. Uses the ALARM audio stream (not muted by the ringer's silent mode),
 * a long vibration, and FLAG_INSISTENT so it keeps ringing until opened. If the user granted
 * Do Not Disturb access, the channel is created with bypassDnd so it also breaks through DND.
 * Shown even when Risaina's other notifications are off: an SOS must always reach the person.
 */
@Singleton
class SosAlert @Inject constructor(@ApplicationContext private val context: Context) {
    companion object { const val ID = 9111 }

    private fun channelId(): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        val dnd = nm.isNotificationPolicyAccessGranted
        val id = if (dnd) "sos_dnd" else "sos"
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, "SOS from your partner", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings even on silent so you never miss an emergency"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                if (dnd) setBypassDnd(true)
            })
        }
        return id
    }

    fun show(partnerName: String, message: String?, sosId: String?) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("risaina://app/sos/${sosId ?: "latest"}")).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, channelId())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🆘 SOS from $partnerName")
            .setContentText(message ?: "Please check on them now")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message ?: "Please check on them now"))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        try { NotificationManagerCompat.from(context).notify(ID, n) } catch (_: SecurityException) {}
    }

    fun clear() = NotificationManagerCompat.from(context).cancel(ID)
}
