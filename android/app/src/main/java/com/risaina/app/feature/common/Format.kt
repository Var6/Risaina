package com.risaina.app.feature.common

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val timeFmt: DateTimeFormatter get() = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dayFmt: DateTimeFormatter get() = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val fullFmt: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())

    fun relative(at: Instant, now: Instant = Instant.now()): String {
        val d = Duration.between(at, now)
        return when {
            d.isNegative || d.seconds < 45 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 24 && at.atZone(ZoneId.systemDefault()).toLocalDate() == now.atZone(ZoneId.systemDefault()).toLocalDate() ->
                at.atZone(ZoneId.systemDefault()).format(timeFmt)
            d.toDays() < 7 -> at.atZone(ZoneId.systemDefault()).format(dayFmt)
            else -> at.atZone(ZoneId.systemDefault()).format(fullFmt)
        }
    }

    fun time(at: Instant): String = at.atZone(ZoneId.systemDefault()).format(timeFmt)
    fun day(at: Instant): String = at.atZone(ZoneId.systemDefault()).format(dayFmt)
    fun date(d: LocalDate): String = d.format(fullFmt)
    fun timeOfDay(t: LocalTime): String = t.format(timeFmt)

    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** "Good evening, Asha" / "Still up, Asha?" — punctuation depends on the phrase. */
    fun greetingFor(name: String, now: LocalTime = LocalTime.now()): String {
        val who = name.ifBlank { "you" }
        return if (now.hour in 5..21) "${greeting(now)}, $who" else "Still up, $who?"
    }

    fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Still up?"
    }
}
