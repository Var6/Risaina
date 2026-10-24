package com.risaina.app.core.domain

import com.risaina.app.core.model.QuietHours
import java.time.Instant
import java.time.LocalTime

/**
 * Decides whether an instant falls inside a quiet-hours window. Handles windows that cross
 * midnight (22:30 → 07:30) and equal start/end (treated as disabled). Pure and testable.
 */
object QuietHoursPolicy {
    fun isQuiet(quiet: QuietHours, at: Instant): Boolean {
        if (!quiet.enabled || quiet.start == quiet.end) return false
        val local = at.atZone(quiet.zone).toLocalTime()
        return isWithin(local, quiet.start, quiet.end)
    }

    fun isWithin(time: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        if (start.isBefore(end)) {
            !time.isBefore(start) && time.isBefore(end)
        } else {
            // crosses midnight: quiet from start..23:59:59 and 00:00..end
            !time.isBefore(start) || time.isBefore(end)
        }

    /** Should a notification make sound/vibration right now? */
    fun shouldAlert(quiet: QuietHours, allowInQuiet: Boolean, at: Instant): Boolean =
        allowInQuiet || !isQuiet(quiet, at)
}
