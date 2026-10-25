package com.risaina.app.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.risaina.app.R
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.datastore.WidgetSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in "lock-screen card": a quiet, persistent notification that shows what your partner put on
 * your widgets (photo, note, emoji) on the lock screen. It works on every Android phone, unlike
 * lock-screen widgets, which only some Android versions and launchers allow. No sound, no buzz.
 * It is shown in full on the lock screen only because the user turned it on explicitly.
 */
@Singleton
class LockScreenCard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesStore: PreferencesStore,
) {
    companion object {
        const val CHANNEL = "lock_card"
        private const val ID = 7171
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lock-screen card", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Your partner's photo and note on your lock screen"
            setSound(null, null); enableVibration(false); setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
    }

    suspend fun update(s: WidgetSnapshot) {
        val nm = NotificationManagerCompat.from(context)
        if (!preferencesStore.current().lockScreenCard || !s.paired) { nm.cancel(ID); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val name = s.partnerName ?: "Your partner"
        // A gift is the star of the lock screen; otherwise their mood/note/emoji. No photos here.
        val giftFresh = s.latestGiftAtMillis != null && System.currentTimeMillis() - s.latestGiftAtMillis < 3L * 24 * 3600 * 1000
        val title = if (giftFresh) "${s.latestGiftEmoji ?: "🎁"}  A gift from $name" else listOfNotNull(name, s.latestEmoji).joinToString("  ")
        val line = if (giftFresh) s.latestGiftLine ?: "Open Risaina to see it" else s.latestNoteText ?: s.latestEmojiLabel ?: "Always a little closer"
        val open = PendingIntent.getActivity(
            context, ID,
            Intent(Intent.ACTION_VIEW, Uri.parse("risaina://app/home")).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
        try { nm.notify(ID, b.build()) } catch (_: SecurityException) {}
    }
}
