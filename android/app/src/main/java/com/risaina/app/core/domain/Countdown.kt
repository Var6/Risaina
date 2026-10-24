package com.risaina.app.core.domain

import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

/** Days until a date, from the perspective of a given "today". Past dates count as 0 with [isPast]. */
data class Countdown(val days: Long, val isToday: Boolean, val isPast: Boolean) {
    val headline: String
        get() = when {
            isToday -> "Today"
            isPast -> "Passed"
            days == 1L -> "Tomorrow"
            else -> "$days days"
        }
}

object CountdownCalculator {
    fun until(target: LocalDate, today: LocalDate): Countdown {
        val days = ChronoUnit.DAYS.between(today, target)
        return Countdown(days = days.coerceAtLeast(0), isToday = days == 0L, isPast = days < 0)
    }

    /** "2 years, 3 months, 11 days" style duration for the relationship timeline. */
    fun together(since: LocalDate, today: LocalDate): Period =
        if (since.isAfter(today)) Period.ZERO else Period.between(since, today)

    /** The next occurrence of an annual date (anniversary) on or after today. */
    fun nextAnniversary(original: LocalDate, today: LocalDate): LocalDate {
        var candidate = original.withYear(today.year)
        if (candidate.isBefore(today)) candidate = candidate.plusYears(1)
        return candidate
    }

    fun formatTogether(p: Period): String {
        val parts = buildList {
            if (p.years > 0) add("${p.years} ${if (p.years == 1) "year" else "years"}")
            if (p.months > 0) add("${p.months} ${if (p.months == 1) "month" else "months"}")
            if (p.days > 0 || isEmpty()) add("${p.days} ${if (p.days == 1) "day" else "days"}")
        }
        return parts.joinToString(", ")
    }
}
