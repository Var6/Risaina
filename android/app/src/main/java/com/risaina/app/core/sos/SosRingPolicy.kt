package com.risaina.app.core.sos

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * When an SOS may ring. Pure (unit-tested). The guiding rule: a missed emergency is worse than a
 * false alarm, so every doubt (an unreadable time, no network to double-check) rings; only clear
 * evidence that the SOS is old or already handled keeps the phone quiet.
 */
object SosRingPolicy {
    /** Older than this, a replayed or late SOS is history, not an emergency. */
    val RING_WINDOW: Duration = Duration.ofHours(6)
    /** An SOS created longer ago than this is double-checked with the server before ringing. */
    val VERIFY_AFTER: Duration = Duration.ofMinutes(2)
    const val RING_MS = 5 * 60_000L
    const val TEST_RING_MS = 5_000L

    /** Fails open: an unparseable time counts as recent. */
    fun withinWindow(createdAtIso: String?, now: Instant): Boolean {
        val created = parse(createdAtIso) ?: return true
        return Duration.between(created, now) <= RING_WINDOW
    }

    enum class EventDecision { SKIP, RING, VERIFY_THEN_RING }

    /**
     * For a `sos.raised` event applied by the sync. The event payload is a snapshot taken at creation,
     * so its acknowledgedAt is always null; `handledLocally` (acknowledged, resolved, or already rang on
     * this phone) is what really stops a replay from ringing.
     */
    fun forEvent(
        isRecipient: Boolean,
        ackedOrResolvedOnServer: Boolean,
        handledLocally: Boolean,
        createdAtIso: String?,
        now: Instant,
    ): EventDecision {
        if (!isRecipient || ackedOrResolvedOnServer || handledLocally) return EventDecision.SKIP
        if (!withinWindow(createdAtIso, now)) return EventDecision.SKIP
        val created = parse(createdAtIso) ?: return EventDecision.RING
        return if (Duration.between(created, now) > VERIFY_AFTER) EventDecision.VERIFY_THEN_RING else EventDecision.RING
    }

    /**
     * For the FCM fast path, which must decide with no network. `seq <= cursor` on the outbox's push
     * means the sync already applied this event (and rang or decided not to). A re-push carries the
     * SOS id and its creation time, so it is judged by those instead.
     */
    fun forPush(
        seq: Long?,
        cursor: Long?,
        sosId: String?,
        handledLocally: Boolean,
        createdAtMs: Long?,
        sentAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (handledLocally) return false
        val origin = (createdAtMs ?: sentAtMs)?.takeIf { it > 0 }
        if (origin != null && nowMs - origin > RING_WINDOW.toMillis()) return false
        if (sosId == null && seq != null && cursor != null && seq <= cursor) return false
        return true
    }

    /**
     * A retried SOS that finally goes out late says so, so the partner does not think it is happening
     * right now: "Please call (sent 9:41 pm, delayed)". Stays within the 140-character limit.
     */
    fun delayedMessage(message: String?, createdAt: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (Duration.between(createdAt, now) < Duration.ofMinutes(2)) return message
        val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(zone).format(createdAt).lowercase(Locale.US)
        val note = "(sent $time, delayed)"
        val base = message?.trim().orEmpty()
        if (base.isEmpty()) return note
        return base.take(140 - note.length - 1).trimEnd() + " " + note
    }

    /**
     * One SOS of a ring was acknowledged on another device, or its sender said they are safe. Several
     * SOS can ring together (a second one joins the first), so only that one leaves the ring: returns
     * the ring without it, or null when nothing still open is left and the ring should stop.
     * [sosId] and [seq] name the handled SOS (with its other key when known); [isOpen] says whether a
     * remaining id or seq is still neither acknowledged nor resolved.
     */
    fun withoutHandled(
        state: RingState,
        sosId: String?,
        seq: Long?,
        isOpen: (sosId: String?, seq: Long?) -> Boolean,
    ): RingState? {
        val ids = state.sosIds - setOfNotNull(sosId)
        val seqs = state.seqs - setOfNotNull(seq)
        if (state.test) return null
        val open = ids.any { isOpen(it, null) } || seqs.any { isOpen(null, it) }
        return if (open) state.copy(sosIds = ids, seqs = seqs) else null
    }

    private fun parse(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

/** Words shown for a ringing SOS. Pure, so the lock-screen privacy rule is unit-tested. */
object SosCopy {
    fun title(partnerName: String, panic: Boolean, test: Boolean): String = when {
        test -> "Test alarm"
        panic -> "🆘 $partnerName is in trouble"
        else -> "🆘 SOS from $partnerName"
    }

    /** With previews allowed: the sender's own words, or a plain prompt. */
    fun text(message: String?, panic: Boolean, test: Boolean): String = when {
        test -> "This is how an SOS from your partner will ring."
        !message.isNullOrBlank() -> message.trim()
        panic -> "Tap to see where they are"
        else -> "Please check on them now"
    }

    /** On a locked phone with previews off: never the message, only that something happened. */
    fun lockedText(panic: Boolean, test: Boolean): String = when {
        test -> "This is how an SOS from your partner will ring."
        panic -> "They need help. Unlock to see more."
        else -> "They sent an SOS. Unlock to see more."
    }
}
