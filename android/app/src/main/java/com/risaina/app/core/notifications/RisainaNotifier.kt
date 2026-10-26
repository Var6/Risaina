package com.risaina.app.core.notifications

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.risaina.app.R
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.domain.QuietHoursPolicy
import com.risaina.app.core.model.MessageType
import com.risaina.app.core.sync.Incoming
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds notifications locally from synced data. Honours: notification permission, the user's
 * master toggle, preview privacy, and quiet hours (silent channel). Nothing intimate is logged.
 */
@Singleton
class RisainaNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesStore: PreferencesStore,
    private val coupleDao: CoupleDao,
    private val time: TimeProvider,
    private val diagnostics: DeliveryDiagnostics,
) {
    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun appInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /**
     * Opening Risaina means the partner's ping or gift and the game nudge were seen: remove those
     * notifications so an old gift never lingers in the shade. Chat messages stay until read or
     * tapped (they are not "seen" just because the app opened on Home), and SOS, the lock-screen
     * card and service notifications are left alone.
     */
    fun clearPartnerAlerts() {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(PING_NOTIFICATION_ID)
        nm.cancel(GAME_NOTIFICATION_ID)
        nm.cancel(MESSAGE_FALLBACK_ID)
    }

    private fun isShowing(id: Int): Boolean = runCatching {
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == id }
    }.getOrDefault(false)

    /**
     * A generic alert for this kind was posted from the push moments ago. The detailed notification
     * then arrives silently: updating the same id in place is silent anyway (onlyAlertOnce), but if
     * the person already tapped or dismissed the generic one, a fresh post would buzz a second time.
     */
    private fun followsFastAlert(kind: String): Boolean =
        System.currentTimeMillis() - diagnostics.lastFastAlertAt(kind) in 0 until FAST_ALERT_WINDOW_MS

    suspend fun notifyIncoming(item: Incoming) {
        if (!canPost()) return
        val prefs = preferencesStore.current()
        if (!prefs.notificationsEnabled) return
        if (appInForeground()) return // the UI reveals it in place
        val partner = coupleDao.partner()?.displayName ?: "Your partner"
        val quiet = !QuietHoursPolicy.shouldAlert(prefs.quietHours, prefs.allowPushInQuietHours, time.now())
        when (item) {
            is Incoming.NewPing -> {
                val p = item.ping
                if (p.seenAt != null) return // already opened (e.g. on the other screen); nothing to announce
                val gift = com.risaina.app.feature.ping.Expressions.isGift(p.message)
                val title = when {
                    !prefs.previewInNotifications -> "$partner sent you something"
                    gift -> "${p.emoji}  $partner sent you a gift"
                    else -> "${p.emoji}  ${p.type.label} from $partner"
                }
                val body = when {
                    !prefs.previewInNotifications -> "Tap to see"
                    gift -> com.risaina.app.feature.ping.Expressions.giftLine(p.message)!!
                    else -> p.message ?: p.type.defaultMessage.ifBlank { "Tap to see" }
                }
                post(
                    id = PING_NOTIFICATION_ID,
                    onlyAlertOnce = true,
                    silent = followsFastAlert(FAST_PING),
                    channel = if (quiet) NotificationChannels.PINGS_QUIET else NotificationChannels.PINGS,
                    title = title, body = body, deepLink = "risaina://app/ping/${p.id}",
                    category = NotificationCompat.CATEGORY_SOCIAL, priority = if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH,
                )
            }
            is Incoming.NewMessage -> {
                val m = item.message
                val body = when {
                    !prefs.previewInNotifications -> "New message"
                    m.type == MessageType.PHOTO && m.viewOnce -> "Sent you a view-once photo"
                    m.type == MessageType.PHOTO -> "Sent you a photo"
                    m.type == MessageType.VOICE -> "Sent you a voice note"
                    else -> m.text ?: "New message"
                }
                // The generic "sent you a message" from the push is replaced by the real one.
                if (isShowing(MESSAGE_FALLBACK_ID)) NotificationManagerCompat.from(context).cancel(MESSAGE_FALLBACK_ID)
                post(
                    id = m.id.hashCode(),
                    silent = followsFastAlert(FAST_MESSAGE),
                    channel = if (quiet) NotificationChannels.MESSAGES_QUIET else NotificationChannels.MESSAGES,
                    title = partner, body = body, deepLink = "risaina://app/messages",
                    category = NotificationCompat.CATEGORY_MESSAGE, priority = if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT,
                )
            }
            Incoming.WidgetChanged -> Unit
            is Incoming.Sos -> Unit // SosAlert already rang
            is Incoming.Game -> {
                val body = when (item.action) {
                    "created" -> "challenged you to Stone Paper Scissors Thread ✊✋✌️🧵"
                    "moved" -> "made their move. Your turn!"
                    "revealed", "draw" -> "The result is in. Open the chat to see"
                    "challenged" -> "sealed a truth and a dare for you 😈 Pick one"
                    "chosen" -> "made their pick. Now it's on them"
                    "done" -> "says it's done. Confirm it?"
                    "confirmed" -> "confirmed it ✓ You're off the hook"
                    "rejected" -> "says not quite. Try again 😄"
                    "rematch" -> "wants a rematch"
                    else -> null
                } ?: return
                post(
                    id = GAME_NOTIFICATION_ID, channel = if (quiet) NotificationChannels.MESSAGES_QUIET else NotificationChannels.MESSAGES,
                    title = partner, body = if (prefs.previewInNotifications) "${partner.substringBefore(" ")} $body" else "New activity",
                    deepLink = "risaina://app/messages", category = NotificationCompat.CATEGORY_MESSAGE, priority = NotificationCompat.PRIORITY_DEFAULT,
                )
            }
            Incoming.Unlinked -> post(
                id = 9001, channel = NotificationChannels.SYSTEM, title = "Risaina",
                body = "Your pairing has ended.", deepLink = "risaina://app/home",
                category = NotificationCompat.CATEGORY_STATUS, priority = NotificationCompat.PRIORITY_LOW,
            )
        }
    }

    /**
     * Called straight from the FCM service, before any network sync, so the partner's phone buzzes
     * within a second. Content is generic (the push carries no content); the sync that follows
     * updates the same notification silently with the real ping.
     */
    suspend fun notifyPingArrivedFast() {
        if (!canPost()) return
        val prefs = preferencesStore.current()
        if (!prefs.notificationsEnabled || appInForeground()) return
        val partner = coupleDao.partner()?.displayName ?: "Your partner"
        val quiet = !QuietHoursPolicy.shouldAlert(prefs.quietHours, prefs.allowPushInQuietHours, time.now())
        post(
            id = PING_NOTIFICATION_ID,
            channel = if (quiet) NotificationChannels.PINGS_QUIET else NotificationChannels.PINGS,
            title = "$partner is thinking of you", body = "Tap to see", deepLink = "risaina://app/home",
            category = NotificationCompat.CATEGORY_SOCIAL, priority = if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH,
        )
        diagnostics.recordFastAlert(FAST_PING)
    }

    /**
     * Straight from a message push, before the network: the phone alerts within a second even when
     * the sync after a Doze wake is slow or fails. Generic (the push carries no content); the real
     * message notification replaces it silently once the sync has it.
     */
    suspend fun notifyMessageArrivedFast() {
        if (!canPost()) return
        val prefs = preferencesStore.current()
        if (!prefs.notificationsEnabled || appInForeground()) return
        val partner = coupleDao.partner()?.displayName ?: "Your partner"
        val quiet = !QuietHoursPolicy.shouldAlert(prefs.quietHours, prefs.allowPushInQuietHours, time.now())
        post(
            id = MESSAGE_FALLBACK_ID,
            channel = if (quiet) NotificationChannels.MESSAGES_QUIET else NotificationChannels.MESSAGES,
            title = partner, body = "Sent you a message", deepLink = "risaina://app/messages",
            category = NotificationCompat.CATEGORY_MESSAGE, priority = if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT,
        )
        diagnostics.recordFastAlert(FAST_MESSAGE)
    }

    /**
     * Answer to "Send me a test push" (Delivery health). Shown whatever the in-app notification
     * switches say, because the person asked for it; it proves the whole path works with the app
     * closed: push delivery, waking the app, and posting on the Touch Pings channel.
     */
    fun notifyTestPush(latencyMillis: Long?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val took = latencyMillis?.let { " It took ${formatLatency(it)}." } ?: ""
        post(
            id = TEST_NOTIFICATION_ID, channel = NotificationChannels.PINGS,
            title = "Test notification ✓", body = "Pushes reach this phone.$took", deepLink = "risaina://app/home",
            category = NotificationCompat.CATEGORY_STATUS, priority = NotificationCompat.PRIORITY_HIGH,
        )
    }

    companion object {
        const val PING_NOTIFICATION_ID = 4242
        const val GAME_NOTIFICATION_ID = 5050
        /** The generic "sent you a message" posted from a push before the sync (stream: delivery). */
        const val MESSAGE_FALLBACK_ID = 4343
        const val TEST_NOTIFICATION_ID = 4344
        private const val FAST_PING = "ping"
        private const val FAST_MESSAGE = "message"
        private const val FAST_ALERT_WINDOW_MS = 2 * 60_000L

        /** "0.8 s", "12 s", "3 min". */
        fun formatLatency(ms: Long): String = when {
            ms < 10_000 -> "${"%.1f".format(java.util.Locale.ROOT, ms / 1000.0)} s"
            ms < 120_000 -> "${ms / 1000} s"
            else -> "${ms / 60_000} min"
        }
    }

    private fun post(id: Int, channel: String, title: String, body: String, deepLink: String, category: String, priority: Int, onlyAlertOnce: Boolean = false, silent: Boolean = false) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(deepLink)).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(category)
            .setPriority(priority)
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setSilent(silent)
            .setVibrate(if (channel == NotificationChannels.PINGS && !silent) NotificationChannels.HEARTBEAT else null)
            .setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Risaina").setContentText("New activity").build(),
            )
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between check and post; nothing to do.
        }
    }
}
