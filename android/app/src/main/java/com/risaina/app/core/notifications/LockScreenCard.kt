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

/**
 * Which of Risaina's own partner notifications are in the shade while the lock card is drawn, read
 * once from activeNotifications. Only [ping] changes what the card says; [widget] and [message] are
 * read so the tests can pin down that they never do.
 */
data class ShowingAlerts(val ping: Boolean = false, val widget: Boolean = false, val message: Boolean = false)

/**
 * What the lock-screen card says. Pure, so it is unit-tested (LockCardContentTest).
 *
 * [eventAtMillis] is the time shown on the card (only for an unseen gift or ping). [whenMillis] is
 * the notification's sort time: always a real item time when there is one, never "now", because
 * Android ranks by it and a fresh time on every re-post would lift an old card to the top.
 */
data class LockCardContent(val title: String, val line: String, val eventAtMillis: Long?, val whenMillis: Long? = eventAtMillis) {
    /** Identity of the content: the card is re-posted only when this changes. */
    val key: String get() = "$title|$line|${eventAtMillis ?: ""}"

    companion object {
        /**
         * The card never repeats something that has a notification of its own: with the card on, one
         * ping or message used to show twice on the partner's phone ("Touch from Raina" next to
         * "Raina reached out", "Raina: hello" next to a card saying "hello").
         *
         * - An unseen gift, then an unseen ping, is announced only while the ping notification is not
         *   [showing]: notifications off, the Touch Pings channel blocked, or the notification swiped
         *   away. That is what the card is for.
         * - Otherwise it is a calm line from "their widget" only (note, else mood, and its emoji) with
         *   no time on it; never a chat message or a ping, so a new message or ping leaves the card as
         *   it is and it is not posted again. A gift can no longer leak into it either.
         *
         * It used to say "A gift from …" for three days after any gift, rebuilt with a fresh "now"
         * time on every sync, so an old, already-opened gift kept looking new.
         */
        fun from(s: WidgetSnapshot, showing: ShowingAlerts = ShowingAlerts()): LockCardContent {
            val name = s.partnerName ?: "Your partner"
            return when {
                s.giftUnseen && !showing.ping -> LockCardContent("${s.latestGiftEmoji ?: "🎁"}  A gift from $name", s.latestGiftLine ?: "Open Risaina to see it", s.latestGiftAtMillis)
                s.pingUnseen && !showing.ping -> LockCardContent("${s.latestPingEmoji ?: "💗"}  $name reached out", "${s.latestPingLine ?: "Thinking of you"} · tap to send one back", s.latestPingAtMillis)
                else -> LockCardContent(
                    listOfNotNull(name, s.boardEmoji).joinToString("  "),
                    s.boardLine ?: "Always a little closer",
                    eventAtMillis = null,
                    whenMillis = s.boardAtMillis,
                )
            }
        }

        /**
         * Post only when the content changed, or when the card vanished without the person swiping
         * it away. A swipe sends the deleteIntent (stored as [dismissedKey]); a reboot, app update or
         * force-stop (ColorOS does one when the app is swiped out of Recents) removes the card with
         * no deleteIntent, and then it must come back. A swiped card stays away until there is
         * something new to say.
         */
        fun shouldPost(key: String, postedKey: String?, dismissedKey: String?, showing: Boolean): Boolean = when {
            key != postedKey -> true
            showing -> false
            else -> dismissedKey != key
        }
    }
}

/**
 * Opt-in "lock-screen card": a quiet, persistent notification with an unseen ping or gift whose own
 * notification is gone, otherwise the mood or note on "their widget". It works on every Android
 * phone, unlike lock-screen widgets, which only some Android versions and launchers allow. No sound,
 * no buzz. It is shown in full on the lock screen only because the user turned it on explicitly.
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
        private const val GROUP = "risaina_lock_card"
        internal const val PREFS = "lock_card"
        private const val K_POSTED = "posted_key"
        internal const val K_DISMISSED = "dismissed_key"
        /** Sort time for a calm card when nothing was ever received: the first post, kept stable. */
        private const val K_FIRST_POSTED_AT = "first_posted_at"
        const val ACTION_DISMISSED = "com.risaina.app.LOCK_CARD_DISMISSED"
        const val EXTRA_KEY = "key"
        private val MESSAGE_CHANNELS = setOf(NotificationChannels.MESSAGES, NotificationChannels.MESSAGES_QUIET)
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lock-screen card", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Your partner's photo and note on your lock screen"
            setSound(null, null); enableVibration(false); setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
    }

    private fun showing(): Boolean = runCatching { nm.activeNotifications.any { it.id == ID } }.getOrDefault(false)

    /** Remove the card (sign-out, unlink, or the setting turned off) and forget what it said. */
    fun cancel() {
        NotificationManagerCompat.from(context).cancel(ID)
        // Guarded: update() calls this on every recompute while the card is off (the default), and
        // an unconditional clear() would be a disk write per sync.
        if (prefs.all.isNotEmpty()) prefs.edit().clear().apply()
    }

    suspend fun update(s: WidgetSnapshot) {
        if (!preferencesStore.current().lockScreenCard || !s.paired) {
            // A card from an older version has no stored key, so also check what is showing.
            if (prefs.all.isNotEmpty() || showing()) cancel()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel()
        // The person blocked this channel in system settings: posting would do nothing.
        if (nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        // Read once. Syncs post each notification as its event is applied, before this recompute, and
        // the push handler's quick alert comes earlier still, so a new ping's notification is already
        // here. When Android will not say, nothing counts as showing: the card announces, as before.
        val active = runCatching { nm.activeNotifications.toList() }.getOrDefault(emptyList())
        val alerts = ShowingAlerts(
            ping = active.any { it.id == RisainaNotifier.PING_NOTIFICATION_ID },
            widget = active.any { it.id == RisainaNotifier.WIDGET_NOTIFICATION_ID },
            message = active.any { it.id != RisainaNotifier.GAME_NOTIFICATION_ID && it.notification.channelId in MESSAGE_CHANNELS },
        )
        val content = LockCardContent.from(s, alerts)
        if (!LockCardContent.shouldPost(content.key, prefs.getString(K_POSTED, null), prefs.getString(K_DISMISSED, null), active.any { it.id == ID })) return

        val sortAt = content.whenMillis
            ?: prefs.getLong(K_FIRST_POSTED_AT, 0L).takeIf { it > 0 }
            ?: System.currentTimeMillis().also { prefs.edit().putLong(K_FIRST_POSTED_AT, it).apply() }
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
            // The real time of the gift or ping is shown; the calm status line shows no time. Either
            // way `when` is a stable item time (the builder would otherwise stamp "now").
            .setShowWhen(content.eventAtMillis != null).setWhen(sortAt)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Its own group, so Android 15+ never bundles the card with Risaina's other notifications.
            .setGroup(GROUP)
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
        context.getSharedPreferences(LockScreenCard.PREFS, Context.MODE_PRIVATE).edit()
            .putString(LockScreenCard.K_DISMISSED, intent.getStringExtra(LockScreenCard.EXTRA_KEY)).apply()
    }
}
