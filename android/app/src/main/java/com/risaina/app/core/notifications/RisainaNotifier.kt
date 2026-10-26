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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val gameRepository: com.risaina.app.core.data.GameRepository,
    private val diagnostics: DeliveryDiagnostics,
    private val pingDao: com.risaina.app.core.database.PingDao,
    private val messageDao: com.risaina.app.core.database.MessageDao,
) {
    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun appInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /**
     * The quick alert ("is thinking of you") checks the database and then posts; the sync applies
     * the same item and posts the detailed one under the same id. The SyncWorker starts in this
     * process at once, so the two can interleave: without this lock the generic line could land
     * after the detailed one and replace it for good. Held across check and post on the quick path,
     * and across the post of every detailed notification.
     */
    private val postLock = Mutex()

    /**
     * Opening Risaina means the partner's ping or gift, the game nudge and "updated your widget" were
     * seen: remove those notifications so an old gift never lingers in the shade. Chat messages stay
     * until read or tapped (they are not "seen" just because the app opened on Home), and SOS, the
     * lock-screen card and service notifications are left alone.
     */
    fun clearPartnerAlerts() {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(PING_NOTIFICATION_ID)
        nm.cancel(GAME_NOTIFICATION_ID)
        nm.cancel(WIDGET_NOTIFICATION_ID)
    }

    /**
     * Sign-out, session expiry, account deletion and unlink wipe the chat on this phone, so
     * everything the partner sent must leave the shade and the lock screen too, unread chat messages
     * included (their text would otherwise outlive the wipe). [clearPartnerAlerts] keeps chat on
     * purpose and is only for opening the app. A ringing SOS stays: it must reach the person.
     */
    fun clearAllPartnerNotifications() {
        clearPartnerAlerts()
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(TEST_NOTIFICATION_ID)
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)?.activeNotifications
                ?.filter { clearedOnPurge(it.id, it.notification.channelId) }
                ?.forEach { nm.cancel(it.tag, it.id) }
        }
    }

    /**
     * A generic alert for this very item ([ref]) was posted from its push moments ago. The detailed
     * notification then arrives silently: updating the same id in place is silent anyway
     * (onlyAlertOnce), but if the person already tapped or dismissed the generic one, a fresh post
     * would buzz a second time. Matched per item, so another ping or message that came in the same
     * sync (and had no quick alert of its own) still alerts. A quick alert from an older server
     * carries no item id and matches any item of its kind.
     */
    private fun followsFastAlert(kind: String, ref: String): Boolean {
        val (at, fastRef) = diagnostics.lastFastAlert(kind)
        return FastAlerts.follows(at, fastRef, ref, System.currentTimeMillis())
    }

    suspend fun notifyIncoming(item: Incoming) {
        if (!canPost()) return
        val prefs = preferencesStore.current()
        if (!prefs.notificationsEnabled) return
        if (appInForeground()) return // the UI reveals it in place
        val partner = coupleDao.partner()?.displayName ?: "Your partner"
        val quiet = !QuietHoursPolicy.shouldAlert(prefs.quietHours, prefs.allowPushInQuietHours, time.now())
        postLock.withLock { postDetailed(item, prefs, partner, quiet) }
    }

    private fun postDetailed(item: Incoming, prefs: com.risaina.app.core.model.Preferences, partner: String, quiet: Boolean) {
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
                    silent = followsFastAlert(FAST_PING, p.id),
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
                // Same id as the quick "sent you a message" posted from the push (messageNotificationId),
                // so this replaces it in place without a second alert.
                post(
                    id = messageNotificationId(m.id),
                    onlyAlertOnce = true,
                    silent = followsFastAlert(FAST_MESSAGE, m.id),
                    channel = if (quiet) NotificationChannels.MESSAGES_QUIET else NotificationChannels.MESSAGES,
                    title = partner, body = body, deepLink = "risaina://app/messages",
                    category = NotificationCompat.CATEGORY_MESSAGE, priority = if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT,
                )
            }
            // The partner changed "Their widget" (mood, note, photo): a quiet line while the widget
            // redraws, so this high-priority push still ends in something visible.
            Incoming.WidgetChanged -> {
                ensureWidgetChannel()
                post(
                    id = WIDGET_NOTIFICATION_ID, onlyAlertOnce = true, channel = WIDGET_CHANNEL,
                    title = partner, body = "${partner.substringBefore(" ")} updated your widget", deepLink = "risaina://app/home",
                    category = NotificationCompat.CATEGORY_STATUS, priority = NotificationCompat.PRIORITY_LOW,
                )
            }
            is Incoming.Sos -> Unit // SosAlert already rang
            is Incoming.Game -> {
                // Games stream. The game copy may lag behind (it is refreshed separately); it is only
                // used to drop a "your turn" text for a game that has closed since.
                val current = gameRepository.games.value.firstOrNull { it.id == item.id }
                val body = GameNotificationText.body(item.kind, item.action, item.topic)
                if (body == null || GameNotificationText.isStale(item.action, current?.status)) {
                    // A cancelled or called-off game must not leave "Your turn" in the shade.
                    if (item.action in GameNotificationText.CLEARS || current?.status == "cancelled") {
                        NotificationManagerCompat.from(context).cancel(GAME_NOTIFICATION_ID)
                    }
                    return
                }
                post(
                    id = GAME_NOTIFICATION_ID, channel = if (quiet) NotificationChannels.MESSAGES_QUIET else NotificationChannels.MESSAGES,
                    title = partner, body = if (prefs.previewInNotifications) "${partner.substringBefore(" ")} $body" else "New activity",
                    deepLink = "risaina://app/messages", category = NotificationCompat.CATEGORY_MESSAGE, priority = NotificationCompat.PRIORITY_DEFAULT,
                    onlyAlertOnce = GameNotificationText.alertOnce(item.action),
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
     * within a second. Content is generic (the push carries no content, only the ping's opaque id
     * [pingId], absent from older servers); the sync that follows updates the same notification
     * silently with the real ping.
     */
    suspend fun notifyPingArrivedFast(pingId: String?) {
        if (!canPost()) return
        val prefs = preferencesStore.current()
        if (!prefs.notificationsEnabled || appInForeground()) return
        val partner = coupleDao.partner()?.displayName ?: "Your partner"
        val quiet = !QuietHoursPolicy.shouldAlert(prefs.quietHours, prefs.allowPushInQuietHours, time.now())
        postLock.withLock {
            // An earlier push's sync, or the SyncWorker running right now, may already have brought
            // this ping and posted its real notification: a generic line would only overwrite it.
            // Checked under the lock, so the sync cannot post in between (see postLock).
            if (pingId != null && pingDao.byId(pingId) != null) return
            post(
                id = PING_NOTIFICATION_ID,
                channel = if (quiet) NotificationChannels.PINGS_QUIET else NotificationChannels.PINGS,
                title = "$partner is thinking of you", body = "Tap to see", deepLink = "risaina://app/home",
                category = NotificationCompat.CATEGORY_SOCIAL, priority = if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH,
            )
            diagnostics.recordFastAlert(FAST_PING, pingId)
        }
    }

    /**
     * Straight from a message push, before the network: the phone alerts within a second even when
     * the sync after a Doze wake is slow or fails. Generic (the push carries no content, only the
     * message's opaque id [messageId]), posted under the id the real message notification uses, so
     * that one replaces it in place once the sync has it.
     */
    suspend fun notifyMessageArrivedFast(messageId: String) {
        if (!canPost()) return
        val prefs = preferencesStore.current()
        if (!prefs.notificationsEnabled || appInForeground()) return
        val partner = coupleDao.partner()?.displayName ?: "Your partner"
        val quiet = !QuietHoursPolicy.shouldAlert(prefs.quietHours, prefs.allowPushInQuietHours, time.now())
        postLock.withLock {
            // Already synced (see notifyPingArrivedFast): its real notification was posted, or was
            // left out on purpose; a generic "Sent you a message" must not replace it.
            if (messageDao.byId(messageId) != null) return
            post(
                id = messageNotificationId(messageId),
                channel = if (quiet) NotificationChannels.MESSAGES_QUIET else NotificationChannels.MESSAGES,
                title = partner, body = "Sent you a message", deepLink = "risaina://app/messages",
                category = NotificationCompat.CATEGORY_MESSAGE, priority = if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT,
            )
            diagnostics.recordFastAlert(FAST_MESSAGE, messageId)
        }
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
        const val TEST_NOTIFICATION_ID = 4344
        const val WIDGET_NOTIFICATION_ID = 4345
        /** Low importance: no sound, no buzz, no heads-up. */
        const val WIDGET_CHANNEL = "widget_updates"

        /** One notification per chat message; the quick and the detailed post share it. */
        fun messageNotificationId(messageId: String): Int = messageId.hashCode()

        private val PARTNER_CHANNELS = setOf(
            NotificationChannels.PINGS, NotificationChannels.PINGS_QUIET,
            NotificationChannels.MESSAGES, NotificationChannels.MESSAGES_QUIET, WIDGET_CHANNEL,
        )
        private val PARTNER_IDS = setOf(PING_NOTIFICATION_ID, GAME_NOTIFICATION_ID, TEST_NOTIFICATION_ID, WIDGET_NOTIFICATION_ID)

        /**
         * Whether [clearAllPartnerNotifications] removes a showing notification: anything on a
         * partner channel or with a partner id, never the SOS alarm. Pure (NotifierPurgeTest).
         */
        fun clearedOnPurge(id: Int, channelId: String?): Boolean =
            id != SosAlert.ID && (channelId in PARTNER_CHANNELS || id in PARTNER_IDS)
        private const val FAST_PING = "ping"
        private const val FAST_MESSAGE = "message"

        /** "0.8 s", "12 s", "3 min". */
        fun formatLatency(ms: Long): String = when {
            ms < 10_000 -> "${"%.1f".format(java.util.Locale.ROOT, ms / 1000.0)} s"
            ms < 120_000 -> "${ms / 1000} s"
            else -> "${ms / 60_000} min"
        }
    }

    private fun ensureWidgetChannel() {
        val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(WIDGET_CHANNEL) != null) return
        nm.createNotificationChannel(
            android.app.NotificationChannel(WIDGET_CHANNEL, "Widget updates", android.app.NotificationManager.IMPORTANCE_LOW).apply {
                description = "A quiet note when your partner changes what your widgets show"
                enableVibration(false); setSound(null, null); setShowBadge(false)
            },
        )
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
