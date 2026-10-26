package com.risaina.app.core.sync

import com.risaina.app.core.model.Ping
import com.risaina.app.core.model.PingStatus
import com.risaina.app.core.model.PingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReplayGuardTest {
    private val ping = Incoming.NewPing(
        Ping(
            id = "p1", localKey = "k1", coupleId = "c1", senderId = "her", recipientId = "me", type = PingType.HUG, emoji = "🌹", message = "gift:rose",
            status = PingStatus.ACCEPTED, createdAt = Instant.parse("2026-10-01T08:00:00Z"), deliveredAt = null, seenAt = null,
        ),
    )
    private val game = Incoming.Game("g1", "moved")

    @Test fun historyUpToTheMarkIsAReplay() {
        assertTrue(ReplayGuard.isReplay(seq = 120, bootstrapUntilSeq = 120))
        assertTrue(ReplayGuard.isReplay(seq = 1, bootstrapUntilSeq = 120))
        assertFalse(ReplayGuard.isReplay(seq = 121, bootstrapUntilSeq = 120))
        assertFalse("no mark yet: nothing is a replay", ReplayGuard.isReplay(seq = 1, bootstrapUntilSeq = 0))
    }

    /** The socket path and the sync path both pass replayed events through this: no reveal, no buzz, no notification. */
    @Test fun replayedPingsGiftsMessagesAndGamesAreNotAnnounced() {
        val out = ReplayGuard.announceable(listOf(ping, game), seq = 40, bootstrapUntilSeq = 120)
        assertTrue(out.isEmpty())
    }

    @Test fun eventsAfterTheMarkAreAnnounced() {
        val out = ReplayGuard.announceable(listOf(ping, game), seq = 121, bootstrapUntilSeq = 120)
        assertEquals(listOf(ping, game), out)
    }

    @Test fun beyondTenPagesTheMarkStillHolds() {
        // A history of 2,500 events: the first sync stops after 2,000; the next starts at since>0,
        // but the persisted mark (2,500) keeps the rest from being announced.
        assertTrue(ReplayGuard.announceable(listOf(ping), seq = 2_300, bootstrapUntilSeq = 2_500).isEmpty())
    }

    @Test fun unlinkWidgetChangeAndSosAreKeptEvenInAReplay() {
        val keep = listOf(Incoming.Unlinked, Incoming.WidgetChanged, Incoming.Sos("s1"))
        assertEquals(keep, ReplayGuard.announceable(keep + ping, seq = 5, bootstrapUntilSeq = 120))
    }
}
