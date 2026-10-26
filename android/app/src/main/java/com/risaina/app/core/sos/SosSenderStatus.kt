package com.risaina.app.core.sos

import java.time.Duration
import java.time.Instant

/**
 * What the sender sees about their own SOS. Pure (unit-tested). "Sent" only means the server took
 * it; the partner's phone confirms it is ringing with a delivered receipt, and "seen" needs their
 * explicit "I'm on it". When neither comes, the sender is told to call, because a phone that is
 * switched off, force-stopped or offline can never ring.
 */
object SosSenderStatus {
    enum class Kind { SENT, RINGING, SEEN, NOT_REACHING, RESOLVED }

    /** No delivered receipt after this long: tell the sender to call. */
    val NOT_REACHING_AFTER: Duration = Duration.ofSeconds(30)
    /** Ringing but nobody said "I'm on it" after this long: offer Call and Text too. */
    val ESCALATE_AFTER: Duration = Duration.ofSeconds(120)
    /** "I'm safe now" stays on offer for this long after sending. */
    val SAFE_NOW_WINDOW: Duration = Duration.ofHours(2)

    data class View(val kind: Kind, val offerCall: Boolean)

    fun of(createdAt: String, deliveredAt: String?, acknowledgedAt: String?, resolvedAt: String?, now: Instant): View {
        if (resolvedAt != null) return View(Kind.RESOLVED, offerCall = false)
        if (acknowledgedAt != null) return View(Kind.SEEN, offerCall = false)
        val age = parse(createdAt)?.let { Duration.between(it, now) } ?: Duration.ZERO
        if (deliveredAt != null) return View(Kind.RINGING, offerCall = age >= ESCALATE_AFTER)
        return if (age >= NOT_REACHING_AFTER) View(Kind.NOT_REACHING, offerCall = true) else View(Kind.SENT, offerCall = false)
    }

    /** One line for the sender. Uses the partner's name: either of them can be the one in trouble. */
    fun line(kind: Kind, partnerName: String): String = when (kind) {
        Kind.SENT -> "Sent · waiting for $partnerName's phone"
        Kind.RINGING -> "Ringing on $partnerName's phone"
        Kind.SEEN -> "✓ $partnerName saw it and is on it"
        Kind.NOT_REACHING -> "Not reaching $partnerName's phone yet. Call $partnerName directly."
        Kind.RESOLVED -> "You said you're safe. $partnerName has been told."
    }

    /** Whether "I'm safe now" is still offered for this SOS. */
    fun canSaySafe(createdAt: String, resolvedAt: String?, now: Instant): Boolean {
        if (resolvedAt != null) return false
        val created = parse(createdAt) ?: return true
        return Duration.between(created, now) <= SAFE_NOW_WINDOW
    }

    private fun parse(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()
}
