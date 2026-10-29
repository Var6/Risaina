package com.risaina.app.feature.calendar

import com.risaina.app.core.domain.DateDetector
import com.risaina.app.core.domain.DateHit
import com.risaina.app.core.domain.DateOrder
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarSourceKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * What the entry editor opens with, from the chat or a note. The navigation layer turns it into the
 * CalendarEntryEdit route (strings on the wire of the back stack).
 */
data class CalendarPrefill(
    val title: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val kind: CalendarKind = CalendarKind.EVENT,
    val sourceKind: CalendarSourceKind? = null,
    val sourceMessageId: String? = null,
    val sourceListId: String? = null,
    val sourceItemId: String? = null,
)

/** Builds prefills from text. Pure (CalendarPrefillTest). */
object CalendarPrefills {
    /**
     * The title around the first date in [text] and that date, read relative to [reference] (a
     * message's own time). With no date the whole text is the title and the date is left for the
     * person to pick. "Sat?" leaves no words: [fallbackTitle] ("Plan with Bea") is used.
     */
    fun fromText(
        text: String,
        reference: ZonedDateTime,
        order: DateOrder,
        fallbackTitle: String,
        kind: CalendarKind = CalendarKind.EVENT,
        messageId: String? = null,
    ): CalendarPrefill {
        val hit = DateDetector.find(text, reference, order).firstOrNull()
        val title = if (hit != null) DateDetector.suggestTitle(text, listOf(hit)).ifBlank { fallbackTitle } else plainTitle(text, fallbackTitle)
        return CalendarPrefill(
            title = title, date = hit?.date, time = hit?.time, kind = kind,
            sourceKind = messageId?.let { CalendarSourceKind.MESSAGE }, sourceMessageId = messageId,
        )
    }

    /** The first date in [text] only when the parser is sure of it: the composer's chip and "Make it a to-do". */
    fun confidentHit(text: String, reference: ZonedDateTime, order: DateOrder): DateHit? =
        DateDetector.find(text, reference, order).firstOrNull()?.takeIf { it.confident }

    /** The first line, at most 120 characters: a long message is still a usable title. */
    fun plainTitle(text: String, fallback: String): String {
        val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isEmpty()) return fallback
        return if (line.length <= 120) line else line.take(119).trimEnd() + "…"
    }
}
