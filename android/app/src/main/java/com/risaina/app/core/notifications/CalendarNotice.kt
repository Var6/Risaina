package com.risaina.app.core.notifications

import com.risaina.app.core.domain.CalendarRules
import com.risaina.app.core.domain.CalendarText
import com.risaina.app.core.model.CalendarEntry
import java.time.ZoneId
import java.util.Locale

/** The words of "Bea added “Dinner” to your calendar". Pure (CalendarNoticeTest). */
object CalendarNotice {
    /** [partner] is their display name; only the first name is used. Previews off: no title in it. */
    fun title(partner: String, entryTitle: String, updated: Boolean, preview: Boolean): String {
        val first = partner.trim().substringBefore(" ").ifBlank { "Your partner" }
        return when {
            !preview -> "$first added something to your calendar"
            updated -> "$first changed “$entryTitle” in your calendar"
            else -> "$first added “$entryTitle” to your calendar"
        }
    }

    /** "Sat 14 Nov, 8:00 pm" on this phone's clock, with the partner's when it tells another time. */
    fun line(e: CalendarEntry, zone: ZoneId, partnerZone: ZoneId?, locale: Locale = Locale.getDefault()): String {
        val local = CalendarRules.localStart(e, e.date, zone)
        val day = CalendarText.dayTime(local.toLocalDate(), local.toLocalTime().takeIf { e.time != null }, locale)
        val theirs = CalendarRules.partnerStart(e, e.date, zone, partnerZone)
        return day + (theirs?.let { " (${CalendarText.time(it.toLocalTime(), locale)} for them)" } ?: "")
    }
}
