package com.risaina.app.core.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZonedDateTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** How "3/4" reads where the locale writes day and month as numbers. */
enum class DateOrder {
    DMY, MDY;

    companion object {
        /** From the locale's short date pattern: en-IN and en-GB write the day first, en-US the month. */
        fun forLocale(locale: Locale): DateOrder {
            val pattern = runCatching {
                DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
            }.getOrNull() ?: return DMY
            val d = pattern.indexOf('d')
            val m = pattern.indexOf('M')
            return if (d >= 0 && m >= 0 && m < d) MDY else DMY
        }
    }
}

/**
 * A date (and maybe a time) found in some text. [range] indexes the original text. [confident] is
 * false for readings a person often means otherwise ("3/4", a bare "at 8"); [alternatives] then holds
 * the other readings, for one-tap chips. [dateGuessed]: only a time was written ("7pm"), and the date
 * is the next time it comes round.
 */
data class DateHit(
    val range: IntRange,
    val date: LocalDate,
    val time: LocalTime? = null,
    val endTime: LocalTime? = null,
    val confident: Boolean = true,
    val dateGuessed: Boolean = false,
    val alternatives: List<Pair<LocalDate, LocalTime?>> = emptyList(),
)

/**
 * Finds dates and times in a message or a note: "tomorrow 7pm", "Sat", "next Friday", "25th of
 * December", "Dec 25", "2026-12-25", "25/12", "noon", "7-9pm", "in 2 hours". Plain java.time, no
 * library: the results are only ever suggestions the person confirms, so a small, predictable parser
 * beats a clever one.
 *
 * Deliberately not understood (they would guess more than they find): Hinglish ("kal" is both
 * yesterday and tomorrow, "7 baje"), holidays, "next week", "end of the month", repeats ("every
 * Friday"). Words that are also ordinary words never match on their own: a short weekday needs a
 * capital, a lead-in ("on sat") or a time after it, so "I sat down" and "the sun is out" stay free; a
 * month needs a day number ("I may come", "march on"); "7.30", "2-1" and "1/2 cup" are not dates.
 */
object DateDetector {
    private enum class Meridiem { AM, PM }

    private sealed interface Part { val range: IntRange }

    private data class DatePart(
        override val range: IntRange,
        val date: LocalDate,
        val alternatives: List<LocalDate> = emptyList(),
        val confident: Boolean = true,
        /** "tonight" means the evening: a bare "at 9" with it is 9 pm. */
        val hint: Meridiem? = null,
        /** "in 2 hours" carries its own time. */
        val time: LocalTime? = null,
    ) : Part

    private enum class TimeKind { MERIDIEM, CLOCK, BARE, NOON, MIDNIGHT }

    private data class TimePart(
        override val range: IntRange,
        val kind: TimeKind,
        val hour: Int,
        val minute: Int,
        val meridiem: Meridiem? = null,
        /** For a range ("7-9pm"): the end, already in 24-hour form. */
        val end: LocalTime? = null,
    ) : Part

    /** A time read with its hint: the time, and the other reading for a bare "at 8". */
    private data class ReadTime(val time: LocalTime, val alternative: LocalTime?, val confident: Boolean, val nextDay: Boolean)

    // ---- vocabulary ----

