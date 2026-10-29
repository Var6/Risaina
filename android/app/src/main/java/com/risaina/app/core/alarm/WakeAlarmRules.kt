package com.risaina.app.core.alarm

import com.risaina.app.core.domain.TimeZones
import com.risaina.app.core.model.AlarmMember
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.model.WakeMode
import com.risaina.app.core.model.WakeOutcome
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What to do about one alarm when (re-)arming it. */
data class ArmPlan(
    /** An occurrence that was set here, is due and never rang (the alarm was lost): ring it now. */
    val ringNow: Instant? = null,
    /** An occurrence that was set here and passed more than [WakeAlarmRules.LATE_LIMIT] ago without ringing. */
    val missed: Instant? = null,
    /** The next ring to set. */
    val next: Instant? = null,
)

/** What the receiver does with a FIRE for one occurrence. */
enum class FireDecision { RING, MISSED, EARLY, IGNORE }

/**
 * What re-arming does about the ring in the store, whose own alarms (timeout, snooze) a reboot or a
 * phone maker's force-stop may have dropped, together with its notification.
 */
enum class RingRecovery {
    NONE,
    /** Ringing, within its 10 minutes: its timeout is set again (the same alarm, so harmless if it is still there). */
    KEEP_TIMEOUT,
    /** Snoozed, not yet due: the snooze alarm and the "Snoozed until" notice are set again. */
    KEEP_SNOOZE,
    /** Its 10 minutes ran out and the timeout never came: handled as that timeout now. */
    TIME_OUT,
    /** The snooze ran out and its alarm never came: it rings again now. */
    RING_AGAIN,
    /** Over [WakeAlarmRules.LATE_LIMIT] past its time: it is not rung so late; missed. */
    GIVE_UP,
}

/** One person's side of an alarm, as their chip on the Wake screen says it. */
enum class MemberStatus { READY, OFF, NOT_SET_YET, PERMISSION_OFF, NOTIFICATIONS_OFF, NOT_READY, NEEDS_UPDATE, DONE }

/** The other partner's time next to mine in the editor. */
data class ZoneLines(val line: String, val warning: String?)

/**
 * "Wake together" rules, pure and unit-tested (WakeAlarmRulesTest).
 *
 * The rule is a time of day, ISO weekdays and an anchor IANA zone (the creator's), so both phones
 * work out the same instant and ring together; a one-off alarm is a single instant. Each occurrence
 * is resolved on its own day in the anchor zone, so daylight saving is handled per ring: a time in a
 * spring-forward gap moves forward by the gap (02:30 → 03:30), and a time in an autumn overlap takes
 * the earlier offset (java.time's ZonedDateTime.of). Only the next ring is ever set, and it is
 * recomputed after each one.
 */
object WakeAlarmRules {
    /** A ring more than this late is not rung: a quiet "missed" notice instead. */
    val LATE_LIMIT: Duration = Duration.ofMinutes(30)
    /** A fire this much early (a clock change) still rings; earlier than that waits for its time. */
    val EARLY_TOLERANCE: Duration = Duration.ofMinutes(1)
    /** How long one ring lasts before it snoozes itself (once) or gives up. */
    const val RING_MS = 10 * 60_000L
    /** "Test on this phone" rings this long after the tap, and stops after [TEST_RING_MS]. */
    const val TEST_DELAY_MS = 10_000L
    const val TEST_RING_MS = 60_000L
    const val DEFAULT_LABEL = "Wake together"
    const val TEST_ID = "test"
    /** Skips are at most 7 per person (the server's cap), so 9 weeks always reach a ring. */
    private const val SEARCH_DAYS = 64L
    /** The editor warns when the other person's ring falls in this part of their night. */
    private val NIGHT_END: LocalTime = LocalTime.of(5, 0)

    // ---- occurrences ------------------------------------------------------------------------

    /** [time] on [date] in [zone]: a gap moves forward by its length, an overlap takes the earlier offset. */
    fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Instant = ZonedDateTime.of(date, time, zone).toInstant()

    /** A later mode (each at their own local time) or a zone this phone does not know is never scheduled. */
    fun canSchedule(alarm: WakeAlarm): Boolean = alarm.mode == WakeMode.TOGETHER && alarm.zone != null

