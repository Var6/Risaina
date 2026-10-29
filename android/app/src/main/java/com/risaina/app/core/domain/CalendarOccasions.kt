package com.risaina.app.core.domain

import com.risaina.app.core.model.Couple
import com.risaina.app.core.model.Milestone
import com.risaina.app.core.model.MilestoneKind
import com.risaina.app.core.model.Partner
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit

enum class OccasionKind { ANNIVERSARY, PARTNER_BIRTHDAY, MY_BIRTHDAY, NEXT_VISIT, MILESTONE }

/**
 * A date the app already knows (the anniversary, both birthdays, the next visit, a milestone),
 * shown in the calendar and reminded without ever being stored as an entry: editing it goes back
 * to where it lives (the couple, a profile, the timeline). [key] is stable: "occ:anniversary",
 * "occ:birthday:{userId}", "occ:meeting", "occ:milestone:{id}".
 */
data class Occasion(
    val key: String,
    val kind: OccasionKind,
    /** The name without a count: "Anniversary", "Raina's birthday", "Goa, finally". */
    val label: String,
    val yearly: Boolean,
    /** Yearly occasions: the day each year. One-off occasions: the day of [date]. */
    val monthDay: MonthDay,
    /** The year it began (start date, birth year, milestone), when known: for "3rd" and "turning 27". */
    val sinceYear: Int?,
    /** One-off occasions only. */
    val date: LocalDate? = null,
    val milestone: Milestone? = null,
)

/** One occasion on one day, with the title for that year ("Our 3rd anniversary"). */
data class OccasionDay(val occasion: Occasion, val date: LocalDate, val title: String, val years: Int?)

/**
 * Works out the occasions from what is already synced, in one place: the timeline, Home, the
 * calendar and the reminder planner all use it (they used to repeat it with slightly different
 * Feb 29 rules). A yearly date of 29 February falls on the 28th in other years.
 */
object CalendarOccasions {
    fun derive(couple: Couple?, myId: String?, myBirthday: String?, partner: Partner?, milestones: List<Milestone>): List<Occasion> = buildList {
        val start = couple?.relationshipStartDate
        start?.let { add(Occasion("occ:anniversary", OccasionKind.ANNIVERSARY, "Anniversary", yearly = true, MonthDay.from(it), it.year)) }
        if (partner != null) Birthday.parse(partner.birthday)?.let { b ->
            add(Occasion("occ:birthday:${partner.id}", OccasionKind.PARTNER_BIRTHDAY, "${partner.displayName}'s birthday", yearly = true, b.monthDay, b.year))
        }
        if (myId != null) Birthday.parse(myBirthday)?.let { b ->
            add(Occasion("occ:birthday:$myId", OccasionKind.MY_BIRTHDAY, "Your birthday", yearly = true, b.monthDay, b.year))
        }
        couple?.nextMeetingDate?.let { d ->
            add(Occasion("occ:meeting", OccasionKind.NEXT_VISIT, couple.nextMeetingLabel?.takeIf { it.isNotBlank() } ?: "Next time together", yearly = false, MonthDay.from(d), null, date = d))
        }
        milestones.forEach { m ->
            // The anniversary is already here from the start date: the same day as a milestone too would show twice.
            if (m.kind == MilestoneKind.ANNIVERSARY && m.date == start) return@forEach
            val yearly = m.kind == MilestoneKind.ANNIVERSARY
            add(Occasion("occ:milestone:${m.id}", OccasionKind.MILESTONE, m.title, yearly, MonthDay.from(m.date), m.date.year, date = m.date.takeIf { !yearly }, milestone = m))
        }
    }

    /** A yearly occasion's day in [year]: 29 February is the 28th when [year] has none. */
    fun inYear(md: MonthDay, year: Int): LocalDate = if (md.isValidYear(year)) md.atYear(year) else LocalDate.of(year, 2, 28)

    /** The occasion on [date], or null when it does not fall then. */
    fun on(o: Occasion, date: LocalDate): OccasionDay? {
        if (!o.yearly) return if (o.date == date) day(o, date) else null
        if (o.sinceYear != null && date.year < o.sinceYear) return null
        return if (inYear(o.monthDay, date.year) == date) day(o, date) else null
    }

    /** The next time it comes round, today included; null for a one-off that has passed. */
    fun next(o: Occasion, today: LocalDate): OccasionDay? {
        if (!o.yearly) return o.date?.takeIf { !it.isBefore(today) }?.let { day(o, it) }
        var d = inYear(o.monthDay, today.year)
        if (d.isBefore(today)) d = inYear(o.monthDay, today.year + 1)
        if (o.sinceYear != null && d.year < o.sinceYear) d = inYear(o.monthDay, o.sinceYear)
        return day(o, d)
    }

    /** Every day the occasions fall on from [from] to [to], both included, in date order. */
    fun between(occasions: List<Occasion>, from: LocalDate, to: LocalDate): List<OccasionDay> = buildList {
        occasions.forEach { o ->
            if (!o.yearly) { o.date?.takeIf { !it.isBefore(from) && !it.isAfter(to) }?.let { add(day(o, it)) }; return@forEach }
            for (y in from.year..to.year) {
                val d = inYear(o.monthDay, y)
                if (!d.isBefore(from) && !d.isAfter(to)) on(o, d)?.let(::add)
            }
        }
    }.sortedBy { it.date }

    /** For Home's birthday chips and the partner's profile. */
    fun upcomingBirthday(occasions: List<Occasion>, kind: OccasionKind, today: LocalDate): UpcomingBirthday? =
        occasions.firstOrNull { it.kind == kind }?.let { next(it, today) }?.let { d ->
            UpcomingBirthday(d.date, ChronoUnit.DAYS.between(today, d.date), d.years)
        }

    private fun day(o: Occasion, date: LocalDate): OccasionDay {
        val years = if (o.yearly) o.sinceYear?.let { date.year - it } else null
        val title = when (o.kind) {
            OccasionKind.ANNIVERSARY -> if (years != null && years > 0) "Our ${Occasions.ordinal(years)} anniversary" else "The day it began"
            OccasionKind.PARTNER_BIRTHDAY -> o.label + (years?.takeIf { it > 0 }?.let { " · turning $it" } ?: "")
            OccasionKind.MY_BIRTHDAY -> o.label
            OccasionKind.NEXT_VISIT -> o.label
            OccasionKind.MILESTONE -> o.label + (years?.takeIf { it > 0 }?.let { " · ${if (it == 1) "1 year" else "$it years"}" } ?: "")
        }
        return OccasionDay(o, date, title, years)
    }
}
