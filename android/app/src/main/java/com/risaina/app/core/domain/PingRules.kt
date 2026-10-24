package com.risaina.app.core.domain

import com.risaina.app.core.model.PingStatus
import java.time.Duration
import java.time.Instant

/**
 * Sender-side rules for Touch Ping: legal state transitions and the local cooldown. The server
 * is the authority; these rules only prevent the client from presenting impossible states.
 */
object PingRules {
    /** Which transitions are legal. Terminal states cannot move backwards. */
    fun canTransition(from: PingStatus, to: PingStatus): Boolean = when (from) {
        PingStatus.PENDING -> to == PingStatus.ACCEPTED || to == PingStatus.FAILED
        PingStatus.ACCEPTED -> to == PingStatus.DELIVERED || to == PingStatus.SEEN || to == PingStatus.EXPIRED
        PingStatus.DELIVERED -> to == PingStatus.SEEN
        PingStatus.SEEN -> false
        PingStatus.FAILED -> to == PingStatus.PENDING // retry
        PingStatus.EXPIRED -> false
    }

    /** Seconds the sender still has to wait, or 0 if they may send now. */
    fun cooldownRemaining(lastSentAt: Instant?, cooldownSeconds: Int, now: Instant): Long {
        if (lastSentAt == null) return 0
        val elapsed = Duration.between(lastSentAt, now).seconds
        return (cooldownSeconds - elapsed).coerceAtLeast(0)
    }

    const val MAX_MESSAGE_LENGTH = 140

    fun validateMessage(message: String?): String? {
        if (message == null) return null
        if (message.length > MAX_MESSAGE_LENGTH) return "Keep it under $MAX_MESSAGE_LENGTH characters"
        return null
    }

    fun validateEmoji(emoji: String): String? {
        if (emoji.isBlank()) return "Pick an emoji"
        // Count grapheme clusters generously: codepoints joined by ZWJ/variation selectors count as one.
        val clusters = graphemeCount(emoji)
        if (clusters > 2) return "Use one or two emoji"
        return null
    }

    /**
     * Counts user-perceived emoji clusters without relying on platform BreakIterator behaviour
     * (JVM and Android ICU disagree on ZWJ sequences). Joiners, variation selectors, skin-tone and
     * hair modifiers, combining marks, tag characters and the second half of a flag all attach to
     * the previous cluster.
     */
    internal fun graphemeCount(s: String): Int {
        var count = 0
        var prevWasZwj = false
        var prevWasRegionalIndicator = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val isZwj = cp == 0x200D
            val isRegional = cp in 0x1F1E6..0x1F1FF
            val attaches = isZwj || prevWasZwj ||
                cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0x1F9B0..0x1F9B3 ||
                cp in 0xE0020..0xE007F || cp == 0x20E3 ||
                Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(cp) == Character.ENCLOSING_MARK.toInt() ||
                (isRegional && prevWasRegionalIndicator)
            if (!attaches) count++
            prevWasZwj = isZwj
            prevWasRegionalIndicator = isRegional && !(isRegional && prevWasRegionalIndicator)
        }
        return count
    }
}
