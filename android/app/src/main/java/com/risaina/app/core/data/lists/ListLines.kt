package com.risaina.app.core.data.lists

import com.risaina.app.core.data.TodoAppend
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.ListLimits
import com.risaina.app.core.model.ListMarker

/**
 * Lines of text as list rows and back, the way the server's convert does it (backend
 * lists/convert.ts), so a pasted list, a note turned into a checklist here and one turned on the
 * server come out the same. Pure.
 */
object ListLines {
    data class Parsed(val text: String, val done: Boolean)

    // A leading bullet or number needs a space after it, so "-5 °C" and "1.5 kg rice" keep their text.
    private val MARKER = Regex("^(?:[-*•·◦‣]|\\d{1,3}[.)])(?:\\s+|$)")
    private val BOX = Regex("^(\\[\\s?]|\\[[xX]]|☐|☑|☒|✓|✔|✅)(?:\\s+|$)")
    private val TICKED = Regex("^(\\[[xX]]|☑|☒|✓|✔|✅)$")

    /** One line without its bullet, number and checkbox ("[x]" or "☑" ticks it), or null when nothing is left. */
    fun parseLine(line: String): Parsed? {
        var s = line.trim()
        var done = false
        var marker = false
        var box = false
        repeat(2) {
            if (!marker) MARKER.find(s)?.let { s = s.substring(it.value.length); marker = true; return@repeat }
            if (!box) BOX.find(s)?.let { m -> done = TICKED.matches(m.groupValues[1]); s = s.substring(m.value.length); box = true }
        }
        s = s.trim()
        return if (s.isEmpty()) null else Parsed(s, done)
    }

    /** Cuts [text] into pieces of at most [max] UTF-16 units, at the last space when there is one, never inside an emoji. */
    fun splitText(text: String, max: Int = ListLimits.MAX_ITEM_TEXT): List<String> {
        val out = ArrayList<String>()
        var s = text.trim()
        while (s.length > max) {
            var cut = (max downTo 1).firstOrNull { s[it].isWhitespace() } ?: max
            if (cut == max && Character.isHighSurrogate(s[cut - 1])) cut--
            s.substring(0, cut).trim().takeIf { it.isNotEmpty() }?.let(out::add)
            s = s.substring(cut).trim()
        }
        if (s.isNotEmpty()) out += s
        return out
    }

    /**
     * Text (pasted, or a note's body) as rows: each non-blank line without its marker, long lines cut
     * into several. At most [room] rows; [leftOver] counts the lines that did not fit.
     */
    data class Rows(val rows: List<Parsed>, val leftOver: Int, val rest: String)

    fun toRows(text: String, room: Int): Rows {
        val lines = text.split(Regex("\r\n|\r|\n"))
        val rows = ArrayList<Parsed>()
        lines.forEachIndexed { i, line ->
            val p = parseLine(line) ?: return@forEachIndexed
            val pieces = splitText(p.text)
            if (rows.size + pieces.size > room) {
                val rest = lines.drop(i)
                return Rows(rows, rest.count { parseLine(it) != null }, rest.joinToString("\n"))
            }
            pieces.forEach { rows += Parsed(it, p.done) }
        }
        return Rows(rows, 0, "")
    }

    /** The prefix a row is drawn with in a note or a preview: "1. ", "• ", then "☐ " / "☑ ". */
    fun prefix(index: Int, done: Boolean, marker: ListMarker, checkboxes: Boolean): String {
        val lead = when (marker) { ListMarker.NUMBER -> "${index + 1}. "; ListMarker.BULLET -> "• "; else -> "" }
        return lead + if (checkboxes) (if (done) "☑ " else "☐ ") else ""
    }
}

/** Note ↔ checklist for a list not on the server yet, the same way the server converts one. */
object ListConvert {
    fun locally(d: ListDoc, to: ListKind, newId: () -> String = TodoAppend::newId): ListDoc {
        if (d.kind == to.wire) return d
        return if (to == ListKind.CHECKLIST) {
            val r = ListLines.toRows(d.body, (ListLimits.MAX_ITEMS - d.items.size).coerceAtLeast(0))
            d.copy(
                kind = to.wire, body = r.rest, marker = ListMarker.NONE.wire, checkboxes = true,
                items = d.items + r.rows.map { DocItem(newId(), it.text, it.done) },
            )
        } else {
            val marker = ListMarker.fromWire(d.marker)
            var body = d.body.trimEnd()
            val rows = d.items.filter { it.text.isNotBlank() }
            var taken = 0
            for ((i, item) in rows.withIndex()) {
                val line = ListLines.prefix(i, item.done, marker, d.checkboxes) + item.text.trim()
                val next = if (body.isEmpty()) line else "$body\n$line"
                if (next.length > ListLimits.MAX_BODY) break
                body = next
                taken++
            }
            d.copy(kind = to.wire, body = body, items = rows.drop(taken))
        }
    }
}
