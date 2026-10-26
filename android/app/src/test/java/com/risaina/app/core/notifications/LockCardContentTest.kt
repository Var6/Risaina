package com.risaina.app.core.notifications

import com.risaina.app.core.datastore.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockCardContentTest {
    private val base = WidgetSnapshot(partnerName = "Raina", paired = true, latestEmoji = "🥺", latestEmojiLabel = "Missing you")
    private val seenGift = base.copy(latestGiftEmoji = "🌹", latestGiftLine = "A rose for you", latestGiftAtMillis = 1_000L, giftUnseen = false)

    @Test fun seenGiftIsNeverAnnouncedAgain() {
        val c = LockCardContent.from(seenGift.copy(latestNoteText = "Can't wait for Goa"))
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
        val pinged = seenGift.copy(pingUnseen = true, latestPingEmoji = "🫂", latestPingLine = "Hug", latestPingAtMillis = 5_000L)
        assertTrue(LockCardContent.from(pinged).title.contains("reached out"))
        val afterOpening = pinged.copy(pingUnseen = false)
        assertFalse(LockCardContent.from(afterOpening).title.contains("gift", ignoreCase = true))
        assertFalse(LockCardContent.from(afterOpening).title.contains("reached out"))
    }

    @Test fun sameSnapshotGivesSameKeySoNothingIsRePosted() {
        assertEquals(LockCardContent.from(seenGift).key, LockCardContent.from(seenGift.copy()).key)
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

    @Test fun calmCardKeepsANonGiftEmoji() {
        val c = LockCardContent.from(base.copy(latestEmojiFromGift = false))
        assertEquals("Raina  🥺", c.title)
        assertEquals("Missing you", c.line)
    }

    @Test fun calmCardSortsByTheNewestItemNotNow() {
        val c = LockCardContent.from(base.copy(latestAtMillis = 42_000L))
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
