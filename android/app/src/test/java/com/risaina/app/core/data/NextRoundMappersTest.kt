package com.risaina.app.core.data

import com.risaina.app.core.model.AlarmMember
import com.risaina.app.core.model.AlarmWake
import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.core.model.CalendarSource
import com.risaina.app.core.model.CalendarSourceKind
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.model.WakeMode
import com.risaina.app.core.model.WakeOutcome
import com.risaina.app.core.network.AlarmDto
import com.risaina.app.core.network.CalendarEntryDto
import com.risaina.app.core.network.CalendarSourceDto
import com.risaina.app.core.network.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** DTO ↔ entity ↔ model for the calendar and alarms. */
class NextRoundMappersTest {
    private val created = Instant.parse("2026-10-29T10:00:00Z")

    private fun entry(kind: CalendarKind = CalendarKind.TODO, repeat: CalendarRepeat = CalendarRepeat.NONE) = CalendarEntry(
        id = "e1", coupleId = "c", createdBy = "me", updatedBy = "bea", kind = kind, title = "Book tickets", notes = "Window seats",
        emoji = "🎟", date = LocalDate.of(2026, 12, 20), endDate = null, time = LocalTime.of(9, 30), durationMinutes = 30,
        timeZone = "Europe/London", repeat = repeat, repeatUntil = null, reminders = listOf(0, 60), remindUserIds = listOf("bea"),
        pinned = false, done = true, doneAt = created.plusSeconds(60), doneBy = "bea",
        source = CalendarSource(CalendarSourceKind.LIST_ITEM, listId = "l1", itemId = "i1"), clientKey = "k", version = 4,
        createdAt = created, updatedAt = created.plusSeconds(120),
    )

    @Test fun `a calendar entry survives model to row to model`() {
        val e = entry()
        assertEquals(e, e.toEntity().toDomain())
    }

    @Test fun `an UNKNOWN kind or repeat stays UNKNOWN through Room`() {
        val e = entry(kind = CalendarKind.UNKNOWN, repeat = CalendarRepeat.UNKNOWN)
        val back = e.toEntity().toDomain()!!
        assertEquals(CalendarKind.UNKNOWN, back.kind)
        assertEquals(CalendarRepeat.UNKNOWN, back.repeat)
    }

    @Test fun `a row keeps the wire values, so an unknown kind is not lost in storage`() {
        val dto = CalendarEntryDto(
            id = "e", kind = "habit", title = "Walk", date = "2026-11-01", repeat = "daily", reminders = listOf(1440, 0, 0),
            remindUserIds = listOf("me"), source = CalendarSourceDto("message", messageId = "m"),
            createdAt = "2026-10-29T10:00:00.000Z", updatedAt = "garbage",
        )
        val row = dto.toEntity()
        assertEquals("habit", row.kind)
        assertEquals("daily", row.repeat)
        assertEquals("1440,0,0", row.reminders)
        assertEquals("m", row.sourceMessageId)
        assertEquals(0L, row.updatedAt) // an unreadable timestamp does not throw
        val model = row.toDomain()!!
        assertEquals(listOf(0, 1440), model.reminders)
        assertNull(model.time)
    }

    @Test fun `an alarm survives model to JSON to model, members and last wake included`() {
        val a = WakeAlarm(
            id = "a", coupleId = "c", createdBy = "me", updatedBy = null, mode = WakeMode.TOGETHER, label = "Gym",
            time = LocalTime.of(6, 5), timeZone = "Asia/Kolkata", days = listOf(1, 3, 5), onceAt = null, enabled = true,
            snoozeMinutes = 10, version = 2, createdAt = created, updatedAt = created,
            members = listOf(
                AlarmMember("me", ring = true, skips = listOf(created.plusSeconds(86_400)), ready = true, reason = null, armedFor = created, reportedAt = created,
                    lastWake = AlarmWake(created, WakeOutcome.UP, created.plusSeconds(200), 1)),
                AlarmMember("bea", ring = false, skips = emptyList(), ready = null, reason = null, armedFor = null, reportedAt = null, lastWake = null),
            ),
        )
        val json = NetworkModule.json
        val stored = json.encodeToString(AlarmDto.serializer(), a.toDto())
        assertEquals(a, json.decodeFromString(AlarmDto.serializer(), stored).toDomain())
        assertEquals("06:05", a.toDto().time)
    }

    @Test fun `an alarm with an unreadable time is left out, odd days are cleaned`() {
        val dto = AlarmDto(id = "a", time = "6.30", timeZone = "Asia/Kolkata", createdAt = "x", updatedAt = "x")
        assertNull(dto.toDomain())
        val ok = dto.copy(time = "06:30", days = listOf(5, 9, 1, 5, 0)).toDomain()!!
        assertEquals(listOf(1, 5), ok.days)
        assertEquals(Instant.EPOCH, ok.createdAt)
    }
}
