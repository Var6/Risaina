package com.risaina.app.core.domain

import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.CalendarKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** One kind of occasion's reminders on this phone: on or off, and how many days before (0 is on the day). */
data class OccasionReminders(val on: Boolean, val daysBefore: List<Int>)

/**
 * Calendar reminder settings, kept per phone (ReminderSettingsStore): only this phone acts on them,
 * so they are never synced. The defaults are D6's.
 */
data class ReminderSettings(
    /** "Calendar reminders": off silences every reminder on this phone. */
    val enabled: Boolean = true,
    val anniversary: OccasionReminders = OccasionReminders(true, listOf(7, 0)),
    val partnerBirthday: OccasionReminders = OccasionReminders(true, listOf(7, 1, 0)),
    val myBirthday: OccasionReminders = OccasionReminders(false, listOf(0)),
    val nextVisit: OccasionReminders = OccasionReminders(false, listOf(1)),
    /** Yearly milestones only; one-off ones are dates in the timeline, not reminders. */
    val milestones: OccasionReminders = OccasionReminders(false, listOf(0)),
    /** All-day entries and occasions remind at this time on this phone's clock. */
    val allDayTime: LocalTime = LocalTime.of(9, 0),
    /** Also when the partner's birthday begins where they are (only when they share a zone that tells another time). */
    val partnerMidnight: Boolean = false,
    /** Nothing due before this is posted: a new install or a new couple gets no burst of old reminders. */
    val since: Instant? = null,
) {
    fun forOccasion(o: Occasion): OccasionReminders? = when (o.kind) {
        OccasionKind.ANNIVERSARY -> anniversary
        OccasionKind.PARTNER_BIRTHDAY -> partnerBirthday
        OccasionKind.MY_BIRTHDAY -> myBirthday
        OccasionKind.NEXT_VISIT -> nextVisit
        OccasionKind.MILESTONE -> milestones.takeIf { o.yearly }
    }
}

/** Everything the planner reads: Room's entries, the occasions, this phone's settings, zone and clock. */
data class ReminderInput(
    val entries: List<CalendarEntry>,
    val occasions: List<Occasion>,
    val settings: ReminderSettings,
    val myId: String,
    val zone: ZoneId,
    val now: Instant,
    /** The partner's shared zone, for "when it turns midnight for them" and their clock in the text. */
    val partnerZone: ZoneId? = null,
)

/**
 * One reminder. [key] is claimed in reminder_log before posting, so the alarm, the hourly sync and
 * app start never post it twice: "{occasion key}|{occurrence date}|{offset}" for an occasion, and
 * "{entry id}|{occurrence date}|{offset}|v{version}" for an entry. The version lets an entry moved
 * later the same day remind again at its new time; the fire times before that change are skipped
 * (updatedAt), so a rename or a tick never posts an old reminder a second time.
 */
data class Reminder(
    val key: String,
    val fireAt: Instant,
    /** Minutes before the start (all-day: before the all-day reminder time on the day); [MIDNIGHT] for "midnight for them". */
    val offsetMinutes: Int,
    /** The occurrence's date as written. */
    val occurrence: LocalDate,
    val allDay: Boolean,
    val entry: CalendarEntry? = null,
    val occasion: OccasionDay? = null,
) {
    companion object { const val MIDNIGHT = -1 }
}

/** What to post now, and when the one alarm should next go off (null: nothing within the horizon). */
data class ReminderPlan(val due: List<Reminder>, val next: Instant?)

/**
 * Decides which reminders are due and when the next one is (calendar.md §6). Pure: the scheduler
 * feeds it Room and DataStore and turns the plan into notifications and one AlarmManager alarm.
 *
 * - Entries remind only the phones in remindUserIds; done to-dos and entries of a kind this version
 *   does not know never remind; nothing after repeatUntil.
 * - A fire time before the entry's last change is skipped: an entry made 10 minutes ahead does not
 *   fire its "1 hour before" at once. One before [ReminderSettings.since] is skipped too.
 * - Late (the phone was off, the alarm inexact, Doze): posted up to [CATCH_UP] late. An all-day "on
 *   the day" reminder is still posted until [ALL_DAY_CUTOFF] that day. Older ones are dropped quietly.
 */
