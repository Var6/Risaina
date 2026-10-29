package com.risaina.app.core.notifications

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one chat notification: a burst is one notification, and reading or deleting takes lines away. */
class ConversationLinesTest {
    private fun text(id: String, at: Long, text: String = "hi $id", read: Boolean = false, deleted: Boolean = false) =
        ConversationItem(id = id, type = "text", text = text, atMillis = at, read = read, deleted = deleted)

    @Test fun twoUnreadMessagesAreTwoLinesInOneNotification() {
        val c = ConversationLines.build(listOf(text("m2", 2_000, "are you up?"), text("m1", 1_000, "hello")), emptyMap(), previews = true)
        assertEquals(listOf("hello", "are you up?"), c.lines.map { it.text }) // oldest first, as a chat reads
        assertEquals(2, c.total)
    }

    @Test fun aQuickAlertShowsAPlaceholderThatTheRealTextReplacesInPlace() {
        val beforeSync = ConversationLines.build(emptyList(), mapOf("m1" to 1_000L), previews = true)
        assertEquals(listOf(ConversationLine("m1", ConversationLines.PLACEHOLDER, 1_000L)), beforeSync.lines)

        // The sync brought it: same key, now with its text, and no placeholder left over.
        val afterSync = ConversationLines.build(listOf(text("m1", 1_100, "hello")), mapOf("m1" to 1_000L), previews = true)
        assertEquals(listOf(ConversationLine("m1", "hello", 1_100L)), afterSync.lines)
    }

    @Test fun aPlaceholderSitsBesideMessagesAlreadyHere() {
        val c = ConversationLines.build(listOf(text("m1", 1_000, "hello")), mapOf("m2" to 2_000L), previews = true)
        assertEquals(listOf("hello", ConversationLines.PLACEHOLDER), c.lines.map { it.text })
    }

    @Test fun previewsOffHidesEveryMessage() {
        val c = ConversationLines.build(
            listOf(text("m1", 1_000, "secret"), ConversationItem("m2", "photo", null, atMillis = 2_000)),
            emptyMap(), previews = false,
        )
        assertEquals(listOf("New message", "New message"), c.lines.map { it.text })
    }

    @Test fun mediaHaveTheirLabels() {
        val c = ConversationLines.build(
            listOf(
                ConversationItem("p1", "photo", null, viewOnce = true, atMillis = 1),
                ConversationItem("p2", "photo", null, atMillis = 2),
                ConversationItem("v1", "voice", null, atMillis = 3),
                ConversationItem("e1", "emoji", "🥺", atMillis = 4),
                // A type this build does not know reads as its text.
                ConversationItem("x1", "card", "Dinner on Friday", atMillis = 5),
                ConversationItem("x2", "card", null, atMillis = 6),
            ),
            emptyMap(), previews = true,
        )
        assertEquals(
            listOf("Sent you a view-once photo", "Sent you a photo", "Sent you a voice note", "🥺", "Dinner on Friday", "New message"),
            c.lines.map { it.text },
        )
    }

    @Test fun onceEverythingIsReadThereIsNothingLeftToShow() {
        val read = listOf(text("m1", 1_000, read = true), text("m2", 2_000, read = true))
        val c = ConversationLines.build(read, emptyMap(), previews = true)
        assertTrue(c.isEmpty) // the notifier then cancels MESSAGES_ID
        assertEquals(0, c.total)
    }

    @Test fun readingOneLeavesTheOthers() {
        val c = ConversationLines.build(listOf(text("m1", 1_000, read = true), text("m2", 2_000, "still new")), emptyMap(), previews = true)
        assertEquals(listOf("still new"), c.lines.map { it.text })
    }

    @Test fun aDeletedMessageLosesItsLineAndNeverComesBackAsAPlaceholder() {
        val c = ConversationLines.build(
            listOf(text("m1", 1_000, "oops", deleted = true), text("m2", 2_000, "hello")),
            mapOf("m1" to 900L), previews = true,
        )
        assertEquals(listOf("hello"), c.lines.map { it.text })
    }

    @Test fun onlyTheNewestSixAreShownButAllAreCounted() {
        val many = (1..9).map { text("m$it", it * 1_000L, "line $it") }
        val c = ConversationLines.build(many.shuffled(), emptyMap(), previews = true)
        assertEquals((4..9).map { "line $it" }, c.lines.map { it.text })
        assertEquals(9, c.total)
    }

    // --- leftovers from one notification per message -------------------------------------------

    @Test fun anOlderVersionsPerMessageNotificationIsALeftover() {
        assertTrue(ConversationLines.isLeftover("m1".hashCode(), NotificationChannels.MESSAGES, null))
        assertTrue(ConversationLines.isLeftover("m2".hashCode(), NotificationChannels.MESSAGES_QUIET, NotificationCompat.GROUP_KEY_SILENT))
    }

    @Test fun theConversationTheGameAndOtherChannelsAreNot() {
        assertFalse(ConversationLines.isLeftover(RisainaNotifier.MESSAGES_ID, NotificationChannels.MESSAGES, null))
        assertFalse(ConversationLines.isLeftover(RisainaNotifier.GAME_NOTIFICATION_ID, NotificationChannels.MESSAGES, null))
        assertFalse(ConversationLines.isLeftover(RisainaNotifier.PING_NOTIFICATION_ID, NotificationChannels.PINGS, null))
        // Anything with a group of its own is a notification of this version.
        assertFalse(ConversationLines.isLeftover(1234, NotificationChannels.MESSAGES, NotificationGroups.CHAT))
    }
}
