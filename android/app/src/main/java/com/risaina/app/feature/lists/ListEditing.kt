package com.risaina.app.feature.lists

import com.risaina.app.core.data.TodoAppend
import com.risaina.app.core.data.lists.DocItem
import com.risaina.app.core.data.lists.ListDoc
import com.risaina.app.core.data.lists.ListLines
import com.risaina.app.core.model.ListLimits
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Where the cursor goes after an edit: a row (by id) and a position in its text. */
data class Caret(val rowId: String, val at: Int)

/** An edit's result: the new doc, where the cursor goes, and how many pasted lines did not fit in 200 rows. */
data class RowEdit(val doc: ListDoc, val caret: Caret? = null, val leftOut: Int = 0)

/** A deleted row and where it was, for Undo. */
data class RemovedRow(val index: Int, val item: DocItem)

/**
 * The list editor's operations on rows, as pure functions (ListEditingTest). Rows are found by id,
 * never by an index captured earlier: a merge may have moved them meanwhile.
 */
object RowEdits {
    /**
     * Row [rowId] now reads [text]. An Enter typed in it splits the row at the cursor and moves to
     * the new row; pasted lines become rows there (bullets, numbers and boxes stripped, long lines
     * cut at a space), up to 200 rows in all. A single character past 300 is not taken. Only a line
     * break typed or pasted now counts: a row that already holds one (saved by an older app, or a
     * chat message added as one item) is edited like any other.
     */
    fun typed(doc: ListDoc, rowId: String, text: String, newId: () -> String = TodoAppend::newId): RowEdit {
        val i = doc.items.indexOfFirst { it.id == rowId }
        if (i < 0) return RowEdit(doc)
        val row = doc.items[i]
        val old = row.text
        if (isPlain(old, text)) return RowEdit(doc.withRow(i, row.copy(text = text)))
        val p = commonPrefix(old, text)
        val q = commonSuffix(old, text, p)
        val before = text.substring(0, p)
        val inserted = text.substring(p, text.length - q)
        val after = text.substring(text.length - q)
        if (inserted.endsWith('\n') && inserted.count { it == '\n' } == 1) {
            // An Enter (perhaps with the word the keyboard corrected as it went in): split there.
            val head = before + inserted.dropLast(1)
            if (doc.items.size >= ListLimits.MAX_ITEMS) return RowEdit(doc.withRow(i, row.copy(text = head + after)), Caret(rowId, head.length), leftOut = 1)
            val next = DocItem(newId(), after)
            val items = doc.items.toMutableList().apply { set(i, row.copy(text = head)); add(i + 1, next) }
            return RowEdit(doc.copy(items = items), Caret(next.id, 0))
        }
        // One more character in a full row.
        if ('\n' !in inserted && inserted.length <= 1) return RowEdit(doc.withRow(i, row.copy(text = old)), Caret(rowId, p))
        // Pasted lines lose their bullets, numbers and boxes; the first joins what was before the
        // cursor and the last keeps what was after it. Blank lines are dropped.
        val texts: List<String> = if ('\n' in inserted) {
            val segs = inserted.split('\n').map { ListLines.parseLine(it)?.text ?: "" }.toMutableList()
            segs[0] = before + segs[0]
            segs[segs.lastIndex] = segs.last() + after
            segs.filter { it.isNotBlank() }.ifEmpty { listOf(before + after) }
        } else listOf(before + inserted + after)
        val pieces = texts.flatMap { t -> if (t.length > ListLimits.MAX_ITEM_TEXT) ListLines.splitText(t).ifEmpty { listOf("") } else listOf(t) }
        // This row is replaced by the pieces: room for the rest up to 200.
        val kept = pieces.take((ListLimits.MAX_ITEMS - doc.items.size + 1).coerceAtLeast(1))
        val rows = kept.mapIndexed { n, t -> if (n == 0) row.copy(text = t) else DocItem(newId(), t) }
        val items = doc.items.toMutableList().apply { removeAt(i); addAll(i, rows) }
        val last = rows.last()
        // The cursor ends where the pasted text ends (before what followed it), or at the end when rows were left out.
        val caretAt = if (kept.size == pieces.size) last.text.length - after.length else last.text.length
        return RowEdit(doc.copy(items = items), Caret(last.id, caretAt.coerceIn(0, last.text.length)), leftOut = pieces.size - kept.size)
    }

    /**
     * Whether [old] → [text] stays one row as typed: what changed holds no line break, and the row
     * stays within 300 characters (or does not grow). Line breaks already in the row are not a change.
     */
    fun isPlain(old: String, text: String): Boolean {
        if (text.length > ListLimits.MAX_ITEM_TEXT && text.length > old.length) return false
        val p = commonPrefix(old, text)
        val q = commonSuffix(old, text, p)
        return text.indexOf('\n', p).let { it < 0 || it >= text.length - q }
    }

