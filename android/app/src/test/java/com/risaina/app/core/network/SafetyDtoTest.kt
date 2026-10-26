package com.risaina.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire format of the safety endpoints, with the app's real Json settings. */
class SafetyDtoTest {
    private val json = NetworkModule.json

    @Test fun `GET location sharing decodes the partner fix and liveness`() {
        val body = """
            {"mine":null,"partner":{"sessionId":"6720aa00aa00aa00aa00aa00","userId":"u2","reason":"panic","sosId":"s1",
             "startedAt":"2026-10-26T10:00:00.000Z","expiresAt":"2026-10-26T12:00:00.000Z","updatedAt":"2026-10-26T10:05:00.000Z",
             "lastSeenAt":"2026-10-26T10:05:00.000Z","fix":{"lat":12.9716,"lng":77.5946,"accuracyM":18,"at":"2026-10-26T10:04:58.000Z","batteryPct":34}}}
        """.trimIndent()
        val s = json.decodeFromString(LiveLocationStateDto.serializer(), body)
        assertNull(s.mine)
        val p = s.partner!!
        assertEquals("panic", p.reason)
        assertEquals(34, p.fix!!.batteryPct)
        assertEquals(18.0, p.fix!!.accuracyM, 0.0)
        assertEquals("2026-10-26T10:05:00.000Z", p.lastSeenAt)
    }

    @Test fun `location started payload has no fix`() {
        val payload = """{"sessionId":"a","userId":"u1","reason":"manual","sosId":null,"startedAt":"2026-10-26T10:00:00Z","expiresAt":null,"updatedAt":"2026-10-26T10:00:00Z","lastSeenAt":"2026-10-26T10:00:00Z"}"""
        val s = json.decodeFromString(LiveLocationDto.serializer(), payload)
        assertNull(s.fix)
        assertNull(s.expiresAt)
    }

    @Test fun `start request always carries mode and drops empty fields`() {
        val untilOff = json.encodeToString(StartSharingRequest.serializer(), StartSharingRequest(sessionId = "a", mode = "until_off"))
        assertTrue(untilOff.contains("\"mode\":\"until_off\""))
        assertTrue(untilOff.contains("\"reason\":\"manual\""))
        assertFalse(untilOff.contains("durationMinutes"))
        assertFalse(untilOff.contains("sosId"))
        assertFalse(untilOff.contains("fix"))
        val timed = json.encodeToString(StartSharingRequest.serializer(), StartSharingRequest(sessionId = "a", mode = "timed", durationMinutes = 60))
        assertTrue(timed.contains("\"durationMinutes\":60"))
    }

    @Test fun `a fix is sent with its age, never a wall-clock time`() {
        val body = json.encodeToString(PutFixRequest.serializer(), PutFixRequest("a", LocationFixInput(1.0, 2.0, 15.0, ageMs = 1200, batteryPct = 80)))
        assertTrue(body.contains("\"ageMs\":1200"))
        assertFalse(body.contains("\"at\""))
    }

    @Test fun `an ordinary SOS request stays as before`() {
        assertEquals("{}", json.encodeToString(CreateSosRequest.serializer(), CreateSosRequest()))
        val panic = json.encodeToString(CreateSosRequest.serializer(), CreateSosRequest("help", panic = true, idempotencyKey = "k"))
        assertTrue(panic.contains("\"panic\":true"))
        assertTrue(panic.contains("\"idempotencyKey\":\"k\""))
    }

    @Test fun `an SOS from an older server decodes with defaults`() {
        val s = json.decodeFromString(SosDto.serializer(), """{"id":"s","coupleId":"c","senderId":"a","recipientId":"b","message":null,"createdAt":"2026-10-26T10:00:00Z","acknowledgedAt":null}""")
        assertFalse(s.panic)
        assertNull(s.deliveredAt)
        assertNull(s.resolvedAt)
    }

    @Test fun `socket location frame decodes`() {
        val frame = """{"type":"location","location":{"sessionId":"a","userId":"u2","reason":"manual","startedAt":"2026-10-26T10:00:00Z","updatedAt":"2026-10-26T10:00:00Z","fix":{"lat":1,"lng":2,"accuracyM":10,"at":"2026-10-26T10:00:00Z","batteryPct":null}}}"""
        val msg = json.decodeFromString(WsInbound.serializer(), frame)
        assertEquals("location", msg.type)
        assertEquals(1.0, msg.location!!.fix!!.lat, 0.0)
    }
}
