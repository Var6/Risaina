package com.risaina.app.testutil

import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.core.model.CalendarSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** A calendar entry for tests: an all-day event for both of us unless the test says otherwise. */
fun calendarEntry(
    id: String = "e1",
    date: LocalDate = LocalDate.of(2026, 11, 14),
    title: String = "Dinner",
    kind: CalendarKind = CalendarKind.EVENT,
    time: LocalTime? = null,
    timeZone: String? = null,
    endDate: LocalDate? = null,
    repeat: CalendarRepeat = CalendarRepeat.NONE,
    repeatUntil: LocalDate? = null,
    reminders: List<Int> = emptyList(),
    remindUserIds: List<String> = listOf("me", "bea"),
    done: Boolean = false,
    pinned: Boolean = false,
    createdBy: String = "me",
    updatedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    source: CalendarSource? = null,
    emoji: String? = null,
    notes: String? = null,
    durationMinutes: Int? = null,
    version: Int = 1,
) = CalendarEntry(
    id = id, coupleId = "c", createdBy = createdBy, updatedBy = createdBy, kind = kind, title = title, notes = notes, emoji = emoji,
    date = date, endDate = endDate, time = time, durationMinutes = durationMinutes, timeZone = timeZone, repeat = repeat, repeatUntil = repeatUntil,
    reminders = reminders, remindUserIds = remindUserIds, pinned = pinned, done = done, doneAt = null, doneBy = null, source = source,
    clientKey = null, version = version, createdAt = updatedAt, updatedAt = updatedAt,
)
