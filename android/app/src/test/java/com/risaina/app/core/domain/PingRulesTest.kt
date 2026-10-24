package com.risaina.app.core.domain

import com.risaina.app.core.model.PingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PingRulesTest {
    @Test fun `legal transitions`() {
        assertTrue(PingRules.canTransition(PingStatus.PENDING, PingStatus.ACCEPTED))
        assertTrue(PingRules.canTransition(PingStatus.PENDING, PingStatus.FAILED))
        assertTrue(PingRules.canTransition(PingStatus.ACCEPTED, PingStatus.DELIVERED))
        assertTrue(PingRules.canTransition(PingStatus.ACCEPTED, PingStatus.SEEN))
        assertTrue(PingRules.canTransition(PingStatus.DELIVERED, PingStatus.SEEN))
        assertTrue(PingRules.canTransition(PingStatus.FAILED, PingStatus.PENDING))
    }

    @Test fun `illegal transitions never move backwards or skip to delivered from pending`() {
        assertFalse(PingRules.canTransition(PingStatus.PENDING, PingStatus.DELIVERED))
        assertFalse(PingRules.canTransition(PingStatus.PENDING, PingStatus.SEEN))
        assertFalse(PingRules.canTransition(PingStatus.SEEN, PingStatus.DELIVERED))
        assertFalse(PingRules.canTransition(PingStatus.DELIVERED, PingStatus.ACCEPTED))
        assertFalse(PingRules.canTransition(PingStatus.SEEN, PingStatus.PENDING))
        assertFalse(PingRules.canTransition(PingStatus.EXPIRED, PingStatus.ACCEPTED))
    }

    @Test fun `cooldown remaining`() {
        val now = Instant.parse("2026-10-25T10:00:30Z")
        assertEquals(0, PingRules.cooldownRemaining(null, 15, now))
        assertEquals(5, PingRules.cooldownRemaining(Instant.parse("2026-10-25T10:00:20Z"), 15, now))
        assertEquals(0, PingRules.cooldownRemaining(Instant.parse("2026-10-25T10:00:00Z"), 15, now))
    }

    @Test fun `message length validation`() {
        assertNull(PingRules.validateMessage(null))
        assertNull(PingRules.validateMessage("a".repeat(140)))
        assertNotNull(PingRules.validateMessage("a".repeat(141)))
    }

    @Test fun `emoji validation counts grapheme clusters`() {
        assertNull(PingRules.validateEmoji("❤️"))
        assertNull(PingRules.validateEmoji("👩‍❤️‍👨"))   // ZWJ sequence is one cluster
        assertNull(PingRules.validateEmoji("🌙✨"))
        assertNotNull(PingRules.validateEmoji(""))
        assertNotNull(PingRules.validateEmoji("abc"))
        assertNull(PingRules.validateEmoji("🇮🇳"))       // flag = two regional indicators = one cluster
        assertNull(PingRules.validateEmoji("👍🏽"))       // skin tone modifier attaches
        assertNull(PingRules.validateEmoji("🏳️‍🌈"))    // ZWJ + variation selector sequence
        assertEquals(3, PingRules.graphemeCount("🌙✨❤️"))
        assertNotNull(PingRules.validateEmoji("🌙✨❤️"))
    }
}
