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

/** A field both partners share, in the words the editor uses when someone else changed it. */
enum class SharedField(val words: String) {
    TIME("the time"), DAYS("the days"), DAY("the day"), NAME("the name"), SNOOZE("the snooze"), ZONE("the time zone"), ON_OFF("whether it's on"),
}

/**
 * The editor caught up with a newer copy of its alarm after a version conflict. [draft] keeps every
 * field this person changed and takes the rest from the newer copy, at its version; [start] is the
 * newer copy as a draft, what later changes are measured against. [changed]: the shared fields that
 * moved on meanwhile. Empty when only a member entry did (a readiness report, a skip, a ring
 * switch), and then the save goes again by itself.
 */
data class WakeRebase(val draft: WakeDraft, val start: WakeDraft, val changed: List<SharedField>) {
    val retry: Boolean get() = changed.isEmpty()
}

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

    /**
     * A save met a newer copy ([newer]) of the alarm the editor started from ([base], which gave
     * the draft [start]). Every field this person changed from [start] keeps their value; the
     * others take [newer]'s, so the partner's change is never undone by a field nobody touched
     * here. The draft moves to [newer]'s version.
     */
    fun rebase(base: WakeAlarm, start: WakeDraft, newer: WakeAlarm, d: WakeDraft, me: String, now: Instant): WakeRebase {
        val theirs = of(newer, me, now)
        fun <T> pick(mine: T, from: T, other: T): T = if (mine != from) mine else other
        val draft = theirs.copy(
            time = pick(d.time, start.time, theirs.time),
            days = pick(d.days, start.days, theirs.days),
            date = pick(d.date, start.date, theirs.date),
            label = pick(d.label, start.label, theirs.label),
            snoozeMinutes = pick(d.snoozeMinutes, start.snoozeMinutes, theirs.snoozeMinutes),
            ringOnMyPhone = pick(d.ringOnMyPhone, start.ringOnMyPhone, theirs.ringOnMyPhone),
        )
        return WakeRebase(draft, theirs, sharedChanges(base, newer))
    }

    /** The shared fields that differ between two copies of one alarm (member entries aside). */
    fun sharedChanges(base: WakeAlarm, newer: WakeAlarm): List<SharedField> = buildList {
        if (base.time != newer.time) add(SharedField.TIME)
        if (base.days != newer.days) add(SharedField.DAYS)
        else if (base.onceAt != newer.onceAt && localDay(base) != localDay(newer)) add(SharedField.DAY)
        if (base.label != newer.label) add(SharedField.NAME)
        if (base.snoozeMinutes != newer.snoozeMinutes) add(SharedField.SNOOZE)
        if (base.timeZone != newer.timeZone) add(SharedField.ZONE)
        if (base.enabled != newer.enabled) add(SharedField.ON_OFF)
    }

    private fun localDay(a: WakeAlarm): LocalDate? = a.onceAt?.atZone(a.zone ?: ZoneId.systemDefault())?.toLocalDate()

    /** "Raina changed the time and the name meanwhile. …" after a conflict that needs a look. */
    fun conflictText(partnerName: String, changed: List<SharedField>, enabledNow: Boolean): String {
        val words = changed.filter { it != SharedField.ON_OFF }.map { it.words }
        val parts = listOfNotNull(
            words.takeIf { it.isNotEmpty() }?.let { "changed ${joinWords(it)}" },
            if (SharedField.ON_OFF in changed) (if (enabledNow) "turned it on" else "turned it off") else null,
        )
        val what = parts.joinToString(if (words.size > 1) ", and " else " and ").ifEmpty { "changed this alarm" }
        return "$partnerName $what meanwhile. Your changes are still here. Have a look and save again."
    }

    private fun joinWords(words: List<String>): String =
        if (words.size == 1) words[0] else words.dropLast(1).joinToString(", ") + " and " + words.last()
}