    /**
     * Backspace at the very start of a row: an empty row goes; otherwise its text joins the end of
     * the row above (when that fits in 300). The cursor goes to where they meet.
     */
    fun joinWithPrevious(doc: ListDoc, rowId: String): RowEdit {
        val i = doc.items.indexOfFirst { it.id == rowId }
        if (i == 0 && doc.items.size > 1 && doc.items[0].text.isEmpty()) {
            return RowEdit(doc.copy(items = doc.items.drop(1)), Caret(doc.items[1].id, 0))
        }
        if (i <= 0) return RowEdit(doc)
        val prev = doc.items[i - 1]
        val row = doc.items[i]
        if (prev.text.length + row.text.length > ListLimits.MAX_ITEM_TEXT) return RowEdit(doc, Caret(prev.id, prev.text.length))
        val items = doc.items.toMutableList().apply { set(i - 1, prev.copy(text = prev.text + row.text)); removeAt(i) }
        return RowEdit(doc.copy(items = items), Caret(prev.id, prev.text.length))
    }

    /** "+ Add item": an empty row at the end (or the empty last row again), with the cursor in it. */
    fun addRow(doc: ListDoc, newId: () -> String = TodoAppend::newId): RowEdit {
        doc.items.lastOrNull()?.takeIf { it.text.isEmpty() }?.let { return RowEdit(doc, Caret(it.id, 0)) }
        if (doc.items.size >= ListLimits.MAX_ITEMS) return RowEdit(doc, leftOut = 1)
        val row = DocItem(newId(), "")
        return RowEdit(doc.copy(items = doc.items + row), Caret(row.id, 0))
    }

    fun remove(doc: ListDoc, rowId: String): Pair<ListDoc, RemovedRow?> {
        val i = doc.items.indexOfFirst { it.id == rowId }
        if (i < 0) return doc to null
        return doc.copy(items = doc.items.toMutableList().apply { removeAt(i) }) to RemovedRow(i, doc.items[i])
    }

    /** Undo of [remove]: the row comes back where it was (or at the end, if the list got shorter). */
    fun restore(doc: ListDoc, removed: RemovedRow): ListDoc {
        if (doc.items.any { it.id == removed.item.id }) return doc
        val at = removed.index.coerceIn(0, doc.items.size)
        return doc.copy(items = doc.items.toMutableList().apply { add(at, removed.item) })
    }

    fun move(doc: ListDoc, rowId: String, to: Int): ListDoc {
        val i = doc.items.indexOfFirst { it.id == rowId }
        if (i < 0) return doc
        val items = doc.items.toMutableList()
        val row = items.removeAt(i)
        items.add(to.coerceIn(0, items.size), row)
        return doc.copy(items = items)
    }

    /** A tick carries this phone's stamp until the server's comes back. */
    fun toggle(doc: ListDoc, rowId: String, done: Boolean, me: String?, now: Long): ListDoc =
        doc.copy(items = doc.items.map { if (it.id == rowId) it.copy(done = done, doneBy = if (done) me else null, doneAt = if (done) now else null) else it })

    fun uncheckAll(doc: ListDoc): ListDoc = doc.copy(items = doc.items.map { if (it.done) it.copy(done = false, doneBy = null, doneAt = null) else it })

    fun deleteTicked(doc: ListDoc): ListDoc = doc.copy(items = doc.items.filterNot { it.done })

    private fun ListDoc.withRow(i: Int, row: DocItem) = copy(items = items.toMutableList().apply { set(i, row) })

    private fun commonPrefix(a: String, b: String): Int {
        var n = 0
        while (n < a.length && n < b.length && a[n] == b[n]) n++
        return n
    }

    private fun commonSuffix(a: String, b: String, prefix: Int): Int {
        var n = 0
        while (n < a.length - prefix && n < b.length - prefix && a[a.length - 1 - n] == b[b.length - 1 - n]) n++
        return n
    }
}

/**
 * A note's text editing: Enter after a "• ", "- ", "☐ " or "3. " line starts the next one; Enter on
 * an empty one ends the list. The text stays plain, so an older app shows it unchanged. Pure.
 */
object NoteEdits {
    data class Edit(val text: String, val cursor: Int, val selectionEnd: Int = cursor)

    private val NUMBERED = Regex("^(\\s*)(\\d{1,3})([.)]) ")
    private val BULLETED = Regex("^(\\s*)([-*•]|☐|☑) ")

    /** [new] typed over [old], cursor at [cursor]: list continuation when the change was one Enter. */
    fun typed(old: String, new: String, cursor: Int): Edit {
        if (new.length != old.length + 1 || cursor < 1 || cursor > new.length || new[cursor - 1] != '\n') return Edit(new, cursor)
        if (old.substring(0, cursor - 1) != new.substring(0, cursor - 1)) return Edit(new, cursor)
        val lineStart = new.lastIndexOf('\n', cursor - 2) + 1
        val line = new.substring(lineStart, cursor - 1)
        val next: String
        val marker: String
        val num = NUMBERED.find(line)
        val bul = BULLETED.find(line)
        when {
            num != null -> { marker = num.value; next = num.groupValues[1] + (num.groupValues[2].toInt() + 1) + num.groupValues[3] + " " }
            bul != null -> { marker = bul.value; next = bul.groupValues[1] + (if (bul.groupValues[2] == "☑") "☐" else bul.groupValues[2]) + " " }
            else -> return Edit(new, cursor)
        }
        if (line == marker) {
            // Enter on an empty item ends the list: the marker goes and the cursor stays on that line.
            val text = new.substring(0, lineStart) + new.substring(cursor)
            return Edit(text, lineStart)
        }
        val text = new.substring(0, cursor) + next + new.substring(cursor)
        return Edit(text, cursor + next.length)
    }

