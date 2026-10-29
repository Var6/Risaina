package com.risaina.app.core.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Whether the two of you live by one clock, as pure functions (TimeZonesTest). On one clock there is
 * only one time: the app asks nothing about "my time" or "their time" and shows no second clock.
 *
 * - A partner whose zone is not shared (or not known on this phone) counts as one clock: there is
 *   nothing to convert to, so nothing is asked.
 * - Two names for one zone (Asia/Kolkata and Asia/Calcutta) are one clock, and so are two zones that
 *   tell the same time all year (Europe/London and Europe/Lisbon).
 * - Zones that agree only part of the year (America/Phoenix and America/Denver) are one clock only
 *   at the instants and on the days they agree.
 */
object TimeZones {
    /** How far a screen looks ahead ([sameClockAllYear]): a whole round of clock changes. */
    private val YEAR: Duration = Duration.ofDays(366)

    /** [id] as a zone; null when it is missing or this phone does not know it. */
    fun zoneOf(id: String?): ZoneId? = id?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    /** Both clocks tell the same time at [at]. */
    fun sameClock(mine: ZoneId, partner: ZoneId?, at: Instant): Boolean = sameClock(mine, partner, at, at)

    /** Both clocks tell the same time at every instant from [from] to [to], both included. */
    fun sameClock(mine: ZoneId, partner: ZoneId?, from: Instant, to: Instant): Boolean {
        if (partner == null || mine.normalized() == partner.normalized() || mine.rules == partner.rules) return true
        // An offset only changes at a transition: the start and every transition of either zone in
        // the span are all the instants worth checking.
        var t = from
        while (true) {
            if (mine.rules.getOffset(t) != partner.rules.getOffset(t)) return false
            val next = listOfNotNull(mine.rules.nextTransition(t)?.instant, partner.rules.nextTransition(t)?.instant).minOrNull()
            if (next == null || next.isAfter(to)) return true
            t = next
        }
    }

    /** Both clocks tell the same time all through this phone's days [from] to [to]. */
    fun sameClock(mine: ZoneId, partner: ZoneId?, from: LocalDate, to: LocalDate = from): Boolean =
        sameClock(mine, partner, from.atStartOfDay(mine).toInstant(), to.plusDays(1).atStartOfDay(mine).toInstant().minusNanos(1))

    /**
     * For a whole screen or setting ("show their clock at all"): one clock now and through the next
     * year's clock changes. Zones that part ways for some weeks of the year are not one clock here.
     */
    fun sameClockAllYear(mine: ZoneId, partner: ZoneId?, now: Instant): Boolean = sameClock(mine, partner, now, now.plus(YEAR))
}
