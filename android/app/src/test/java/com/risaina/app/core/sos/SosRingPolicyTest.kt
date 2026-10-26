package com.risaina.app.core.sos

import com.risaina.app.core.sos.SosRingPolicy.EventDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class SosRingPolicyTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private fun ago(d: Duration) = now.minus(d).toString()

    @Test fun `window is six hours and fails open on unreadable times`() {
        assertTrue(SosRingPolicy.withinWindow(ago(Duration.ofHours(5)), now))
        assertTrue(SosRingPolicy.withinWindow(ago(Duration.ofHours(6)), now))
        assertFalse(SosRingPolicy.withinWindow(ago(Duration.ofHours(7)), now))
        assertTrue(SosRingPolicy.withinWindow(null, now))
        assertTrue(SosRingPolicy.withinWindow("not a date", now))
    }

    @Test fun `event rings only for the recipient of an open, recent SOS`() {
        fun decide(recipient: Boolean = true, server: Boolean = false, local: Boolean = false, created: String? = ago(Duration.ofSeconds(20))) =
            SosRingPolicy.forEvent(recipient, server, local, created, now)
        assertEquals(EventDecision.RING, decide())
        assertEquals(EventDecision.SKIP, decide(recipient = false))
        assertEquals(EventDecision.SKIP, decide(server = true))
        assertEquals(EventDecision.SKIP, decide(local = true))
        assertEquals(EventDecision.SKIP, decide(created = ago(Duration.ofHours(7))))
    }

    @Test fun `an older event in the window is double-checked, an unreadable one rings`() {
        assertEquals(EventDecision.VERIFY_THEN_RING, SosRingPolicy.forEvent(true, false, false, ago(Duration.ofMinutes(30)), now))
        assertEquals(EventDecision.VERIFY_THEN_RING, SosRingPolicy.forEvent(true, false, false, ago(Duration.ofHours(5)), now))
        assertEquals(EventDecision.RING, SosRingPolicy.forEvent(true, false, false, "garbage", now))
        assertEquals(EventDecision.RING, SosRingPolicy.forEvent(true, false, false, null, now))
    }

    @Test fun `push fast path never rings for something already applied or handled`() {
        val nowMs = now.toEpochMilli()
        // the outbox push (no SOS id): the sync already applied this seq
        assertFalse(SosRingPolicy.forPush(seq = 40, cursor = 40, sosId = null, handledLocally = false, createdAtMs = null, sentAtMs = nowMs, nowMs = nowMs))
        assertTrue(SosRingPolicy.forPush(seq = 41, cursor = 40, sosId = null, handledLocally = false, createdAtMs = null, sentAtMs = nowMs, nowMs = nowMs))
        // acknowledged offline, then re-pushed
        assertFalse(SosRingPolicy.forPush(seq = 41, cursor = 40, sosId = "s1", handledLocally = true, createdAtMs = nowMs, sentAtMs = nowMs, nowMs = nowMs))
        // a re-push carries the id: judged by the local state and its age, not the cursor
        assertTrue(SosRingPolicy.forPush(seq = 41, cursor = 99, sosId = "s1", handledLocally = false, createdAtMs = nowMs - 60_000, sentAtMs = nowMs, nowMs = nowMs))
        assertFalse(SosRingPolicy.forPush(seq = 41, cursor = 0, sosId = "s1", handledLocally = false, createdAtMs = nowMs - Duration.ofHours(7).toMillis(), sentAtMs = nowMs, nowMs = nowMs))
        // no SOS time: the push's own sent time decides
        assertFalse(SosRingPolicy.forPush(seq = 41, cursor = 0, sosId = null, handledLocally = false, createdAtMs = null, sentAtMs = nowMs - Duration.ofHours(7).toMillis(), nowMs = nowMs))
    }

    @Test fun `push fast path fails open when nothing is known`() {
        assertTrue(SosRingPolicy.forPush(seq = null, cursor = null, sosId = null, handledLocally = false, createdAtMs = null, sentAtMs = 0, nowMs = now.toEpochMilli()))
        assertTrue(SosRingPolicy.forPush(seq = 5, cursor = null, sosId = null, handledLocally = false, createdAtMs = null, sentAtMs = null, nowMs = now.toEpochMilli()))
    }

    @Test fun `a late retry says when it was really sent`() {
        val utc = ZoneId.of("UTC")
        val created = Instant.parse("2026-10-26T21:41:00Z")
        assertEquals("Please call", SosRingPolicy.delayedMessage("Please call", created, created.plusSeconds(60), utc))
        assertEquals("Please call (sent 9:41 pm, delayed)", SosRingPolicy.delayedMessage("Please call", created, created.plusSeconds(9 * 60), utc))
        assertEquals("(sent 9:41 pm, delayed)", SosRingPolicy.delayedMessage(null, created, created.plusSeconds(9 * 60), utc))
        val long = "x".repeat(140)
        val out = SosRingPolicy.delayedMessage(long, created, created.plusSeconds(600), utc)!!
        assertTrue(out.length <= 140)
        assertTrue(out.endsWith("(sent 9:41 pm, delayed)"))
    }

    @Test fun `locked text never shows the message`() {
        val secret = "at the station, please come"
        assertFalse(SosCopy.lockedText(panic = false, test = false).contains(secret))
        assertFalse(SosCopy.lockedText(panic = true, test = false).contains(secret))
        assertEquals(secret, SosCopy.text(secret, panic = false, test = false))
        assertEquals("Tap to see where they are", SosCopy.text(null, panic = true, test = false))
        assertEquals("🆘 Raina is in trouble", SosCopy.title("Raina", panic = true, test = false))
        assertEquals("🆘 SOS from Raina", SosCopy.title("Raina", panic = false, test = false))
        assertEquals("Test alarm", SosCopy.title("Raina", panic = true, test = true))
    }

    @Test fun `ring state matches by id or seq`() {
        val s = RingState(setOf("a"), setOf(7L), "Raina", null, false, false, 0, 1, false)
        assertTrue(s.matches("a", null))
        assertTrue(s.matches(null, 7L))
        assertFalse(s.matches("b", 8L))
        assertFalse(s.matches(null, null))
    }

    // --- one SOS of a merged ring is handled elsewhere --------------------------------------------

    private fun ring(ids: Set<String>, seqs: Set<Long>, test: Boolean = false) =
        RingState(ids, seqs, "Raina", null, panic = true, test = test, startedAtMs = 0, untilMs = 1, previews = false)

    @Test fun `the sender is safe from an older SOS, and a newer open one keeps ringing`() {
        // A (seq 10) is resolved; B (seq 11) is still open.
        val next = SosRingPolicy.withoutHandled(ring(setOf("A", "B"), setOf(10L, 11L)), "A", 10L) { id, seq ->
            id != "A" && seq != 10L
        }
        assertEquals(setOf("B"), next?.sosIds)
        assertEquals(setOf(11L), next?.seqs)
    }

    @Test fun `the last open SOS leaving the ring stops it`() {
        assertNull(SosRingPolicy.withoutHandled(ring(setOf("A"), setOf(10L)), "A", 10L) { _, _ -> true })
        // The other one is already closed too (acknowledged here meanwhile).
        assertNull(SosRingPolicy.withoutHandled(ring(setOf("A", "B"), emptySet()), "A", null) { _, _ -> false })
        assertNull(SosRingPolicy.withoutHandled(ring(emptySet(), emptySet(), test = true), null, null) { _, _ -> true })
    }

    @Test fun `a push known only by its seq still leaves when its id is resolved`() {
        // The ring knows seq 10 (from the push) and id B; A is seq 10's id, linked by the sync.
        val next = SosRingPolicy.withoutHandled(ring(setOf("B"), setOf(10L)), "A", 10L) { id, _ -> id == "B" }
        assertEquals(setOf("B"), next?.sosIds)
        assertTrue(next!!.seqs.isEmpty())
    }
}
