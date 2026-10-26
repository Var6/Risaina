package com.risaina.app.feature.safety

import com.risaina.app.core.network.SosDto
import com.risaina.app.core.safety.SosSender
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PanicPanelModeTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")

    private fun sos(id: String, age: Duration, resolvedAt: String? = null) = SosDto(
        id = id, coupleId = "c", senderId = "me", recipientId = "her",
        createdAt = now.minus(age).toString(), panic = true, resolvedAt = resolvedAt,
    )

    private fun sent(id: String) = SosSender.Attempt.Sent(id, panic = true, sharing = true)

    @Test fun `a fresh alert shows what happened to it`() {
        val s = sos("a", Duration.ofMinutes(3))
        assertEquals(PanicPanelMode.Sent(s), PanicPanelMode.of(sent("a"), listOf(s), now))
    }

    @Test fun `an alert the sender already called off gives the hold button back`() {
        val s = sos("a", Duration.ofMinutes(20), resolvedAt = now.minusSeconds(60).toString())
        assertEquals(PanicPanelMode.Form(sending = false), PanicPanelMode.of(sent("a"), listOf(s), now))
    }

    @Test fun `an alert from hours ago never stands in for the hold button`() {
        val s = sos("a", Duration.ofHours(5))
        assertEquals(PanicPanelMode.Form(sending = false), PanicPanelMode.of(sent("a"), listOf(s), now))
    }

    @Test fun `an alert that is no longer listed (signed out) gives the form`() {
        assertEquals(PanicPanelMode.Form(sending = false), PanicPanelMode.of(sent("a"), emptyList(), now))
    }

    @Test fun `sending keeps the form, disabled`() {
        assertEquals(PanicPanelMode.Form(sending = true), PanicPanelMode.of(SosSender.Attempt.Sending(panic = true), emptyList(), now))
    }

    @Test fun `not sent keeps its card until it is dismissed`() {
        val a = SosSender.Attempt.NotSent("No internet.", "I'm in trouble", retrying = true, sharing = false, panic = true, key = "k")
        assertEquals(PanicPanelMode.NotSent(a), PanicPanelMode.of(a, emptyList(), now))
    }

    @Test fun `idle is the form`() {
        assertEquals(PanicPanelMode.Form(sending = false), PanicPanelMode.of(SosSender.Attempt.Idle, listOf(sos("a", Duration.ofMinutes(1))), now))
    }
}