object ReminderPlanner {
    const val HORIZON_DAYS = 400L
    val CATCH_UP: Duration = Duration.ofHours(6)
    val ALL_DAY_CUTOFF: LocalTime = LocalTime.of(23, 0)

    fun plan(input: ReminderInput): ReminderPlan {
        val s = input.settings
        if (!s.enabled) return ReminderPlan(emptyList(), null)
        val now = input.now
        val today = now.atZone(input.zone).toLocalDate()
        // Fire times reach up to 4 weeks before an occurrence, and a late one back to yesterday.
        val from = today.minusDays(2)
        val to = today.plusDays(HORIZON_DAYS + 29)
        val horizon = now.plus(Duration.ofDays(HORIZON_DAYS))
        val due = mutableListOf<Reminder>()
        var next: Instant? = null

        fun consider(r: Reminder, notBefore: Instant?) {
            if (s.since != null && r.fireAt.isBefore(s.since)) return
            if (notBefore != null && r.fireAt.isBefore(notBefore)) return
            if (!r.fireAt.isAfter(now)) {
                if (isDue(r, now, input.zone)) due += r
            } else if (r.fireAt.isBefore(horizon) && (next == null || r.fireAt.isBefore(next))) {
                next = r.fireAt
            }
        }

        for (e in input.entries) {
            if (e.kind == CalendarKind.UNKNOWN || input.myId !in e.remindUserIds) continue
            if (e.kind == CalendarKind.TODO && e.done) continue
            if (e.reminders.isEmpty()) continue
            val furthest = e.reminders.max().toLong()
            for (occ in CalendarRules.occurrences(e, from, to)) {
                val base = if (e.allDay) ZonedDateTime.of(occ, s.allDayTime, input.zone) else CalendarRules.start(e, occ, input.zone)!!.atZone(input.zone)
                for (offset in e.reminders) {
                    consider(Reminder("${e.id}|$occ|$offset|v${e.version}", before(base, offset, e.allDay), offset, occ, e.allDay, entry = e), notBefore = e.updatedAt)
                }
                // Occurrences only get later: once the earliest reminder of this one is past the next found, stop.
                val earliest = base.minusMinutes(furthest).toInstant()
                next?.let { n -> if (earliest.isAfter(now) && earliest.isAfter(n)) break }
            }
        }

        for (o in input.occasions) {
            val cfg = s.forOccasion(o)?.takeIf { it.on } ?: continue
            for (day in CalendarOccasions.between(listOf(o), from, to)) {
                for (daysBefore in cfg.daysBefore.distinct()) {
                    val fireAt = ZonedDateTime.of(day.date.minusDays(daysBefore.toLong()), s.allDayTime, input.zone).toInstant()
                    consider(Reminder("${o.key}|${day.date}|${daysBefore * 1440}", fireAt, daysBefore * 1440, day.date, allDay = true, occasion = day), null)
                }
                if (o.kind == OccasionKind.PARTNER_BIRTHDAY && s.partnerMidnight && input.partnerZone != null) {
                    val fireAt = day.date.atStartOfDay(input.partnerZone).toInstant()
                    // On one clock their midnight is this phone's too: nothing "where they are" to tell.
                    if (!TimeZones.sameClock(input.zone, input.partnerZone, fireAt)) {
                        consider(Reminder("${o.key}|${day.date}|midnight", fireAt, Reminder.MIDNIGHT, day.date, allDay = false, occasion = day), null)
                    }
                }
            }
        }
        return ReminderPlan(due.sortedBy { it.fireAt }, next)
    }

    /** [offset] minutes before [base]. All-day: whole days back on the calendar, so a clock change never moves it by an hour. */
    private fun before(base: ZonedDateTime, offset: Int, allDay: Boolean): Instant =
        if (allDay) base.minusDays(offset / 1440L).minusMinutes(offset % 1440L).toInstant() else base.minusMinutes(offset.toLong()).toInstant()

