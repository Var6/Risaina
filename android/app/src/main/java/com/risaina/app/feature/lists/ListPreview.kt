package com.risaina.app.feature.lists

import com.risaina.app.core.data.lists.DocItem
import com.risaina.app.core.data.lists.ListLines
import com.risaina.app.core.data.lists.ListSyncEngine
import com.risaina.app.core.data.lists.ListWire
import com.risaina.app.core.data.lists.OverviewEntry
import com.risaina.app.core.model.ListMarker
import java.time.ZoneId

enum class ListFilter(val label: String) { ALL("All"), TODOS("To-dos"), NOTES("Notes") }

/** What an unsent change says on its card. */
enum class DraftChip(val label: String) {
    SAVING("Saving…"),
    ON_PHONE("On this phone"),
    NOT_SAVED("Not saved – tap to retry"),
    DELETED("Deleted – tap to choose"),
}

data class PreviewLine(val prefix: String, val text: String, val done: Boolean)

/** One overview card, ready to draw. */
data class ListCard(
    val id: String,
    val title: String,
    val glyph: String,
    /** The format in words, for TalkBack ("To-do list"). */
    val kindLabel: String,
    val isNote: Boolean,
    val pinned: Boolean,
    val lines: List<PreviewLine>,
    val more: Int,
    val done: Int,
    val total: Int,
    val showProgress: Boolean,
    val footer: String,
    val chip: DraftChip?,
)

/** Overview cards (lists.md E) as pure functions (ListPreviewTest). */
object ListPreview {
    const val PREVIEW_ROWS = 4
    /** The search field shows once there are more lists than this. */
    const val SEARCH_AFTER = 6
    private const val FOOTER_ITEM_MAX = 24

    fun card(e: OverviewEntry, people: People, now: Long, zone: ZoneId = ZoneId.systemDefault()): ListCard {
        val d = e.doc
        val marker = ListMarker.fromWire(d.marker)
        val (glyph, label) = when {
            d.isNote -> "📝" to "Note"
            d.checkboxes -> "☑️" to "To-do list"
            marker == ListMarker.NUMBER -> "🔢" to "Numbered list"
            marker == ListMarker.BULLET -> "📋" to "Bulleted list"
            else -> "📋" to "List"
        }
        val rows = d.items.filter { it.text.isNotBlank() }
        val lines: List<PreviewLine>
        val total: Int
        if (d.isNote) {
            val bodyLines = d.body.lines().filter { it.isNotBlank() }
            lines = bodyLines.take(PREVIEW_ROWS).map { PreviewLine("", it.trim(), false) }
            total = bodyLines.size
        } else {
            lines = rows.take(PREVIEW_ROWS).mapIndexed { i, r -> PreviewLine(ListLines.prefix(i, r.done, marker, d.checkboxes).trim(), r.text.trim(), r.done && d.checkboxes) }
            total = rows.size
        }
        return ListCard(
            id = e.id, title = ListWire.title(d), glyph = glyph, kindLabel = label, isNote = d.isNote, pinned = d.pinned,
            lines = lines, more = (total - lines.size).coerceAtLeast(0),
            done = rows.count { it.done }, total = rows.size, showProgress = !d.isNote && d.checkboxes && rows.isNotEmpty(),
            footer = footer(e, people, now, zone), chip = chip(e),
        )
    }

    /** The newest thing the partner did ('Raina ticked “Beach” · 5m', 'Raina edited · 5m'), else when it last changed. */
    fun footer(e: OverviewEntry, people: People, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val partner = people.partnerId
        val tick: Pair<DocItem, Long>? = if (partner == null) null else e.doc.items
            .filter { it.done && it.doneBy == partner && it.doneAt != null }
            .maxByOrNull { it.doneAt!! }?.let { it to it.doneAt!! }
        val edit = e.server?.takeIf { partner != null && it.updatedBy == partner }?.updatedAt
        val name = people.nameOf(partner)
        return when {
            tick != null && (edit == null || tick.second >= edit) -> "$name ticked “${short(tick.first.text)}” · ${ShortAgo.format(tick.second, now, zone)}"
            edit != null -> "$name edited · ${ShortAgo.format(edit, now, zone)}"
            e.changedAt > 0 -> "Edited · ${ShortAgo.format(e.changedAt, now, zone)}"
            else -> ""
        }
    }

    fun chip(e: OverviewEntry): DraftChip? = when (e.draftState) {
        null -> null
        ListSyncEngine.STATE_ORPHANED -> DraftChip.DELETED
        ListSyncEngine.STATE_FAILED -> DraftChip.NOT_SAVED
        else -> if (e.draftError == "offline") DraftChip.ON_PHONE else DraftChip.SAVING
    }

    fun matches(e: OverviewEntry, filter: ListFilter, query: String): Boolean {
        val kindOk = when (filter) { ListFilter.ALL -> true; ListFilter.TODOS -> !e.doc.isNote; ListFilter.NOTES -> e.doc.isNote }
        if (!kindOk) return false
        val q = query.trim()
        if (q.isEmpty()) return true
        val d = e.doc
        return ListWire.title(d).contains(q, ignoreCase = true) || d.body.contains(q, ignoreCase = true) || d.items.any { it.text.contains(q, ignoreCase = true) }
    }

    private fun short(text: String): String = text.trim().let { if (it.length <= FOOTER_ITEM_MAX) it else it.take(FOOTER_ITEM_MAX - 1).trimEnd() + "…" }
}