    /** Whether it rings on [member]'s phone at all: the alarm is on and their own switch is on. */
    fun ringsFor(alarm: WakeAlarm, member: AlarmMember?): Boolean = alarm.enabled && canSchedule(alarm) && member?.ring == true

    /** The next ring of the shared rule strictly after [after], whether or not it is on. */
    fun nextOfRule(alarm: WakeAlarm, after: Instant, skips: Collection<Instant> = emptyList()): Instant? {
        val zone = alarm.zone ?: return null
        if (alarm.mode != WakeMode.TOGETHER) return null
        if (alarm.days.isEmpty()) return alarm.onceAt?.takeIf { it.isAfter(after) && it !in skips }
        val days = alarm.days.toSet()
        // From the day before: a ring late on that day in the anchor zone can still be ahead.
        var date = after.atZone(zone).toLocalDate().minusDays(1)
        val last = date.plusDays(SEARCH_DAYS)
        while (!date.isAfter(last)) {
            if (date.dayOfWeek.value in days) {
                val ring = at(date, alarm.time, zone)
                if (ring.isAfter(after) && ring !in skips) return ring
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** The next ring on [member]'s phone strictly after [after]: the rule, the alarm's switch, theirs and their skips. */
    fun next(alarm: WakeAlarm, member: AlarmMember?, after: Instant): Instant? =
        if (!ringsFor(alarm, member)) null else nextOfRule(alarm, after, member!!.skips)

    /** Whether [instant] is a ring of the rule as it is now (an edit since makes an old one stale). */
    fun isOccurrence(alarm: WakeAlarm, instant: Instant): Boolean {
        val zone = alarm.zone ?: return false
        if (alarm.days.isEmpty()) return alarm.onceAt == instant
        val day = instant.atZone(zone).toLocalDate()
        return listOf(day.minusDays(1), day).any { it.dayOfWeek.value in alarm.days && at(it, alarm.time, zone) == instant }
    }

    /** Ring only up to [LATE_LIMIT] after the occurrence (a phone that was off does not ring an hour late). */
    fun shouldRing(occurrence: Instant, now: Instant): Boolean =
        !now.isBefore(occurrence.minus(EARLY_TOLERANCE)) && !now.isAfter(occurrence.plus(LATE_LIMIT))

    /**
     * A FIRE for [occurrence] arrived at [now]. It rings only if the alarm still exists, is on, rings
     * on this phone, was not skipped or edited since, did not already ring here ([handled]) and this
     * person is not already up (their other phone said so).
     */
    fun fireDecision(alarm: WakeAlarm?, member: AlarmMember?, occurrence: Instant, now: Instant, handled: Instant?): FireDecision {
        if (alarm == null || !ringsFor(alarm, member)) return FireDecision.IGNORE
        if (occurrence in member!!.skips || !isOccurrence(alarm, occurrence) || occurrence == handled) return FireDecision.IGNORE
        if (member.lastWake?.let { it.occurrence == occurrence && it.outcome == WakeOutcome.UP } == true) return FireDecision.IGNORE
        return when {
            now.isBefore(occurrence.minus(EARLY_TOLERANCE)) -> FireDecision.EARLY
            now.isAfter(occurrence.plus(LATE_LIMIT)) -> FireDecision.MISSED
            else -> FireDecision.RING
        }
    }

    /**
     * Arming one alarm at [now]. [armed] is what this phone set last time and [handled] the last
     * occurrence that rang (or was missed) here. When the set ring is due and never came (a reboot,
     * a force-stop that dropped the alarm) it rings now if it is less than 30 minutes late, and is
     * reported missed otherwise.
     */
    fun plan(alarm: WakeAlarm, member: AlarmMember?, now: Instant, armed: Instant?, handled: Instant?): ArmPlan {
        val next = next(alarm, member, now)
        val pending = armed?.takeIf {
            !it.isAfter(now) && it != handled && ringsFor(alarm, member) && it !in member!!.skips && isOccurrence(alarm, it) &&
                member.lastWake?.occurrence != it
        } ?: return ArmPlan(next = next)
        return if (shouldRing(pending, now)) ArmPlan(ringNow = pending, next = next) else ArmPlan(missed = pending, next = next)
    }

    /** The ring in the store at [now], when its own alarms may be gone (see [RingRecovery]). */
    fun recovery(ring: WakeRing?, now: Instant): RingRecovery {
        if (ring == null || !ring.active) return RingRecovery.NONE
        fun late(at: Long) = now.isAfter(Instant.ofEpochMilli(at).plus(LATE_LIMIT))
        val t = now.toEpochMilli()
        return when (ring.phase) {
            WakePhase.RINGING -> {
                val end = ring.startedAt + if (ring.test) TEST_RING_MS else RING_MS
                when {
                    t < end -> RingRecovery.KEEP_TIMEOUT
                    late(end) -> RingRecovery.GIVE_UP
                    else -> RingRecovery.TIME_OUT
                }
            }
            WakePhase.SNOOZED -> {
                val until = ring.until ?: return RingRecovery.GIVE_UP
                when {
                    t < until -> RingRecovery.KEEP_SNOOZE
                    late(until) -> RingRecovery.GIVE_UP
                    else -> RingRecovery.RING_AGAIN
                }
            }
            else -> RingRecovery.NONE
        }
    }

    /** The weekdays the rings fall on in [zone] (they can differ from the anchor's across the date line). */
    fun localDays(alarm: WakeAlarm, zone: ZoneId, now: Instant): List<Int> {
        if (alarm.days.isEmpty()) return emptyList()
        val out = sortedSetOf<Int>()
        var after = now
        repeat(alarm.days.size) {
            val ring = nextOfRule(alarm, after) ?: return out.toList()
            out += ring.atZone(zone).dayOfWeek.value
            after = ring
        }
        return out.toList()
    }

    // ---- words ------------------------------------------------------------------------------

    fun label(alarm: WakeAlarm): String = alarm.label?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LABEL

    /** "6:30 am". */
    fun timeText(time: LocalTime, locale: Locale = Locale.getDefault()): String =
        time.format(DateTimeFormatter.ofPattern("h:mm a", locale)).lowercase(locale)

    fun timeText(at: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String = timeText(at.atZone(zone).toLocalTime(), locale)

    /** "Every day", "Mon–Fri", "Weekends", "Mon, Wed, Fri" or "Once". */
    fun daysText(days: List<Int>, locale: Locale = Locale.getDefault()): String {
        val set = days.filter { it in 1..7 }.toSortedSet()
        fun name(d: Int) = DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, locale)
        return when {
            set.isEmpty() -> "Once"
            set.size == 7 -> "Every day"
            set == sortedSetOf(6, 7) -> "Weekends"
            set.size >= 3 && set.last() - set.first() == set.size - 1 -> "${name(set.first())}–${name(set.last())}"
            else -> set.joinToString(", ") { name(it) }
        }
    }

    /** "Today", "Tomorrow" or "Fri 14 Nov", by [zone]'s calendar. */
    fun dayText(at: Instant, zone: ZoneId, now: Instant, locale: Locale = Locale.getDefault()): String {
        val day = at.atZone(zone).toLocalDate()
        return when (ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), day)) {
            0L -> "Today"
            1L -> "Tomorrow"
            else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
        }
    }

    /** "Tomorrow 6:30 am". */
    fun whenText(at: Instant, zone: ZoneId, now: Instant, locale: Locale = Locale.getDefault()): String =
        "${dayText(at, zone, now, locale)} ${timeText(at, zone, locale)}"

    /**
     * The partner's notice when their alarm reaches this phone: "Raina set Wake together for 6:30 am,
     * Mon–Fri. It rings on your phone too." The time and days are this phone's own (for a couple in
     * two zones they are not the creator's). [change] is the event's: created, edited or enabled.
     */
    fun noticeText(partnerName: String, alarm: WakeAlarm, change: String, zone: ZoneId, now: Instant, locale: Locale = Locale.getDefault()): String {
        val ring = nextOfRule(alarm, now)
        val time = ring?.let { timeText(it, zone, locale) } ?: timeText(alarm.time, locale)
        // "6:30 am, Mon–Fri" or, once, "6:30 am tomorrow" / "6:30 am on Fri 14 Nov".
        val schedule = if (alarm.days.isNotEmpty()) "$time, ${daysText(localDays(alarm, zone, now), locale)}"
        else ring?.let { r -> dayText(r, zone, now, locale).let { d -> if (d == "Today" || d == "Tomorrow") "$time ${d.lowercase(locale)}" else "$time on $d" } } ?: time
        val name = label(alarm)
        val what = when (change) {
            "edited" -> "changed $name to"
            "enabled" -> "turned on $name for"
            else -> "set $name for"
        }
        return "$partnerName $what $schedule. It rings on your phone too."
    }

    /**
     * On the list, when this phone's ring time is not the anchor's: "Rings here 2:00 am (6:30 am for
     * Rishabh)". [creatorName] is the partner's name when they created it, else null (then the
     * anchor zone's city: "6:30 am Kolkata time"). The city too once the partner's clock
     * ([partnerZone]) no longer tells the anchor's time: the alarm was set somewhere else.
     */
    fun localLine(alarm: WakeAlarm, ring: Instant, zone: ZoneId, creatorName: String?, partnerZone: ZoneId? = null, locale: Locale = Locale.getDefault()): String? {
        val anchor = alarm.zone ?: return null
        if (TimeZones.sameClock(zone, anchor, ring)) return null
        val here = ring.atZone(zone).toLocalTime()
        val there = ring.atZone(anchor).toLocalTime()
        val whose = creatorName?.takeIf { TimeZones.sameClock(anchor, partnerZone, ring) }?.let { "for $it" } ?: "${zoneName(anchor)} time"
        return "Rings here ${timeText(here, locale)} (${timeText(there, locale)} $whose)"
    }

    /** "Kolkata" for Asia/Kolkata, "New York" for America/New_York. */
    fun zoneName(zone: ZoneId): String = zone.id.substringAfterLast('/').replace('_', ' ')

    /** The editor's "Set in London time" when the alarm's zone tells another time than this phone at [ring]; null on one clock. */
    fun anchorNote(ring: Instant, anchor: ZoneId, mine: ZoneId): String? =
        if (TimeZones.sameClock(mine, anchor, ring)) null else "Set in ${zoneName(anchor)} time"

    /**
     * The editor's two-zone line for a ring at [ring] set in [anchor]: "6:30 am for you · 9:00 am for
     * Aanya · tomorrow for them", and a warning when it falls in someone's night (00:00–05:00), as
     * in "That's 2:00 am for Aanya". Null when the two of you are on one clock and it is the
     * anchor's time too; set in a third zone, one line: "12:00 pm for you both". With the partner's
     * zone unknown (they hide their local time) nothing shows you share a clock, and they may be far
     * away: only "the same moment on both phones".
     */
    fun zoneLines(ring: Instant, anchor: ZoneId, mine: ZoneId, partnerZone: ZoneId?, partnerName: String, locale: Locale = Locale.getDefault()): ZoneLines? {
        val myTime = ring.atZone(mine).toLocalTime()
        if (partnerZone == null) {
            return ZoneLines(
                if (TimeZones.sameClock(mine, anchor, ring)) "Rings at the same moment on both phones" else "${timeText(myTime, locale)} for you. Rings at the same moment on both phones",
                nightWarning(myTime, "you", locale),
            )
        }
        if (TimeZones.sameClock(mine, partnerZone, ring)) {
            if (TimeZones.sameClock(mine, anchor, ring)) return null
            return ZoneLines("${timeText(myTime, locale)} for you both", nightWarning(myTime, "you both", locale))
        }
        val theirTime = ring.atZone(partnerZone).toLocalTime()
        val dayNote = when (ring.atZone(partnerZone).toLocalDate().compareTo(ring.atZone(mine).toLocalDate())) {
            0 -> null
            in 1..Int.MAX_VALUE -> "the next day for them"
            else -> "the day before for them"
        }
        val line = listOfNotNull("${timeText(myTime, locale)} for you", "${timeText(theirTime, locale)} for $partnerName", dayNote).joinToString(" · ")
        return ZoneLines(line, nightWarning(theirTime, partnerName, locale) ?: nightWarning(myTime, "you", locale))
    }

    private fun nightWarning(time: LocalTime, who: String, locale: Locale): String? =
        if (time.isBefore(NIGHT_END)) "That's ${timeText(time, locale)} for $who" else null

    /**
     * The other person's chip. NOT_SET_YET: their phone runs the new app but has not set the ring
     * the rule now gives (an edit has not reached it yet). NEEDS_UPDATE: their phone never reported,
     * so it runs a version without alarms.
     */
    fun memberStatus(alarm: WakeAlarm, member: AlarmMember?, now: Instant): MemberStatus {
        if (member == null) return MemberStatus.NOT_SET_YET
        if (!alarm.enabled || !member.ring) return MemberStatus.OFF
        val ready = member.ready ?: return MemberStatus.NEEDS_UPDATE
        if (!ready) return statusFor(member.reason)
        val expected = next(alarm, member, now) ?: return MemberStatus.DONE
        return if (member.armedFor == expected) MemberStatus.READY else MemberStatus.NOT_SET_YET
    }

    /** This phone's own chip: its readiness and what it has set. */
    fun myStatus(alarm: WakeAlarm, member: AlarmMember?, ready: Boolean, reason: String?, armed: Instant?, now: Instant): MemberStatus {
        if (member == null) return MemberStatus.NOT_SET_YET
        if (!alarm.enabled || !member.ring) return MemberStatus.OFF
        if (!canSchedule(alarm)) return MemberStatus.NEEDS_UPDATE
        if (!ready) return statusFor(reason)
        val expected = next(alarm, member, now) ?: return MemberStatus.DONE
        return if (armed == expected) MemberStatus.READY else MemberStatus.NOT_SET_YET
    }

    private fun statusFor(reason: String?): MemberStatus = when (reason) {
        WakeReadiness.EXACT_ALARMS_OFF -> MemberStatus.PERMISSION_OFF
        WakeReadiness.NOTIFICATIONS_OFF, WakeReadiness.CHANNEL_BLOCKED -> MemberStatus.NOTIFICATIONS_OFF
        WakeReadiness.UPDATE_NEEDED -> MemberStatus.NEEDS_UPDATE
        else -> MemberStatus.NOT_READY
    }

    /** "Ready", "Off on their phone"… [mine]: the words for this phone ("your phone"). */
    fun statusText(status: MemberStatus, mine: Boolean): String {
        val phone = if (mine) "your phone" else "their phone"
        return when (status) {
            MemberStatus.READY -> "Ready"
            MemberStatus.OFF -> "Off on $phone"
            MemberStatus.NOT_SET_YET -> "Not set on $phone yet"
            MemberStatus.PERMISSION_OFF -> "Alarms permission off"
            MemberStatus.NOTIFICATIONS_OFF -> "Notifications off"
            MemberStatus.NOT_READY -> "Not ready on $phone"
            MemberStatus.NEEDS_UPDATE -> if (mine) "Needs a newer Risaina" else "Needs the new Risaina"
            MemberStatus.DONE -> "Nothing coming up"
        }
    }

    /**
     * Under the time on the ringing screen: whether the partner is up for this very ring
     * ([partnerUpAt] from a fresh alarm.woke, or their stored last wake), or ringing too.
     */
    fun ringPartnerLine(partnerName: String, partner: AlarmMember?, occurrence: Instant, partnerUpAt: Instant?, zone: ZoneId, locale: Locale = Locale.getDefault()): String? =
        partnerUpLine(partnerName, partner, occurrence, partnerUpAt, zone, locale)
            ?: if (partner?.ring == true && occurrence !in partner.skips) "$partnerName's alarm is ringing too" else null

    /** "Raina is up ☀ 6:34 am" when the partner said "I'm up" for this very ring, else null. */
    fun partnerUpLine(partnerName: String, partner: AlarmMember?, occurrence: Instant, partnerUpAt: Instant?, zone: ZoneId, locale: Locale = Locale.getDefault()): String? {
        val up = partnerUpAt ?: partner?.lastWake?.takeIf { it.occurrence == occurrence && it.outcome == WakeOutcome.UP }?.at ?: return null
        return "$partnerName is up ☀ ${timeText(up, zone, locale)}"
    }
}
