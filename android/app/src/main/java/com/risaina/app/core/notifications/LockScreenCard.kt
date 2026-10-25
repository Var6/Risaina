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
        // v2: default importance (still no sound or vibration). Android hides *low*-importance
        // ("silent") notifications on the lock screen by default, which hid the card where it matters.
        const val CHANNEL = "lock_card_v2"
        private const val OLD_CHANNEL = "lock_card"
        private const val ID = 7171
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lock-screen card", NotificationManager.IMPORTANCE_DEFAULT).apply {
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
        // Order: an unseen gift, then an unseen ping ("<partner> reached out"), then a recent gift,
        // then their mood or note. Unseen = arrived since this phone last opened Risaina.
        val giftFresh = s.latestGiftAtMillis != null && System.currentTimeMillis() - s.latestGiftAtMillis < 3L * 24 * 3600 * 1000
        val (title, line) = when {
            s.giftUnseen -> "${s.latestGiftEmoji ?: "🎁"}  A gift from $name" to (s.latestGiftLine ?: "Open Risaina to see it")
            s.pingUnseen -> "${s.latestPingEmoji ?: "💗"}  $name reached out" to "${s.latestPingLine ?: "Thinking of you"} · tap to send one back"
            giftFresh -> "${s.latestGiftEmoji ?: "🎁"}  A gift from $name" to (s.latestGiftLine ?: "Open Risaina to see it")
            else -> listOfNotNull(name, s.latestEmoji).joinToString("  ") to (s.latestNoteText ?: s.latestEmojiLabel ?: "Always a little closer")
        }
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
            .setOngoing(true).setOnlyAlertOnce(true).setSound(null).setVibrate(null)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
        try { nm.notify(ID, b.build()) } catch (_: SecurityException) {}
    }
}