    private fun isDue(r: Reminder, now: Instant, zone: ZoneId): Boolean {
        if (Duration.between(r.fireAt, now) <= CATCH_UP) return true
        val local = now.atZone(zone)
        return r.allDay && r.offsetMinutes == 0 && local.toLocalDate() == r.occurrence && local.toLocalTime().isBefore(ALL_DAY_CUTOFF)
    }

    /**
     * Whether to ask AlarmManager for an exact alarm. Below Android 12 that needs no permission; from
     * 12 on only once the person allowed "Alarms & reminders". Otherwise an inexact one, which Android
     * may deliver up to about an hour late.
     */
    fun useExact(sdk: Int, canScheduleExact: Boolean): Boolean = sdk < 31 || canScheduleExact
}

/** The words of a reminder notification: "Tomorrow · Bea's birthday · turning 27 🎂", "8:00 pm · 2:30 pm for Bea". Pure. */
object ReminderText {
    fun title(r: Reminder, now: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val name = r.entry?.title ?: r.occasion?.title ?: "Reminder"
        val emoji = r.entry?.emoji ?: r.occasion?.let { emoji(it.occasion.kind) }
        return "${lead(r, now, zone)} · $name" + (emoji?.let { " $it" } ?: "")
    }

    fun body(r: Reminder, zone: ZoneId, partnerName: String?, partnerZone: ZoneId?, locale: Locale = Locale.getDefault()): String {
        val who = partnerName?.substringBefore(" ")?.ifBlank { null } ?: "them"
        if (r.offsetMinutes == Reminder.MIDNIGHT) return "It's just turned midnight for $who"
        val e = r.entry ?: return CalendarText.day(r.occurrence, locale)
        val todo = e.kind == CalendarKind.TODO
        if (e.allDay) {
            val end = e.endDate?.let { CalendarRules.spanDays(e) }?.takeIf { it > 0 }?.let { r.occurrence.plusDays(it) }
            val days = CalendarText.day(r.occurrence, locale) + (end?.let { " – " + CalendarText.day(it, locale) } ?: "")
            return if (todo) "To-do · due $days" else days
        }
        val local = CalendarRules.localStart(e, r.occurrence, zone)
        val theirs = CalendarRules.partnerStart(e, r.occurrence, zone, partnerZone)
        val time = CalendarText.time(local.toLocalTime(), locale) + (theirs?.let { " · ${CalendarText.time(it.toLocalTime(), locale)} for $who" } ?: "")
        return if (todo) "To-do · due $time" else time
    }

    /** "Now", "In 10 min", "In 1 hour", "Today", "Tomorrow", "In 7 days". */
    fun lead(r: Reminder, now: Instant, zone: ZoneId): String {
        val today = now.atZone(zone).toLocalDate()
        if (r.offsetMinutes == Reminder.MIDNIGHT) return "Today"
        val e = r.entry
        if (r.allDay || e == null || e.allDay) return CalendarText.countdown(r.occurrence, today)
        val start = CalendarRules.start(e, r.occurrence, zone) ?: return CalendarText.countdown(r.occurrence, today)
        val seconds = Duration.between(now, start).seconds
        val minutes = if (seconds <= 0) 0 else (seconds + 59) / 60
        return when {
            minutes <= 0 -> "Now"
            minutes < 60 -> "In $minutes min"
            minutes < 24 * 60 -> "In ${CalendarText.plural(Math.round(minutes / 60.0).toInt(), "hour")}"
            else -> CalendarText.countdown(start.atZone(zone).toLocalDate(), today)
        }
    }

    fun emoji(kind: OccasionKind): String = when (kind) {
        OccasionKind.ANNIVERSARY -> "💞"
        OccasionKind.PARTNER_BIRTHDAY, OccasionKind.MY_BIRTHDAY -> "🎂"
        OccasionKind.NEXT_VISIT -> "✈️"
        OccasionKind.MILESTONE -> "✨"
    }

    /** What the lock screen shows when previews are off, or always on the public version. */
    const val PRIVATE_TITLE = "Reminder"
    const val PRIVATE_BODY = "Tap to see"
}
