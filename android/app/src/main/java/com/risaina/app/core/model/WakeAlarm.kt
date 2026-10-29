package com.risaina.app.core.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * How a shared alarm rings. A plain string on the wire: a later mode (each at their own local time)
 * reaches this build as [UNKNOWN], which is shown with "Update Risaina to use this alarm" and never
 * scheduled.
 */
enum class WakeMode(val wire: String) {
    /** The same instant on both phones, at [WakeAlarm.time] in the creator's zone. */
    TOGETHER("together"),
    UNKNOWN("");
    companion object { fun fromWire(v: String) = entries.firstOrNull { it != UNKNOWN && it.wire == v } ?: UNKNOWN }
}

enum class WakeOutcome(val wire: String) {
    UP("up"), MISSED("missed"), UNKNOWN("");
    companion object { fun fromWire(v: String) = entries.firstOrNull { it != UNKNOWN && it.wire == v } ?: UNKNOWN }
}

/**
 * A "Wake together" alarm. The server only stores the rule; each phone works out the next ring from
 * [time], [days] and the anchor [timeZone], and rings its own copy.
 */
data class WakeAlarm(
    val id: String,
    val coupleId: String,
    val createdBy: String,
    val updatedBy: String?,
    val mode: WakeMode,
    val label: String?,
    val time: LocalTime,
    /** The creator's IANA zone when it was set; visible to the partner even when they hide their local time. */
    val timeZone: String,
    /** ISO weekdays (1 = Monday) in [timeZone]. Empty: a one-off at [onceAt]. */
    val days: List<Int>,
    val onceAt: Instant?,
    val enabled: Boolean,
    val snoozeMinutes: Int,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val members: List<AlarmMember>,
) {
    val zone: ZoneId? get() = runCatching { ZoneId.of(timeZone) }.getOrNull()
    fun member(userId: String): AlarmMember? = members.firstOrNull { it.userId == userId }
}

/** One partner's side of an alarm. Only that partner can change [ring] and [skips] (PUT /alarms/:id/me). */
data class AlarmMember(
    val userId: String,
    val ring: Boolean,
    /** Occurrences this person skips on their phone. */
    val skips: List<Instant>,
    /** Null: this person's phone never reported, so it runs a build without alarms (v75). */
    val ready: Boolean?,
    /** Why it is not ready, e.g. "exact_alarms_off", "notifications_off". */
    val reason: String?,
    val armedFor: Instant?,
    val reportedAt: Instant?,
    val lastWake: AlarmWake?,
)

data class AlarmWake(val occurrence: Instant, val outcome: WakeOutcome, val at: Instant, val snoozes: Int)
