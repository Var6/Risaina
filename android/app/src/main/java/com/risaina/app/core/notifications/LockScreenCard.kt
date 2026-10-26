package com.risaina.app.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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

/** What the lock-screen card says. Pure, so it is unit-tested (LockCardContentTest). */
data class LockCardContent(val title: String, val line: String, val eventAtMillis: Long?) {
    /** Identity of the content: the card is re-posted only when this changes. */
    val key: String get() = "$title|$line|${eventAtMillis ?: ""}"

    companion object {
        /**
         * Only something the person has NOT seen yet is announced: an unseen gift, then an unseen
         * ping. Otherwise it is a calm status line (their mood, note or emoji) with no time on it.
         * It used to say "A gift from …" for three days after any gift, rebuilt with a fresh "now"
         * time on every sync, so an old, already-opened gift kept looking new.
         */
        fun from(s: WidgetSnapshot): LockCardContent {
            val name = s.partnerName ?: "Your partner"
            return when {
                s.giftUnseen -> LockCardContent("${s.latestGiftEmoji ?: "🎁"}  A gift from $name", s.latestGiftLine ?: "Open Risaina to see it", s.latestGiftAtMillis)
                s.pingUnseen -> LockCardContent("${s.latestPingEmoji ?: "💗"}  $name reached out", "${s.latestPingLine ?: "Thinking of you"} · tap to send one back", s.latestPingAtMillis)
                else -> LockCardContent(listOfNotNull(name, s.latestEmoji).joinToString("  "), s.latestNoteText ?: s.latestEmojiLabel ?: "Always a little closer", null)
            }
        }
    }
}

/**
 * Opt-in "lock-screen card": a quiet, persistent notification with what your partner last sent
 * while it is new, then their mood or note. It works on every Android phone, unlike lock-screen
 * widgets, which only some Android versions and launchers allow. No sound, no buzz. It is shown in
 * full on the lock screen only because the user turned it on explicitly.
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
        private const val PREFS = "lock_card"
        private const val K_POSTED = "posted_key"
        private const val K_DISMISSED = "dismissed_key"
        const val ACTION_DISMISSED = "com.risaina.app.LOCK_CARD_DISMISSED"
        const val EXTRA_KEY = "key"
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    /** Remove the card (sign-out, unlink, or the setting turned off) and forget what it said. */
    fun cancel() {
        NotificationManagerCompat.from(context).cancel(ID)
        prefs.edit().clear().apply()
    }

    suspend fun update(s: WidgetSnapshot) {
        if (!preferencesStore.current().lockScreenCard || !s.paired) { cancel(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val content = LockCardContent.from(s)
        val showing = runCatching { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == ID } }.getOrDefault(false)
        // Post only when the content changed, or when the card vanished without the person swiping
        // it away (e.g. after the app was force-stopped). A swiped card stays away until there is
        // something new to say.
        val posted = prefs.getString(K_POSTED, null)
        val dismissed = prefs.getString(K_DISMISSED, null)
        val changed = content.key != posted
        if (!changed && (showing || dismissed == content.key)) return

        val open = PendingIntent.getActivity(
            context, ID,
            Intent(Intent.ACTION_VIEW, Uri.parse("risaina://app/home")).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val onDismiss = PendingIntent.getBroadcast(
            context, ID,
            Intent(context, LockCardDismissedReceiver::class.java).setAction(ACTION_DISMISSED).putExtra(EXTRA_KEY, content.key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.line))
            .setOngoing(true).setOnlyAlertOnce(true).setSound(null).setVibrate(null)
            // The real time of the gift or ping, or no time at all for the status line.
            .setShowWhen(content.eventAtMillis != null).apply { content.eventAtMillis?.let { setWhen(it) } }
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setDeleteIntent(onDismiss)
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
        try {
            NotificationManagerCompat.from(context).notify(ID, b.build())
            prefs.edit().putString(K_POSTED, content.key).apply()
        } catch (_: SecurityException) {}
    }
}

/** The person swiped the card away: remember what it said so the same content isn't re-posted. */
class LockCardDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LockScreenCard.ACTION_DISMISSED) return
        context.getSharedPreferences("lock_card", Context.MODE_PRIVATE).edit()
            .putString("dismissed_key", intent.getStringExtra(LockScreenCard.EXTRA_KEY)).apply()
    }
}
