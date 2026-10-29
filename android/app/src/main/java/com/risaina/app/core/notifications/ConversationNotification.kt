package com.risaina.app.core.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.risaina.app.R
import com.risaina.app.core.database.MessageDao
import com.risaina.app.core.database.MessageEntity
import com.risaina.app.core.datastore.SessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one chat notification: a conversation of the partner's unread messages, rebuilt from Room
 * every time (ConversationLines). It replaced one notification per message, which nothing removed
 * when the message was read, so a new message sat next to old ones already read and a quick burst
 * showed as two or three notifications.
 *
 * Only RisainaNotifier calls this, under its postLock: the quick alert from a push and the detailed
 * post from the sync must not interleave.
 */
@Singleton
class ConversationNotification @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val sessionStore: SessionStore,
    private val diagnostics: DeliveryDiagnostics,
) {
    /** What it says now: the partner's unread messages, plus a placeholder per quick alert whose message is not here yet. */
    suspend fun build(previews: Boolean, now: Long = System.currentTimeMillis()): Conversation {
        val me = sessionStore.current()?.userId ?: return Conversation(emptyList(), 0)
        val items = messageDao.recent(RECENT).first().filter { it.senderId != me }.map { it.toItem() }
        // byId, not the list above: that one leaves deleted messages out, and a message the partner
        // took back must not come back as "Sent you a message".
        val waiting = diagnostics.quickMessages(now).filterKeys { messageDao.byId(it) == null }
        return ConversationLines.build(items, waiting, previews)
    }

    /** Whether it is in the shade. True when Android will not say (see FastAlerts.detailedAction). */
    fun showing(): Boolean = active()?.let { list -> list.any { it.id == ID } } ?: true

    /**
     * Posts [c]: alerting for a new message when [alert], else updated in place without a sound
     * (onlyAlertOnce; never setSilent, see NotificationGroups). Removed when there is nothing unread.
     */
    fun show(c: Conversation, partner: String, quiet: Boolean, alert: Boolean) {
        cancelLeftovers(active())
        if (c.isEmpty) cancel() else post(c, partner, quiet, alert)
    }

    /**
     * After a read or a delete: rebuilt from what is still unread, and removed when nothing is. Never
     * alerts, and never brings back a conversation the person swiped away (only one that is showing
     * is updated). [mayPost] is false while notifications are off: it can then only be removed.
     */
    fun refresh(c: Conversation, partner: String, quiet: Boolean, mayPost: Boolean) {
        val active = active()
        val leftovers = cancelLeftovers(active)
        if (c.isEmpty) { cancel(); return }
        // Old per-message notifications just removed: the conversation stands in for them.
        val showing = active == null || active.any { it.id == ID } || leftovers > 0
        if (showing && mayPost) post(c, partner, quiet, alert = false)
    }

    fun cancel() = NotificationManagerCompat.from(context).cancel(ID)

    private fun active(): List<StatusBarNotification>? =
        runCatching { context.getSystemService(NotificationManager::class.java)!!.activeNotifications.toList() }.getOrNull()

    /** Removes the per-message notifications an older version posted (ConversationLines.isLeftover). Returns how many. */
    private fun cancelLeftovers(active: List<StatusBarNotification>?): Int {
        val leftovers = active.orEmpty().filter { ConversationLines.isLeftover(it.id, it.notification.channelId, it.notification.group) }
        val nm = NotificationManagerCompat.from(context)
        leftovers.forEach { nm.cancel(it.tag, it.id) }
        return leftovers.size
    }

    private fun post(c: Conversation, partner: String, quiet: Boolean, alert: Boolean) {
        val channel = if (quiet) NotificationChannels.MESSAGES_QUIET else NotificationChannels.MESSAGES
        val them = Person.Builder().setName(partner).setKey(PARTNER_KEY).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").setKey(ME_KEY).build())
        c.lines.forEach { style.addMessage(NotificationCompat.MessagingStyle.Message(it.text, it.atMillis, them)) }
        val newest = c.lines.last()
        val open = PendingIntent.getActivity(
            context, ID,
            Intent(Intent.ACTION_VIEW, Uri.parse("risaina://app/messages")).setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(partner)
            .setContentText(newest.text)
            .setStyle(style)
            .setNumber(c.total)
            // The newest message's time, not "now": a silent rebuild after a read must not lift it.
            .setWhen(newest.atMillis).setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setGroup(NotificationGroups.CHAT)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Risaina").setContentText("New activity").build(),
            )
            .setColor(ContextCompat.getColor(context, R.color.rosewood))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID, n)
        } catch (_: SecurityException) {
            // Permission revoked between check and post; nothing to do.
        }
    }

    private fun MessageEntity.toItem() = ConversationItem(
        id = id, type = type, text = text, viewOnce = viewOnce, atMillis = createdAt,
        read = readAt != null, deleted = deletedAt != null,
    )

    private companion object {
        const val ID = RisainaNotifier.MESSAGES_ID
        /** Unread messages are the newest ones; only [ConversationLines.MAX_LINES] of them are shown. */
        const val RECENT = 100
        const val PARTNER_KEY = "partner"
        const val ME_KEY = "me"
    }
}
