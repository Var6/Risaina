package com.risaina.app.feature.calendar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** What the phone's own calendar app is handed: times in epoch millis, all-day as UTC midnights. */
data class InsertExtras(val beginMillis: Long, val endMillis: Long, val allDay: Boolean, val title: String, val description: String)

/**
 * "Also add to my phone's calendar": Android's new-event screen (ACTION_INSERT on
 * CalendarContract.Events). It needs no permission: the calendar app shows its own screen and the
 * person saves it there. Nothing is read from the phone's calendar.
 */
object CalendarInsert {
    /** Pure (CalendarInsertTest). A timed entry without a length lasts an hour; an all-day one ends the day after its last day. */
    fun extras(title: String, date: LocalDate, endDate: LocalDate?, time: LocalTime?, durationMinutes: Int?, zone: ZoneId, partnerName: String?): InsertExtras {
        val description = "Planned with ${partnerName?.substringBefore(" ")?.ifBlank { null } ?: "your partner"} in Risaina"
        if (time == null) {
            val begin = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = (endDate ?: date).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            return InsertExtras(begin, end, allDay = true, title = title, description = description)
        }
        val begin = ZonedDateTime.of(date, time, zone)
        val end = begin.plusMinutes((durationMinutes ?: 60).toLong())
        return InsertExtras(begin.toInstant().toEpochMilli(), end.toInstant().toEpochMilli(), allDay = false, title = title, description = description)
    }

    /** False when no calendar app can take it. */
    fun launch(context: Context, x: InsertExtras): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, x.title)
                .putExtra(CalendarContract.Events.DESCRIPTION, x.description)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, x.beginMillis)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, x.endMillis)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, x.allDay)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
