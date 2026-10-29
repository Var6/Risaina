package com.risaina.app.core.notifications

import com.risaina.app.core.datastore.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockCardContentTest {
    private val base = WidgetSnapshot(
        partnerName = "Raina", paired = true, latestEmoji = "🥺", latestEmojiLabel = "Missing you",
        boardEmoji = "🥺", boardLine = "Missing you", boardAtMillis = 900L,
    )
    private val seenGift = base.copy(latestGiftEmoji = "🌹", latestGiftLine = "A rose for you", latestGiftAtMillis = 1_000L, giftUnseen = false)
    private val pinged = seenGift.copy(pingUnseen = true, latestPingEmoji = "🫂", latestPingLine = "Hug", latestPingAtMillis = 5_000L)

    @Test fun seenGiftIsNeverAnnouncedAgain() {
        val c = LockCardContent.from(seenGift.copy(boardLine = "Can't wait for Goa"))
        assertFalse(c.title.contains("gift", ignoreCase = true))
        assertEquals("Can't wait for Goa", c.line)
        assertNull("the calm status carries no time, so it never looks new", c.eventAtMillis)
    }

    @Test fun unseenGiftIsAnnouncedWithItsOwnTime() {
        val c = LockCardContent.from(seenGift.copy(giftUnseen = true))
        assertTrue(c.title.contains("A gift from Raina"))
        assertEquals("A rose for you", c.line)
        assertEquals(1_000L, c.eventAtMillis)
        assertEquals(1_000L, c.whenMillis)
    }

    @Test fun unseenPingAfterSeenGiftThenCalmOnceSeen() {
        assertTrue(LockCardContent.from(pinged).title.contains("reached out"))
        val afterOpening = pinged.copy(pingUnseen = false)
        assertFalse(LockCardContent.from(afterOpening).title.contains("gift", ignoreCase = true))
        assertFalse(LockCardContent.from(afterOpening).title.contains("reached out"))
    }

    @Test fun sameSnapshotGivesSameKeySoNothingIsRePosted() {
        assertEquals(LockCardContent.from(seenGift).key, LockCardContent.from(seenGift.copy()).key)
    }

    // --- never a second notification for one item -----------------------------------------------

    @Test fun anUnseenPingWhoseNotificationIsShowingGivesTheCalmCard() {
        val c = LockCardContent.from(pinged, ShowingAlerts(ping = true))
        assertFalse(c.title, c.title.contains("reached out"))
        assertFalse(c.line, c.line.contains("send one back"))
        assertNull(c.eventAtMillis)
        // The card says what it said before the ping came, so it is not posted again at all.
        assertEquals(LockCardContent.from(seenGift).key, c.key)
    }

    @Test fun anUnseenGiftWhoseNotificationIsShowingGivesTheCalmCard() {
        val c = LockCardContent.from(seenGift.copy(giftUnseen = true), ShowingAlerts(ping = true))
        assertFalse(c.title, c.title.contains("gift", ignoreCase = true))
        assertFalse(c.line, c.line.contains("rose", ignoreCase = true))
    }

    @Test fun withNoPingNotificationShowingTheCardStillAnnounces() {
        // Notifications off, the Touch Pings channel blocked, or the notification swiped away.
        assertTrue(LockCardContent.from(pinged, ShowingAlerts(ping = false)).title.contains("reached out"))
        assertTrue(LockCardContent.from(seenGift.copy(giftUnseen = true), ShowingAlerts()).title.contains("A gift from Raina"))
        // Another kind of notification showing does not count as the ping's.
        assertTrue(LockCardContent.from(pinged, ShowingAlerts(widget = true, message = true)).title.contains("reached out"))
    }

    @Test fun aNewChatMessageLeavesTheCardAsItIs() {
        // The widgets' latestNoteText follows the newest chat message; the card does not.
        val before = LockCardContent.from(base)
        val afterMessage = base.copy(latestNoteText = "hello", latestNoteAtMillis = 9_000L, latestNoteFromPartner = true, latestAtMillis = 9_000L, latestKind = "note")
        val c = LockCardContent.from(afterMessage, ShowingAlerts(message = true))
        assertFalse(c.line, c.line.contains("hello"))
        assertEquals(before.key, c.key)
        assertFalse(LockCardContent.shouldPost(c.key, postedKey = before.key, dismissedKey = null, showing = true))
    }

    @Test fun aNewBoardNoteUpdatesTheCalmLineButIsNeverAnAnnouncement() {
        // "Raina updated your widget" (4345) is showing: the card still follows the board it owns.
        val s = base.copy(boardLine = "Goa in 3 days", boardAtMillis = 12_000L)
        val c = LockCardContent.from(s, ShowingAlerts(widget = true))
        assertEquals("Goa in 3 days", c.line)
        assertNull(c.eventAtMillis)
        assertEquals(12_000L, c.whenMillis)
        assertTrue(LockCardContent.shouldPost(c.key, postedKey = LockCardContent.from(base).key, dismissedKey = null, showing = true))
    }

    /**
     * The partner's newest ping is a gift and there is no note: the snapshot's emoji and its label
     * are the gift's. The calm card must not show the gift emoji, its line or the word "gift".
     */
    @Test fun calmCardNeverLeaksAGiftThroughTheEmojiFields() {
        for (label in listOf("A rose for you", "A gift")) {
            val s = WidgetSnapshot(
                partnerName = "Raina", paired = true, giftUnseen = false, pingUnseen = false, latestNoteText = null,
                latestEmoji = "🌹", latestEmojiLabel = label, latestEmojiFromGift = true,
                latestGiftEmoji = "🌹", latestGiftLine = "A rose for you", latestGiftAtMillis = 1_000L, latestAtMillis = 1_000L,
            )
            val c = LockCardContent.from(s)
            assertFalse(c.title, c.title.contains("🌹"))
            assertFalse(c.line, c.line.contains("rose", ignoreCase = true))
            assertFalse(c.line, c.line.contains("gift", ignoreCase = true))
            assertEquals("Raina", c.title)
            assertEquals("Always a little closer", c.line)
        }
    }

    @Test fun calmCardShowsTheBoardEmojiAndMoodButNeverAPingsEmoji() {
        val c = LockCardContent.from(base)
        assertEquals("Raina  🥺", c.title)
        assertEquals("Missing you", c.line)
        // A ping set the widgets' emoji and label; the card keeps the board's.
        val fromPing = LockCardContent.from(base.copy(latestEmoji = "🫂", latestEmojiLabel = "Hug", boardEmoji = null, boardLine = null))
        assertEquals("Raina", fromPing.title)
        assertEquals("Always a little closer", fromPing.line)
    }

    @Test fun calmCardSortsByTheBoardTimeNotNow() {
        val c = LockCardContent.from(base.copy(boardAtMillis = 42_000L, latestAtMillis = 99_000L))
        assertNull(c.eventAtMillis)
        assertEquals(42_000L, c.whenMillis)
    }

    // --- when to (re)post ---------------------------------------------------------------------

    @Test fun rebootOrForceStopBringsTheCardBack() {
        // Posted before, gone now, and the person never swiped it (no deleteIntent).
        assertTrue(LockCardContent.shouldPost(key = "k", postedKey = "k", dismissedKey = null, showing = false))
    }

    @Test fun aSwipedCardStaysAwayUntilThereIsSomethingNew() {
        assertFalse(LockCardContent.shouldPost(key = "k", postedKey = "k", dismissedKey = "k", showing = false))
        assertTrue(LockCardContent.shouldPost(key = "new", postedKey = "k", dismissedKey = "k", showing = false))
    }

    @Test fun sameContentStillShowingIsNotRePosted() {
        assertFalse(LockCardContent.shouldPost(key = "k", postedKey = "k", dismissedKey = null, showing = true))
    }

    @Test fun newContentIsPosted() {
        assertTrue(LockCardContent.shouldPost(key = "new", postedKey = "k", dismissedKey = null, showing = true))
        assertTrue(LockCardContent.shouldPost(key = "k", postedKey = null, dismissedKey = null, showing = false))
    }
}
