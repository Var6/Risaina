package com.risaina.app.feature.wake

import com.risaina.app.core.alarm.WakeAlarmRules
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.network.PatchAlarmRequest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the editor holds. [zone] is the anchor: this phone's zone for a new alarm, the alarm's own
 * for an edit (edits keep it). No [days] means once, on [date].
 */
data class WakeDraft(
    val id: String? = null,
    val version: Int = 0,
    val time: LocalTime = LocalTime.of(6, 30),
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val date: LocalDate? = null,
    val label: String = "",
    val snoozeMinutes: Int = 10,
    val ringOnMyPhone: Boolean = true,
    val zone: String = ZoneId.systemDefault().id,
)

/** The editor's rules, pure (WakeDraftTest). */
object WakeDrafts {
    const val MAX_LABEL = 40
    val SNOOZE_CHOICES = listOf(5, 10, 15, 20, 30)
    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** A new alarm: 6:30 am on weekdays, in this phone's zone; once means the next 6:30 that is ahead. */
    fun new(zone: ZoneId, now: Instant): WakeDraft = WakeDraft(zone = zone.id, date = defaultDate(LocalTime.of(6, 30), zone, now))

    fun of(alarm: WakeAlarm, me: String, now: Instant): WakeDraft {
        val zone = alarm.zone ?: ZoneId.systemDefault()
        return WakeDraft(
            id = alarm.id, version = alarm.version, time = alarm.time, days = alarm.days.toSet(),
            date = alarm.onceAt?.atZone(zone)?.toLocalDate() ?: defaultDate(alarm.time, zone, now),
            label = alarm.label.orEmpty(), snoozeMinutes = alarm.snoozeMinutes,
            ringOnMyPhone = alarm.member(me)?.ring ?: true, zone = alarm.timeZone,
        )
    }

    /** Today if [time] is still ahead there, else tomorrow. */
    fun defaultDate(time: LocalTime, zone: ZoneId, now: Instant): LocalDate {
        val today = now.atZone(zone).toLocalDate()
        return if (WakeAlarmRules.at(today, time, zone).isAfter(now)) today else today.plusDays(1)
    }

    /** The ring the editor's two-zone line is about: the one-off, or the next of the chosen days. */
    fun nextRing(d: WakeDraft, now: Instant): Instant? {
        val zone = runCatching { ZoneId.of(d.zone) }.getOrNull() ?: return null
        if (d.days.isEmpty()) return onceAt(d)
        var date = now.atZone(zone).toLocalDate()
        repeat(8) {
            if (date.dayOfWeek.value in d.days) WakeAlarmRules.at(date, d.time, zone).takeIf { it.isAfter(now) }?.let { return it }
            date = date.plusDays(1)
        }
        return null
    }

    /** The one-off ring, or null for a repeating alarm. */
    fun onceAt(d: WakeDraft): Instant? =
        if (d.days.isNotEmpty()) null else WakeAlarmRules.at(d.date ?: return null, d.time, ZoneId.of(d.zone))

    /**
     * The time the server is sent. For a one-off whose time falls in a spring-forward gap it is the
     * resolved local time (02:30 on that day is 03:30), because the server checks that onceAt is
     * `time` in the zone.
     */
    fun wireTime(d: WakeDraft): LocalTime = onceAt(d)?.atZone(ZoneId.of(d.zone))?.toLocalTime() ?: d.time

    /** Null when it can be saved, else what to say. */
    fun problem(d: WakeDraft, now: Instant): String? {
        val zone = runCatching { ZoneId.of(d.zone) }.getOrNull() ?: return "This alarm's time zone isn't known on this phone"
        if (d.days.isEmpty()) {
            val date = d.date ?: return "Pick a day"
            if (!WakeAlarmRules.at(date, d.time, zone).isAfter(now)) return "That time has already passed. Pick a later one."
            if (date.isAfter(now.atZone(zone).toLocalDate().plusDays(365))) return "Pick a day within the next year"
        }
        if (d.label.trim().length > MAX_LABEL) return "Keep the name under $MAX_LABEL characters"
        return null
    }

    /**
     * PATCH /alarms/:id with only what changed, at the version the editor started from. A one-off
     * that becomes repeating sends onceAt as null; a one-off whose day or time changes sends the
     * new onceAt with the matching time. Null when nothing shared changed (the ring switch is the
     * person's own, sent separately).
     */
    fun patch(alarm: WakeAlarm, d: WakeDraft): PatchAlarmRequest? {
        val clear = mutableSetOf<String>()
        val label = d.label.trim()
        val labelChange = when {
            label.isEmpty() && alarm.label != null -> { clear += "label"; null }
            label.isNotEmpty() && label != alarm.label -> label
            else -> null
        }
        val days = d.days.sorted()
        val daysChange = days.takeIf { it != alarm.days }
        val once = onceAt(d)
        val time = wireTime(d)
        val timeChange = time.takeIf { it != alarm.time }?.format(HH_MM)
        val onceChange = when {
            once == null && alarm.onceAt != null -> { clear += "onceAt"; null }
            once != null && once != alarm.onceAt -> once.toString()
            else -> null
        }
        val snooze = d.snoozeMinutes.takeIf { it != alarm.snoozeMinutes }
        if (labelChange == null && daysChange == null && timeChange == null && onceChange == null && snooze == null && clear.isEmpty()) return null
        return PatchAlarmRequest(
            expectedVersion = d.version, label = labelChange, time = timeChange, days = daysChange, onceAt = onceChange,
            snoozeMinutes = snooze, clear = clear,
        )
    }
}
