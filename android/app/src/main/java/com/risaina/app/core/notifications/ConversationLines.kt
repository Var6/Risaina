package com.risaina.app.core.notifications

import androidx.core.app.NotificationCompat

/** A partner message as the chat notification needs it. [type] is the wire type ("text", "photo", …). */
data class ConversationItem(
    val id: String,
    val type: String,
    val text: String?,
    val viewOnce: Boolean = false,
    val atMillis: Long,
    val read: Boolean = false,
    val deleted: Boolean = false,
)

/** One line of the chat notification: a message, or a placeholder for one its push announced. [key] is the message id. */
data class ConversationLine(val key: String, val text: String, val atMillis: Long)

/** The newest [lines], oldest first, and how many there are in all ([total], for the badge). */
data class Conversation(val lines: List<ConversationLine>, val total: Int) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * How the chat notification is posted. [onlyAlertOnce] keeps an update of the one in the shade quiet
 * (a heads-up then shows the new text). Android ignores it for a new post, so a post that may create
 * the notification without alerting is [muted] (NotificationGroups.alertBehavior).
 */
enum class ConversationPost(val onlyAlertOnce: Boolean, val muted: Boolean) {
    /** A new message: sound and buzz. */
    ALERT(onlyAlertOnce = false, muted = false),
    /** The one in the shade, changed in place. */
    UPDATE(onlyAlertOnce = true, muted = false),
    /** Rebuilt after a read or a delete when it may not be in the shade: it never alerts. */
    SILENT(onlyAlertOnce = true, muted = true),
}

/**
 * What the one chat notification (RisainaNotifier.MESSAGES_ID) says. Rebuilt from Room every time,
 * so reading or deleting a message takes its line away and a burst stays one notification. Pure
 * (ConversationLinesTest).
 */
object ConversationLines {
    const val MAX_LINES = 6
    /** The quick alert's line, before the sync brings the message: the push carries no content. */
    const val PLACEHOLDER = "Sent you a message"
    private const val HIDDEN = "New message"

    /**
     * [messages]: the partner's messages from Room. Only the unread, undeleted ones get a line.
     * [quickRefs]: message id → when its quick alert was posted; one Room does not have yet shows as
     * [PLACEHOLDER] until the sync brings it, under the same key, so the real text takes its place.
     * [previews] off: every message reads "New message", as before.
     */
    fun build(messages: List<ConversationItem>, quickRefs: Map<String, Long>, previews: Boolean): Conversation {
        val known = messages.mapTo(HashSet()) { it.id }
        val unread = messages.filter { !it.read && !it.deleted }.map { ConversationLine(it.id, text(it, previews), it.atMillis) }
        val waiting = quickRefs.filterKeys { it !in known }.map { (id, at) -> ConversationLine(id, PLACEHOLDER, at) }
        val all = (unread + waiting).sortedBy { it.atMillis }
        return Conversation(all.takeLast(MAX_LINES), all.size)
    }

    fun text(m: ConversationItem, previews: Boolean): String = when {
        !previews -> HIDDEN
        m.type == "photo" && m.viewOnce -> "Sent you a view-once photo"
        m.type == "photo" -> "Sent you a photo"
        m.type == "voice" -> "Sent you a voice note"
        else -> m.text?.takeIf { it.isNotBlank() } ?: HIDDEN
    }

    /**
     * What a refresh after a read or a delete does with a conversation that still has lines, or null
     * to post nothing. [showing]: it is in the shade (null when Android will not say). [leftovers]: old
     * per-message notifications just removed, which it stands in for. One the person swiped away is
     * not brought back. When it may be a new post it is SILENT: the first refresh after an upgrade
     * usually runs as the person opens the chat, and must not sound or pop up there.
     */
    fun refreshPost(showing: Boolean?, leftovers: Int, mayPost: Boolean): ConversationPost? = when {
        !mayPost -> null
        showing == true -> ConversationPost.UPDATE
        showing == null || leftovers > 0 -> ConversationPost.SILENT
        else -> null
    }

    private val MESSAGE_CHANNELS = setOf(NotificationChannels.MESSAGES, NotificationChannels.MESSAGES_QUIET)

    /**
     * A chat notification from an older version, which posted one per message under the message id's
     * hash, with no group (or AndroidX's "silent" one): it would sit next to the conversation, so it
     * is removed whenever the conversation is posted or refreshed. The game notification shares the
     * message channels and is not one.
     */
    fun isLeftover(id: Int, channelId: String?, group: String?): Boolean =
        channelId in MESSAGE_CHANNELS &&
            id != RisainaNotifier.MESSAGES_ID && id != RisainaNotifier.GAME_NOTIFICATION_ID &&
            (group == null || group == NotificationCompat.GROUP_KEY_SILENT)
}
