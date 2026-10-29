package com.risaina.app.feature.calendar

import com.risaina.app.core.domain.DateDetector
import com.risaina.app.core.domain.DateHit
import com.risaina.app.core.domain.DateOrder
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarSource
import com.risaina.app.core.model.CalendarSourceKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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

    /**
     * The source a route names, only in a shape the server takes (it refuses the whole save
     * otherwise): a message or a list needs its server id, an item its own id too. A note or list
     * not sent yet has no server id, so the entry is made without a link; an item without an id
     * still links its list.
     */
    fun source(kind: String?, messageId: String?, listId: String?, itemId: String?): CalendarSource? {
        fun serverId(id: String?) = id?.takeIf { SERVER_ID.matches(it) }
        return when (kind?.let(CalendarSourceKind::fromWire)) {
            CalendarSourceKind.MESSAGE -> serverId(messageId)?.let { CalendarSource(CalendarSourceKind.MESSAGE, messageId = it) }
            CalendarSourceKind.LIST -> serverId(listId)?.let { CalendarSource(CalendarSourceKind.LIST, listId = it) }
            CalendarSourceKind.LIST_ITEM -> serverId(listId)?.let { list ->
                itemId?.takeIf { ITEM_ID.matches(it) }?.let { CalendarSource(CalendarSourceKind.LIST_ITEM, listId = list, itemId = it) }
                    ?: CalendarSource(CalendarSourceKind.LIST, listId = list)
            }
            else -> null
        }
    }

    /**
     * The anchor zone for a time read from a message: the author's clock, so "8pm" in the partner's
     * message is their evening. Mine when the message is mine, when their zone is not shared, or when
     * both clocks tell the same time at that moment. Null for an all-day prefill.
     */
    fun zoneFor(time: LocalTime?, date: LocalDate?, myZone: ZoneId, authorZone: ZoneId?): String? {
        time ?: return null
        val theirs = authorZone?.takeIf { date != null && EntryRules.zonesDiffer(myZone, it, date, time, anchor = it) }
        return (theirs ?: myZone).id
    }

    /** The server's ids: 24 lowercase hex characters (backend OBJECT_ID_REGEX). */
    private val SERVER_ID = Regex("^[a-f0-9]{24}$")
    /** A list item's id, server-made or made on a phone (backend ItemId). */
    private val ITEM_ID = Regex("^[A-Za-z0-9_-]{1,32}$")
}