    enum class Kind { BULLET, NUMBER }

    /**
     * The "• List" / "1. List" buttons: the lines in the selection (or the cursor's line) become
     * bullets or numbers, or stop being them when they all already are.
     */
    fun toggleList(text: String, selStart: Int, selEnd: Int, kind: Kind): Edit {
        val start = text.lastIndexOf('\n', minOf(selStart, selEnd) - 1) + 1
        val endNl = text.indexOf('\n', maxOf(selStart, selEnd)).let { if (it < 0) text.length else it }
        val lines = text.substring(start, endNl).split('\n')
        val has = when (kind) { Kind.BULLET -> BULLETED; Kind.NUMBER -> NUMBERED }
        val allMarked = lines.all { has.containsMatchIn(it) }
        val out = lines.mapIndexed { n, l ->
            val bare = l.replace(NUMBERED, "$1").replace(BULLETED, "$1")
            when {
                allMarked -> bare
                kind == Kind.BULLET -> "• $bare"
                else -> "${n + 1}. $bare"
            }
        }.joinToString("\n")
        val text2 = text.substring(0, start) + out + text.substring(endNl)
        return Edit(text2, start + out.length)
    }

    /** "Add to calendar" from a note: the selected text, or the cursor's line, without its marker. */
    fun calendarTitle(text: String, selStart: Int, selEnd: Int): String? {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val raw = if (a != b) text.substring(a, b) else {
            val s = text.lastIndexOf('\n', a - 1) + 1
            val e = text.indexOf('\n', a).let { if (it < 0) text.length else it }
            text.substring(s, e)
        }
        val firstLine = raw.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val clean = ListLines.parseLine(firstLine)?.text ?: return null
        return TodoAppend.cut(clean, CALENDAR_TITLE_MAX)
    }

    private const val CALENDAR_TITLE_MAX = 120
}

/** Pasting several lines into a list creates one row per line (bullets, numbers and [ ] stripped, long lines cut). */
object PasteParser {
    fun split(text: String): List<String> = ListLines.toRows(text, ListLimits.MAX_ITEMS).rows.map { it.text }
}

/** "3m", "2h", "yesterday", "Mon", "12 Oct": how long ago, short enough for a line under a row. */
object ShortAgo {
    fun format(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val mins = (now - at) / 60_000
        if (mins < 1) return "now"
        if (mins < 60) return "${mins}m"
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            days == 0L -> "${mins / 60}h"
            days == 1L -> "yesterday"
            days < 7 -> day.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
            day.year == today.year -> day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            else -> day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
        }
    }
}

/** Who did what, in the couple's words: "You" for this phone's person, the partner's name, or nothing when unknown. */
data class People(val me: String?, val partnerId: String?, val partnerName: String?) {
    fun nameOf(userId: String?): String? = when {
        userId == null -> null
        userId == me -> "You"
        userId == partnerId -> partnerName?.takeIf { it.isNotBlank() } ?: "Your partner"
        else -> null
    }

    /** "✓ You · 3m" / "✓ Raina · yesterday" under a ticked row; null when the server never said who. */
    fun tickLine(item: DocItem, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (!item.done) return null
        val who = nameOf(item.doneBy) ?: return null
        return "✓ $who" + (item.doneAt?.let { " · " + ShortAgo.format(it, now, zone) } ?: "")
    }

    /** The partner added this row (its small initial badge). */
    fun addedByPartner(item: DocItem): Boolean = partnerId != null && item.addedBy == partnerId
}

/** Ready-made lists that suit two people apart. Picking one creates a shared to-do list with these rows. */
object ListTemplates {
    data class Template(val emoji: String, val title: String, val items: List<String>)
    val all = listOf(
        Template("✈️", "When we finally meet", listOf("Long hug at the gate", "Our first meal together", "Watch the sunset", "Take a photo together")),
        Template("🎬", "Watch together (on call)", listOf("A movie we've both never seen", "Rewatch our favourite", "A documentary")),
        Template("🗺️", "Places we'll go", listOf("A beach", "A mountain town", "A city neither of us knows")),
        Template("🎁", "Gift ideas for each other", emptyList()),
        Template("🏡", "Our future home", listOf("Lots of plants", "A reading corner")),
        Template("💬", "Questions to ask each other", listOf("What made you smile today?", "What do you miss most about me?", "Where shall we be in five years?")),
    )
}
