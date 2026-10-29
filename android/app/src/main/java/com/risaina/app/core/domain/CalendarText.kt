package com.risaina.app.core.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How calendar dates and times read across the app: "Fri 14 Nov, 8:00 pm", "in 12 days". Pure. */
object CalendarText {
    fun day(d: LocalDate, locale: Locale = Locale.getDefault()): String = d.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))

    /** With the year only when it is not [thisYear]: "Sat 2 Jan 2027". */
    fun day(d: LocalDate, thisYear: Int, locale: Locale = Locale.getDefault()): String =
        if (d.year == thisYear) day(d, locale) else d.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale))

    fun time(t: LocalTime, locale: Locale = Locale.getDefault()): String = t.format(DateTimeFormatter.ofPattern("h:mm a", locale)).lowercase(locale)

    fun dayTime(d: LocalDate, t: LocalTime?, locale: Locale = Locale.getDefault()): String =
        if (t == null) day(d, locale) else "${day(d, locale)}, ${time(t, locale)}"

    /** "Today", "Tomorrow", "In 12 days", "Yesterday", "3 days ago". */
    fun countdown(date: LocalDate, today: LocalDate): String = when (val n = ChronoUnit.DAYS.between(today, date)) {
        0L -> "Today"
        1L -> "Tomorrow"
        -1L -> "Yesterday"
        in 2..Long.MAX_VALUE -> "In $n days"
        else -> "${-n} days ago"
    }

    /** A reminder offset as a chip says it. All-day entries count whole days back from the all-day reminder time. */
    fun offset(minutes: Int, allDay: Boolean): String = when {
        allDay && minutes == 0 -> "On the day"
        minutes == 0 -> "At the time"
        minutes % 10080 == 0 -> plural(minutes / 10080, "week") + " before"
        minutes % 1440 == 0 -> plural(minutes / 1440, "day") + " before"
        minutes % 60 == 0 -> plural(minutes / 60, "hour") + " before"
        else -> "$minutes min before"
    }

    fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
}
