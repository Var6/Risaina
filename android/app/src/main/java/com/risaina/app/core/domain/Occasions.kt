package com.risaina.app.core.domain

import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class Birthday(val monthDay: MonthDay, val year: Int?) {
    /** Wire format: "YYYY-MM-DD", or "--MM-DD" when the year is private. */
    val wire: String get() = if (year != null) "%04d-%02d-%02d".format(year, monthDay.monthValue, monthDay.dayOfMonth) else "--%02d-%02d".format(monthDay.monthValue, monthDay.dayOfMonth)

    companion object {
        fun parse(s: String?): Birthday? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                if (s.startsWith("--")) Birthday(MonthDay.parse(s), null)
                else LocalDate.parse(s).let { Birthday(MonthDay.from(it), it.year) }
            }.getOrNull()
        }
    }
}

data class UpcomingBirthday(val date: LocalDate, val daysLeft: Long, val turning: Int?)

data class AnniversaryInfo(
    val daysTogether: Long,
    val together: String,
    val nextDate: LocalDate,
    val daysLeft: Long,
    val yearsAtNext: Int,
)

object Occasions {
    /** Next birthday on or after [today]. Feb 29 falls on Feb 28 in non-leap years. */
    fun nextBirthday(b: Birthday, today: LocalDate): UpcomingBirthday {
        fun on(year: Int): LocalDate = if (b.monthDay.isValidYear(year)) b.monthDay.atYear(year) else LocalDate.of(year, 2, 28)
        var date = on(today.year)
        if (date.isBefore(today)) date = on(today.year + 1)
        return UpcomingBirthday(date, ChronoUnit.DAYS.between(today, date), b.year?.let { date.year - it })
    }

    fun anniversary(start: LocalDate, today: LocalDate): AnniversaryInfo {
        val next = run {
            var d = if (MonthDay.from(start).isValidYear(today.year)) start.withYear(today.year) else LocalDate.of(today.year, 2, 28)
            if (!d.isAfter(today) && d != today) d = if (MonthDay.from(start).isValidYear(today.year + 1)) start.withYear(today.year + 1) else LocalDate.of(today.year + 1, 2, 28)
            d
        }
        return AnniversaryInfo(
            daysTogether = ChronoUnit.DAYS.between(start, today).coerceAtLeast(0),
            together = CountdownCalculator.formatTogether(CountdownCalculator.together(start, today)),
            nextDate = next,
            daysLeft = ChronoUnit.DAYS.between(today, next),
            yearsAtNext = next.year - start.year,
        )
    }

    /** "11:42 pm" in the partner's zone, plus whether it is a different calendar day. */
    fun partnerClock(zoneId: String?, now: Instant, myZone: ZoneId = ZoneId.systemDefault()): Pair<String, String?>? {
        val zone = zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
        val theirs = ZonedDateTime.ofInstant(now, zone)
        val mine = ZonedDateTime.ofInstant(now, myZone)
        val time = theirs.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())).lowercase(Locale.getDefault())
        val dayNote = when (theirs.toLocalDate().compareTo(mine.toLocalDate())) {
            0 -> null
            in 1..Int.MAX_VALUE -> "tomorrow for them"
            else -> "yesterday for them"
        }
        return time to dayNote
    }

    fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
}
