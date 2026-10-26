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
}
