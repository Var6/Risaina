package com.risaina.app.core.sos

import com.risaina.app.core.sos.SosSenderStatus.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SosSenderStatusTest {
    private val created = Instant.parse("2026-10-26T12:00:00Z")
    private val iso = created.toString()
    private fun at(seconds: Long) = created.plusSeconds(seconds)

    @Test fun `sent, then not reaching after 30 seconds without a receipt`() {
        assertEquals(SosSenderStatus.View(Kind.SENT, false), SosSenderStatus.of(iso, null, null, null, at(10)))
        assertEquals(SosSenderStatus.View(Kind.NOT_REACHING, true), SosSenderStatus.of(iso, null, null, null, at(30)))
    }

    @Test fun `ringing, with call offered after two minutes without I'm on it`() {
        val delivered = at(3).toString()
        assertEquals(SosSenderStatus.View(Kind.RINGING, false), SosSenderStatus.of(iso, delivered, null, null, at(60)))
        assertEquals(SosSenderStatus.View(Kind.RINGING, true), SosSenderStatus.of(iso, delivered, null, null, at(120)))
    }

    @Test fun `seen and resolved win over everything else`() {
        assertEquals(Kind.SEEN, SosSenderStatus.of(iso, null, at(5).toString(), null, at(500)).kind)
        assertEquals(Kind.RESOLVED, SosSenderStatus.of(iso, at(1).toString(), at(5).toString(), at(9).toString(), at(500)).kind)
        assertFalse(SosSenderStatus.of(iso, null, at(5).toString(), null, at(500)).offerCall)
    }

    @Test fun `the line names the partner, whoever they are`() {
        assertEquals("Not reaching Rishabh's phone yet. Call Rishabh directly.", SosSenderStatus.line(Kind.NOT_REACHING, "Rishabh"))
        assertEquals("Ringing on Raina's phone", SosSenderStatus.line(Kind.RINGING, "Raina"))
    }

    @Test fun `I'm safe now is offered for two hours, until resolved`() {
        assertTrue(SosSenderStatus.canSaySafe(iso, null, at(60)))
        assertTrue(SosSenderStatus.canSaySafe(iso, null, at(2 * 3600)))
        assertFalse(SosSenderStatus.canSaySafe(iso, null, at(2 * 3600 + 1)))
        assertFalse(SosSenderStatus.canSaySafe(iso, at(30).toString(), at(60)))
    }
}