    private const val NOT_WORD_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{N}])"
    private const val MERIDIEM = "(a\\.m\\.?|p\\.m\\.?|am|pm)(?![\\p{L}])"
    private const val MONTHS = "january|february|march|april|may|june|july|august|september|october|november|december|" +
        "jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"

    private val RELATIVE_DAY = Regex(
        NOT_WORD_BEFORE + "(the day after (?:tomorrow|tommorow|tomorow|tmrw|tmr|2moro)|day after (?:tomorrow|tommorow|tomorow|tmrw|tmr|2moro)|" +
            "today|tonight|tonite|tomorrow|tommorow|tomorow|tmrw|tmr|2moro)" + NOT_WORD_AFTER,
    )
    private val WEEKDAY = Regex(
        NOT_WORD_BEFORE + "(?:(this coming|this|next|coming)\\s+)?" +
            "(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun)\\.?(?![\\p{L}])",
    )
    private val SHORT_WEEKDAYS = setOf("mon", "tues", "tue", "wed", "thurs", "thur", "thu", "fri", "sat", "sun")
    private val WEEKDAY_LEAD_IN = Regex("(?:^|[^\\p{L}])(on|this|next|coming|by|till|until|before)\\s+$")
    private val TIME_AFTER = Regex("^\\s*(?:at\\s*|@\\s*)?(?:\\d{1,2}(?:[:.]\\d{2})?\\s?(?:a\\.m|p\\.m|am|pm)(?![\\p{L}])|\\d{1,2}:\\d{2}|noon|midnight|morning|afternoon|evening|night)")
    private val DAY_MONTH = Regex(
        NOT_WORD_BEFORE + "(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?[\\s-]*($MONTHS)\\.?(?![\\p{L}])(?:,?\\s+(\\d{4})" + NOT_WORD_AFTER + ")?",
    )
    private val MONTH_DAY = Regex(
        "(?<![\\p{L}])($MONTHS)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?$NOT_WORD_AFTER(?:,?\\s+(\\d{4})$NOT_WORD_AFTER)?",
    )
    private val ISO = Regex("(?<![\\p{L}\\p{N}.:/-])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\p{L}\\p{N}]|[./-]\\d)")
    private val NUMERIC_WITH_YEAR = Regex("(?<![\\p{L}\\p{N}.:/-])(\\d{1,2})([./-])(\\d{1,2})\\2(\\d{4}|\\d{2})(?![\\p{L}\\p{N}]|[./-]\\d)")
    private val NUMERIC = Regex("(?<![\\p{L}\\p{N}.:/-])(\\d{1,2})/(\\d{1,2})(?![\\p{L}\\p{N}]|[./-]\\d)")
    /** "1/2 cup", "3/4 of us": a fraction, not a date. */
    private val FRACTION_AFTER = Regex(
        "^\\s*(?:cups?|tsp|tbsp|teaspoons?|tablespoons?|kgs?|kilos?|g|grams?|lbs?|km|miles?|hours?|hrs?|litres?|liters?|ml|of|glass(?:es)?|" +
            "inch(?:es)?|portions?|slices?|done|way|left|marks|points|stars)(?![\\p{L}])",
    )
    private val RELATIVE_SPAN = Regex(
        "(?<![\\p{L}])in\\s+(\\d{1,3}|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\s+" +
            "(minutes?|mins?|hours?|hrs?|days?|weeks?)(?![\\p{L}])",
    )
    private val TIME_RANGE = Regex(
        "(?<![\\p{L}\\p{N}.:])(\\d{1,2})(?:[:.](\\d{2}))?\\s?(?:$MERIDIEM)?\\s?(?:-|–|—|to|till|until)\\s?(\\d{1,2})(?:[:.](\\d{2}))?\\s?(?:$MERIDIEM)?" +
            "(?![\\p{L}\\p{N}])",
    )
    private val TIME_MERIDIEM = Regex("(?<![\\p{L}\\p{N}.:])(\\d{1,2})(?:[:.](\\d{2}))?\\s?$MERIDIEM")
    private val TIME_CLOCK = Regex("(?<![\\p{L}\\p{N}.:])(\\d{1,2}):(\\d{2})(?![\\p{N}]|:\\d|\\s?(?:a\\.m|p\\.m|am|pm)(?![\\p{L}]))")
    private val TIME_WORD = Regex(NOT_WORD_BEFORE + "(noon|midday|midnight)" + NOT_WORD_AFTER)
    private val TIME_BARE = Regex(
        "(?:(?<![\\p{L}\\p{N}])at\\s*|@\\s*)(\\d{1,2})(?:\\s*o'?clock)?(?![\\p{N}]|[:.]\\d|\\s?(?:a\\.m|p\\.m|am|pm)(?![\\p{L}])|\\s?%)",
    )
    private val TIME_OCLOCK = Regex("(?<![\\p{L}\\p{N}.:])(\\d{1,2})\\s*o'?clock(?![\\p{L}])")
    private val DAYPART = Regex("(morning|afternoon|evening|night)")
    private val HINT_BEFORE = Regex("(?:morning|afternoon|evening|night|tonight|tonite)\\s*(?:at|@)?\\s*$")
    private val HINT_AFTER = Regex("^\\s*(?:in the |this |at |tomorrow |tmrw )?(?:morning|afternoon|evening|night|tonight|tonite)")
    /** What may stand between a date and its time: "Sat, 8pm", "tomorrow morning at 8", "25 Dec @ 7". */
    private val GAP = Regex("^(?:\\s|,|\\bat\\b|@|\\bon\\b|\\bby\\b|\\bfrom\\b|\\bin the\\b|\\bthis\\b|\\bmorning\\b|\\bafternoon\\b|\\bevening\\b|\\bnight\\b)*$")
    private const val MAX_GAP = 16
    private val NUMBER_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )

    /**
     * Every date or time in [text], in text order. [reference] is "now" for what is written: a
     * message's own time in its author's zone (so "tomorrow" in Tuesday's message means Wednesday),
     * or the present for the composer and notes. [order] reads "3/4".
     */
    fun find(text: String, reference: ZonedDateTime, order: DateOrder): List<DateHit> {
        if (text.isBlank()) return emptyList()
        // Per character, so indices still match the original ("İ".lowercase() is two characters).
        val low = String(CharArray(text.length) { Character.toLowerCase(text[it]) })
        val today = reference.toLocalDate()
        val candidates = buildList {
            addAll(relativeDays(low, today))
            addAll(weekdays(text, low, today))
            addAll(monthDates(low, today))
            addAll(numericDates(low, today, order))
            addAll(relativeSpans(low, reference))
            addAll(times(low))
        }
        // Overlaps: the longest reading wins ("day after tomorrow" over "tomorrow", "7-9pm" over "9pm").
        val parts = mutableListOf<Part>()
        candidates.sortedWith(compareByDescending<Part> { it.range.last - it.range.first }.thenBy { it.range.first }).forEach { c ->
            if (parts.none { it.range.first <= c.range.last && c.range.first <= it.range.last }) parts += c
        }
        parts.sortBy { it.range.first }
        val hits = mutableListOf<DateHit>()
        var i = 0
        while (i < parts.size) {
            val a = parts[i]
            val b = parts.getOrNull(i + 1)
            val pair = if (b != null && a::class != b::class && joinable(low, a, b)) {
                if (a is DatePart) a to (b as TimePart) else (b as DatePart) to (a as TimePart)
            } else null
            if (pair != null) {
                combine(low, pair.first, pair.second)?.let(hits::add)
                i += 2
            } else {
                when (a) {
                    is DatePart -> hits += DateHit(a.range, a.date, a.time, confident = a.confident, alternatives = a.alternatives.map { it to a.time })
                    is TimePart -> timeOnly(low, a, reference)?.let(hits::add)
                }
                i += 1
            }
        }
        return hits
    }

    /**
     * A title from the text around the first hit: the date words go, and so do the little words left
     * at their edges ("on", "at", "by") and a leading "let's" or "remind me to". "Dinner at Olive
     * tomorrow 7pm?" gives "Dinner at Olive". Empty when nothing is left ("Sat"): the caller then uses
     * its own ("Plan with Raina"). At most 120 characters.
     */
    fun suggestTitle(text: String, hits: List<DateHit>): String {
        var lineStart = 0
        var lineEnd = text.length
        hits.firstOrNull()?.let { first ->
            lineStart = text.lastIndexOf('\n', (first.range.first - 1).coerceAtLeast(0)).let { if (it < 0 || it >= first.range.first) 0 else it + 1 }
            lineEnd = text.indexOf('\n', first.range.last).let { if (it < 0) text.length else it }
        }
        val segments = mutableListOf<String>()
        var at = lineStart
        hits.filter { it.range.first >= lineStart && it.range.last < lineEnd }.sortedBy { it.range.first }.forEach { h ->
            if (h.range.first > at) segments += text.substring(at, h.range.first)
            segments += CUT
            at = maxOf(at, h.range.last + 1)
        }
        if (at < lineEnd) segments += text.substring(at, lineEnd)
        // Little words standing right against a cut date go with it.
        val cleaned = segments.mapIndexed { idx, seg ->
            if (seg == CUT) return@mapIndexed seg
            var s = seg
            if (segments.getOrNull(idx + 1) == CUT) s = s.replace(EDGE_END, "")
            if (segments.getOrNull(idx - 1) == CUT) s = s.replace(EDGE_START, "")
            s
        }.filter { it != CUT }
        var title = cleaned.joinToString(" ").replace(Regex("\\s+"), " ").trim().trim(*TRIM)
        title = title.replace(LEADING, "").trim().trim(*TRIM)
        if (title.isEmpty()) return ""
        title = title.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        return if (title.length <= 120) title else title.take(119).trimEnd() + "…"
    }

    private const val CUT = "\u0000"
    private val EDGE_END = Regex("(?:[\\s,]|\\b(?:on|at|by|this|from|for|till|until|before)\\b|@)*$", RegexOption.IGNORE_CASE)
    private val EDGE_START = Regex("^(?:[\\s,]|\\b(?:on|at|by|this|from)\\b|@)*", RegexOption.IGNORE_CASE)
    private val LEADING = Regex("^(?:let'?s|let’s|can we|shall we|we should|remember to|remind me to|don'?t forget to|don’t forget to)\\b\\s*", RegexOption.IGNORE_CASE)
    private val TRIM = charArrayOf(' ', ',', '.', ';', ':', '!', '?', '-', '–', '—', '\n', '\t')

    // ---- dates ----

    private fun relativeDays(low: String, today: LocalDate): List<Part> = RELATIVE_DAY.findAll(low).map { m ->
        val w = m.groupValues[1]
        when {
            w.contains("day after") -> DatePart(m.range, today.plusDays(2))
            w == "today" -> DatePart(m.range, today)
            w == "tonight" || w == "tonite" -> DatePart(m.range, today, hint = Meridiem.PM)
            else -> DatePart(m.range, today.plusDays(1))
        }
    }.toList()

    private fun weekdays(text: String, low: String, today: LocalDate): List<Part> = WEEKDAY.findAll(low).mapNotNull { m ->
        val prefix = m.groups[1]?.value
        val name = m.groupValues[2]
        val nameStart = m.groups[2]!!.range.first
        if (name in SHORT_WEEKDAYS && prefix == null) {
            // "I sat down", "the sun", "we wed": a short form needs a capital, a lead-in or a time after it.
            val capital = text[nameStart].isUpperCase()
            val leadIn = WEEKDAY_LEAD_IN.containsMatchIn(low.substring(maxOf(0, nameStart - 12), nameStart))
            val timeAfter = TIME_AFTER.containsMatchIn(low.substring(m.range.last + 1))
            if (!capital && !leadIn && !timeAfter) return@mapNotNull null
        }
        val day = dayOfWeek(name)
        // "Thursday" said on a Thursday most often means next week, but maybe today.
        val strictlyNext = today.with(TemporalAdjusters.next(day))
        when (prefix) {
            "this" -> DatePart(m.range, today.with(TemporalAdjusters.nextOrSame(day)))
            "next" -> {
                val following = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(day))
                DatePart(m.range, following, alternatives = listOf(strictlyNext).filter { it != following })
            }
            "coming", "this coming" -> DatePart(m.range, strictlyNext)
            else -> DatePart(m.range, strictlyNext, alternatives = if (today.dayOfWeek == day) listOf(today) else emptyList())
        }
    }.toList()

    private fun dayOfWeek(name: String): DayOfWeek = when (name.take(3)) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    private fun monthNumber(name: String): Int = when (name.take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; else -> 12
    }

    private fun monthDates(low: String, today: LocalDate): List<Part> {
        val dayFirst = DAY_MONTH.findAll(low).mapNotNull { m ->
            dated(m.range, m.groupValues[1].toInt(), monthNumber(m.groupValues[2]), m.groups[3]?.value?.toInt(), today, confident = true)
        }
        val monthFirst = MONTH_DAY.findAll(low).mapNotNull { m ->
            dated(m.range, m.groupValues[2].toInt(), monthNumber(m.groupValues[1]), m.groups[3]?.value?.toInt(), today, confident = true)
        }
        return (dayFirst + monthFirst).toList()
    }

    private fun numericDates(low: String, today: LocalDate, order: DateOrder): List<Part> = buildList {
        ISO.findAll(low).forEach { m ->
            dated(m.range, m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt(), today, confident = true)?.let(::add)
        }
        NUMERIC_WITH_YEAR.findAll(low).forEach { m ->
            val y = m.groupValues[4].let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
            numeric(m.range, m.groupValues[1].toInt(), m.groupValues[3].toInt(), y, today, order)?.let(::add)
        }
        NUMERIC.findAll(low).forEach { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (FRACTION_AFTER.containsMatchIn(low.substring(m.range.last + 1)) || (a == 24 && b == 7)) return@forEach
            numeric(m.range, a, b, null, today, order)?.let(::add)
        }
    }

    /** "a/b(/y)": read in [order], or the other way when only that one is a real date ("13/12", "12/25"). */
    private fun numeric(range: IntRange, a: Int, b: Int, year: Int?, today: LocalDate, order: DateOrder): DatePart? {
        val (day, month) = if (order == DateOrder.DMY) a to b else b to a
        val primary = dated(range, day, month, year, today, confident = year != null)
        val swapped = if (a != b) dated(range, month, day, year, today, confident = year != null) else null
        return when {
            primary != null && swapped != null && year == null -> primary.copy(alternatives = listOf(swapped.date))
            primary != null -> primary
            else -> swapped
        }
    }

    /** A day and month, with the year given or the next time it comes round (today counts). Null if no such day. */
    private fun dated(range: IntRange, day: Int, month: Int, year: Int?, today: LocalDate, confident: Boolean): DatePart? {
        if (month !in 1..12 || day !in 1..31) return null
        if (year != null) {
            if (year !in 1900..2200) return null
            val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
            return DatePart(range, d, confident = confident)
        }
        val md = runCatching { MonthDay.of(month, day) }.getOrNull() ?: return null
        // 29 Feb goes to the next leap year that has it.
        var y = today.year
        repeat(9) {
            if (md.isValidYear(y)) {
                val d = md.atYear(y)
                if (!d.isBefore(today)) return DatePart(range, d, confident = confident)
            }
            y++
        }
        return null
    }

    private fun relativeSpans(low: String, reference: ZonedDateTime): List<Part> = RELATIVE_SPAN.findAll(low).mapNotNull { m ->
        val n = m.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[m.groupValues[1]] ?: return@mapNotNull null
        val unit = m.groupValues[2]
        when {
            unit.startsWith("min") -> reference.plusMinutes(n.toLong()).let { DatePart(m.range, it.toLocalDate(), time = it.toLocalTime().truncatedTo(ChronoUnit.MINUTES)) }
            unit.startsWith("h") -> reference.plusHours(n.toLong()).let { DatePart(m.range, it.toLocalDate(), time = it.toLocalTime().truncatedTo(ChronoUnit.MINUTES)) }
            unit.startsWith("d") -> DatePart(m.range, reference.toLocalDate().plusDays(n.toLong()))
            else -> DatePart(m.range, reference.toLocalDate().plusWeeks(n.toLong()))
        }
    }.toList()

    // ---- times ----

    private fun meridiem(s: String?): Meridiem? = when {
        s.isNullOrEmpty() -> null
        s.startsWith("a") -> Meridiem.AM
        else -> Meridiem.PM
    }

    private fun to24(hour: Int, m: Meridiem?): Int = when (m) {
        null -> hour
        Meridiem.AM -> if (hour == 12) 0 else hour
        Meridiem.PM -> if (hour == 12) 12 else hour + 12
    }

    private fun times(low: String): List<Part> = buildList {
        TIME_RANGE.findAll(low).forEach { m ->
            val h1 = m.groupValues[1].toInt()
            val m1 = m.groups[2]?.value?.toInt() ?: 0
            val mer1 = meridiem(m.groups[3]?.value)
            val h2 = m.groupValues[4].toInt()
            val m2 = m.groups[5]?.value?.toInt() ?: 0
            val mer2 = meridiem(m.groups[6]?.value)
            val clocks = m.groups[2] != null && m.groups[5] != null && m.value.contains(':')
            if (mer1 == null && mer2 == null && !clocks) return@forEach // "2-1", "3-4 people"
            if (m1 !in 0..59 || m2 !in 0..59) return@forEach
            val twelve = mer1 != null || mer2 != null
            if (twelve && (h1 !in 1..12 || h2 !in 1..12)) return@forEach
            if (!twelve && (h1 !in 0..23 || h2 !in 0..23)) return@forEach
            // "7-9pm": the end's am/pm goes to the start when the start has none and comes first.
            val startMer = mer1 ?: mer2?.takeIf { h1 % 12 < h2 % 12 } ?: mer2?.let { if (it == Meridiem.PM) Meridiem.AM else Meridiem.PM }
            val endMer = mer2 ?: mer1?.let { if (h2 % 12 > h1 % 12) it else if (it == Meridiem.AM) Meridiem.PM else Meridiem.AM }
            val start = to24(h1, if (twelve) startMer else null)
            val end = LocalTime.of(to24(h2, if (twelve) endMer else null), m2)
            add(TimePart(m.range, TimeKind.MERIDIEM, start, m1, end = end))
        }
        TIME_MERIDIEM.findAll(low).forEach { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groups[2]?.value?.toInt() ?: 0
            if (h in 1..12 && min in 0..59) add(TimePart(m.range, TimeKind.MERIDIEM, h, min, meridiem(m.groupValues[3])))
        }
        TIME_CLOCK.findAll(low).forEach { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) add(TimePart(m.range, TimeKind.CLOCK, h, min))
        }
        TIME_WORD.findAll(low).forEach { m ->
            add(if (m.groupValues[1] == "midnight") TimePart(m.range, TimeKind.MIDNIGHT, 0, 0) else TimePart(m.range, TimeKind.NOON, 12, 0))
        }
        (TIME_BARE.findAll(low) + TIME_OCLOCK.findAll(low)).forEach { m ->
            val h = m.groupValues[1].toInt()
            if (h in 1..23) add(TimePart(m.range, TimeKind.BARE, h, 0))
        }
    }

    /** "morning" before or after a time, within a few words: an am/pm hint, never a time of its own. */
    private fun hintAround(low: String, part: TimePart): Meridiem? {
        val before = low.substring(maxOf(0, part.range.first - 14), part.range.first)
        val after = low.substring(part.range.last + 1, minOf(low.length, part.range.last + 16))
        val near = HINT_BEFORE.find(before)?.value ?: HINT_AFTER.find(after)?.value
        return near?.let { daypart(it) }
    }

    private fun daypart(s: String): Meridiem? = when {
        s.contains("tonight") || s.contains("tonite") -> Meridiem.PM
        else -> when (DAYPART.find(s)?.value) {
            "morning" -> Meridiem.AM
            "afternoon", "evening", "night" -> Meridiem.PM
            else -> null
        }
    }

    private fun read(part: TimePart, hint: Meridiem?): ReadTime? = when (part.kind) {
        TimeKind.MERIDIEM -> if (part.end != null) {
            ReadTime(LocalTime.of(part.hour, part.minute), null, confident = true, nextDay = false)
        } else {
            ReadTime(LocalTime.of(to24(part.hour, part.meridiem), part.minute), null, confident = true, nextDay = false)
        }
        // "7:30 tonight" is 19:30; otherwise a clock time is read as written (24-hour).
        TimeKind.CLOCK -> ReadTime(LocalTime.of(if (hint == Meridiem.PM && part.hour in 1..11) part.hour + 12 else part.hour, part.minute), null, confident = true, nextDay = false)
        TimeKind.NOON -> ReadTime(LocalTime.NOON, null, confident = true, nextDay = false)
        TimeKind.MIDNIGHT -> ReadTime(LocalTime.MIDNIGHT, null, confident = true, nextDay = true)
        // A bare "at 8": a daypart decides; otherwise evening, with the morning as the other reading.
        TimeKind.BARE -> when {
            part.hour >= 13 -> ReadTime(LocalTime.of(part.hour, 0), null, confident = false, nextDay = false)
            part.hour == 12 -> ReadTime(LocalTime.NOON, null, confident = false, nextDay = false)
            hint != null -> ReadTime(LocalTime.of(to24(part.hour, hint), 0), null, confident = true, nextDay = false)
            else -> ReadTime(LocalTime.of(part.hour + 12, 0), LocalTime.of(part.hour, 0), confident = false, nextDay = false)
        }
    }

    private fun joinable(low: String, a: Part, b: Part): Boolean {
        val gap = low.substring(a.range.last + 1, b.range.first)
        return gap.length <= MAX_GAP && GAP.matches(gap)
    }

    private fun combine(low: String, d: DatePart, t: TimePart): DateHit? {
        val hint = d.hint ?: hintAround(low, t)
        val r = read(t, hint) ?: return null
        val date = if (r.nextDay) d.date.plusDays(1) else d.date
        val alternatives = buildList {
            d.alternatives.forEach { add((if (r.nextDay) it.plusDays(1) else it) to r.time) }
            r.alternative?.let { add(date to it) }
        }
        val range = minOf(d.range.first, t.range.first)..maxOf(d.range.last, t.range.last)
        return DateHit(range, date, r.time, t.end, confident = d.confident && r.confident, alternatives = alternatives)
    }

    /** A time with no date: today if it is still to come, else tomorrow. Midnight is always the coming one. */
    private fun timeOnly(low: String, t: TimePart, reference: ZonedDateTime): DateHit? {
        val r = read(t, hintAround(low, t)) ?: return null
        val now = reference.toLocalTime()
        fun dayFor(time: LocalTime) = if (r.nextDay || !time.isAfter(now)) reference.toLocalDate().plusDays(1) else reference.toLocalDate()
        return DateHit(
            t.range, dayFor(r.time), r.time, t.end, confident = r.confident, dateGuessed = true,
            alternatives = listOfNotNull(r.alternative?.let { dayFor(it) to it }),
        )
    }
}
