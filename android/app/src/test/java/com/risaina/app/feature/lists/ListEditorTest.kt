package com.risaina.app.feature.lists

import com.risaina.app.core.data.lists.DocItem
import com.risaina.app.core.data.lists.ListDoc
import com.risaina.app.core.data.lists.ListSyncEngine
import com.risaina.app.core.data.lists.OverviewEntry
import com.risaina.app.core.data.lists.ServerMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** The editor's row operations, note continuation, the "who ticked" line and the overview cards. */
class ListEditorTest {
    private var n = 0
    private val ids = { "n${n++}" }
    private fun doc(vararg texts: String) = ListDoc(items = texts.mapIndexed { i, t -> DocItem("r$i", t) })
    private fun texts(d: ListDoc) = d.items.map { it.text }

    // ---- rows ----

    @Test fun `typing in a row only changes its text`() {
        val r = RowEdits.typed(doc("Milk"), "r0", "Milky", ids)
        assertEquals(listOf("Milky"), texts(r.doc))
        assertNull(r.caret)
    }

    @Test fun `Enter splits the row at the cursor and moves to the new row`() {
        val r = RowEdits.typed(doc("Buy milk", "Eggs"), "r0", "Buy\n milk", ids)
        assertEquals(listOf("Buy", " milk", "Eggs"), texts(r.doc))
        assertEquals(Caret("n0", 0), r.caret)
        assertEquals("the row keeps its id", "r0", r.doc.items[0].id)
    }

    @Test fun `Enter at the end of a row starts an empty one`() {
        val r = RowEdits.typed(doc("Milk"), "r0", "Milk\n", ids)
        assertEquals(listOf("Milk", ""), texts(r.doc))
        assertEquals(Caret("n0", 0), r.caret)
    }

    @Test fun `Enter that also takes the keyboard's corrected word still splits`() {
        val r = RowEdits.typed(doc("teh"), "r0", "the\n", ids)
        assertEquals(listOf("the", ""), texts(r.doc))
    }

    @Test fun `pasting three lines into row 2 inserts three rows there`() {
        val r = RowEdits.typed(doc("A", "", "C"), "r1", "- milk\n- eggs\n- bread", ids)
        assertEquals(listOf("A", "milk", "eggs", "bread", "C"), texts(r.doc))
        assertEquals(Caret("n1", 5), r.caret)
    }

    @Test fun `a pasted list keeps what was after the cursor on its last row`() {
        val r = RowEdits.typed(doc("Buy  today"), "r0", "Buy 1. milk\n2. eggs today", ids)
        assertEquals(listOf("Buy milk", "eggs today"), texts(r.doc))
        assertEquals(Caret("n0", 4), r.caret)
        val lead = RowEdits.typed(doc("x"), "r0", "x\na\n\nb", ids)
        assertEquals("a paste that starts on a new line does not glue onto the row", listOf("x", "a", "b"), texts(lead.doc))
    }

    @Test fun `a 650-character paste becomes three rows of 300 or fewer`() {
        val long = (1..200).joinToString(" ") { "w$it" }.take(650)
        val r = RowEdits.typed(doc(""), "r0", long, ids)
        assertEquals(3, r.doc.items.size)
        assertTrue(r.doc.items.all { it.text.length <= 300 })
        assertEquals(long.replace(" ", ""), r.doc.items.joinToString("") { it.text.replace(" ", "") })
    }

    @Test fun `a row that already holds a line break takes typing and Backspace like any other`() {
        // Saved by v75 (Enter inside a row) or a chat message added as one item.
        val typed = RowEdits.typed(doc("Milk\nEggs"), "r0", "Milk\nEggsx", ids)
        assertEquals(listOf("Milk\nEggsx"), texts(typed.doc))
        assertNull(typed.caret)
        assertEquals(listOf("Milk\nEgg"), texts(RowEdits.typed(doc("Milk\nEggs"), "r0", "Milk\nEgg", ids).doc))
        assertEquals("typing before the break", listOf("Oat milk\nEggs"), texts(RowEdits.typed(doc("Milk\nEggs"), "r0", "Oat milk\nEggs", ids).doc))
        assertEquals("the break itself can be deleted", listOf("MilkEggs"), texts(RowEdits.typed(doc("Milk\nEggs"), "r0", "MilkEggs", ids).doc))
        // A new Enter in it still splits there.
        val split = RowEdits.typed(doc("Milk\nEggs"), "r0", "Milk\nEggs\n", ids)
        assertEquals(listOf("Milk\nEggs", ""), texts(split.doc))
        assertEquals(Caret("n0", 0), split.caret)
    }

    @Test fun `only a line break typed now or a row growing past 300 leaves the plain path`() {
        assertTrue(RowEdits.isPlain("Milk\nEggs", "Milk\nEggsx"))
        assertTrue(RowEdits.isPlain("Milk\nEggs", "Milk\nEgg"))
        assertTrue(RowEdits.isPlain("Milk", "Milky"))
        assertFalse(RowEdits.isPlain("Milk\nEggs", "Milk\n\nEggs"))
        assertFalse(RowEdits.isPlain("Milk", "Mi\nlk"))
        assertFalse(RowEdits.isPlain("x".repeat(300), "x".repeat(301)))
        assertTrue("shrinking an over-long row is fine", RowEdits.isPlain("x".repeat(302), "x".repeat(301)))
    }

    @Test fun `typing one more character into a full row is not taken`() {
        val full = "x".repeat(300)
        val r = RowEdits.typed(doc(full), "r0", full + "y", ids)
        assertEquals(listOf(full), texts(r.doc))
    }

    @Test fun `rows stop at 200, and the lines left out are counted`() {
        val d = ListDoc(items = List(198) { DocItem("i$it", "x$it") })
        val r = RowEdits.typed(d, "i0", "x0\na\nb\nc\nd", ids)
        assertEquals(200, r.doc.items.size)
        assertEquals(2, r.leftOut)
        val full = ListDoc(items = List(200) { DocItem("i$it", "x") })
        val enter = RowEdits.typed(full, "i3", "x\n", ids)
        assertEquals(200, enter.doc.items.size)
        assertEquals(1, enter.leftOut)
    }

    @Test fun `Backspace at the start joins the row to the one above`() {
        val r = RowEdits.joinWithPrevious(doc("Buy", " milk", "Eggs"), "r1")
        assertEquals(listOf("Buy milk", "Eggs"), texts(r.doc))
        assertEquals(Caret("r0", 3), r.caret)
    }

    @Test fun `Backspace on an empty row deletes it, and on the first row only when it is empty`() {
        assertEquals(listOf("A"), texts(RowEdits.joinWithPrevious(doc("A", ""), "r1").doc))
        assertEquals(listOf("B"), texts(RowEdits.joinWithPrevious(doc("", "B"), "r0").doc))
        assertEquals(listOf("A", "B"), texts(RowEdits.joinWithPrevious(doc("A", "B"), "r0").doc))
    }

    @Test fun `a join that would pass 300 characters only moves the cursor`() {
        val r = RowEdits.joinWithPrevious(doc("a".repeat(200), "b".repeat(200)), "r1")
        assertEquals(2, r.doc.items.size)
        assertEquals(Caret("r0", 200), r.caret)
    }

    @Test fun `Add item makes one empty row and reuses it`() {
        val r = RowEdits.addRow(doc("A"), ids)
        assertEquals(listOf("A", ""), texts(r.doc))
        val again = RowEdits.addRow(r.doc, ids)
        assertEquals(r.doc, again.doc)
        assertEquals(r.caret, again.caret)
    }

    @Test fun `a deleted row comes back where it was`() {
        val (d, removed) = RowEdits.remove(doc("A", "B", "C"), "r1")
        assertEquals(listOf("A", "C"), texts(d))
        assertEquals(listOf("A", "B", "C"), texts(RowEdits.restore(d, removed!!)))
        assertEquals("not twice", listOf("A", "B", "C"), texts(RowEdits.restore(RowEdits.restore(d, removed), removed)))
    }

    @Test fun `move, tick with a local stamp, untick all, delete ticked`() {
        val d = RowEdits.move(doc("A", "B", "C"), "r2", 0)
        assertEquals(listOf("C", "A", "B"), texts(d))
        val t = RowEdits.toggle(d, "r0", true, "me", 42)
        assertEquals(DocItem("r0", "A", true, "me", 42), t.items[1])
        assertFalse(RowEdits.uncheckAll(t).items.any { it.done })
        assertEquals(listOf("C", "B"), texts(RowEdits.deleteTicked(t)))
    }

    // ---- notes ----

    @Test fun `Enter after a bullet or a number starts the next one`() {
        assertEquals(NoteEdits.Edit("• milk\n• ", 9), NoteEdits.typed("• milk", "• milk\n", 7))
        assertEquals(NoteEdits.Edit("1. milk\n2. ", 11), NoteEdits.typed("1. milk", "1. milk\n", 8))
        assertEquals(NoteEdits.Edit("☑ milk\n☐ ", 9), NoteEdits.typed("☑ milk", "☑ milk\n", 7))
        assertEquals("plain text is left alone", NoteEdits.Edit("milk\n", 5), NoteEdits.typed("milk", "milk\n", 5))
    }

    @Test fun `Enter on an empty bullet ends the list`() {
        assertEquals(NoteEdits.Edit("• milk\n", 7), NoteEdits.typed("• milk\n• ", "• milk\n• \n", 10))
    }

    @Test fun `the list buttons mark the selected lines, or unmark them`() {
        val on = NoteEdits.toggleList("milk\neggs\nbread", 0, 9, NoteEdits.Kind.NUMBER)
        assertEquals("1. milk\n2. eggs\nbread", on.text)
        val off = NoteEdits.toggleList(on.text, 0, 3, NoteEdits.Kind.NUMBER)
        assertEquals("milk\n2. eggs\nbread", off.text)
        assertEquals("milk\n• eggs\nbread", NoteEdits.toggleList("milk\neggs\nbread", 6, 6, NoteEdits.Kind.BULLET).text)
    }

    @Test fun `a keystroke made before a merged partner edit reached the field keeps their lines`() {
        // The field still shows "Milk"; the engine already holds the partner's "Bread" under it.
        assertEquals("Milkx\nBread", NoteEdits.onto("Milk\nBread", "Milk", "Milkx"))
        assertEquals("their line above", "Eggs\nMilk!", NoteEdits.onto("Eggs\nMilk", "Milk", "Milk!"))
        assertEquals("Backspace", "Mil\nBread", NoteEdits.onto("Milk\nBread", "Milk", "Mil"))
        assertEquals("Enter with a new bullet", "• a\n• \nBread", NoteEdits.onto("• a\nBread", "• a", "• a\n• "))
        assertEquals("nothing merged meanwhile: the field's text", "Milkx", NoteEdits.onto("Milk", "Milk", "Milkx"))
        // Both changed the same line: both kept, nothing lost.
        val same = NoteEdits.onto("Oat milk", "Milk", "Milkx")
        assertTrue(same.contains("Oat milk") && same.contains("Milkx"))
        // A list button over several lines is merged line by line.
        assertEquals("• a\n• b\nBread", NoteEdits.onto("a\nb\nBread", "a\nb", "• a\n• b"))
    }

    @Test fun `Add to calendar takes the selection, or the cursor's line, without its marker`() {
        val text = "Plans\n• Dinner at Lilo's on Friday\n• Movie"
        assertEquals("Dinner at Lilo's on Friday", NoteEdits.calendarTitle(text, 10, 10))
        assertEquals("Dinner", NoteEdits.calendarTitle(text, 8, 14))
        assertNull(NoteEdits.calendarTitle("\n\n", 1, 1))
    }

    @Test fun `pasted bullets numbers and checkboxes become clean items, long lines are cut`() {
        assertEquals(listOf("milk", "bread", "1.5 kg rice"), PasteParser.split("- milk\n* bread\n1.5 kg rice"))
        assertTrue(PasteParser.split("y".repeat(650)).all { it.length <= 300 })
    }

    // ---- who did what ----

    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-29T12:00:00Z").toEpochMilli()
    private fun ago(minutes: Long) = now - minutes * 60_000

    @Test fun `short times`() {
        assertEquals("now", ShortAgo.format(ago(0), now, zone))
        assertEquals("3m", ShortAgo.format(ago(3), now, zone))
        assertEquals("2h", ShortAgo.format(ago(125), now, zone))
        assertEquals("yesterday", ShortAgo.format(ago(60 * 20), now, zone))
        val locale = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.UK)
        try {
            assertEquals("Mon", ShortAgo.format(Instant.parse("2026-10-26T10:00:00Z").toEpochMilli(), now, zone))
            assertEquals("20 Oct", ShortAgo.format(Instant.parse("2026-10-20T10:00:00Z").toEpochMilli(), now, zone))
            assertEquals("20 Oct 2025", ShortAgo.format(Instant.parse("2025-10-20T10:00:00Z").toEpochMilli(), now, zone))
        } finally {
            java.util.Locale.setDefault(locale)
        }
    }

    @Test fun `ticked rows say who and when`() {
        val people = People("me", "bea", "Raina")
        assertEquals("✓ You · 3m", people.tickLine(DocItem("a", "x", true, "me", ago(3)), now, zone))
        assertEquals("✓ Raina · yesterday", people.tickLine(DocItem("a", "x", true, "bea", ago(60 * 20)), now, zone))
        assertNull("unknown who: nothing", people.tickLine(DocItem("a", "x", true, null, null), now, zone))
        assertNull(people.tickLine(DocItem("a", "x", false, "me", ago(3)), now, zone))
        assertTrue(people.addedByPartner(DocItem("a", "x", addedBy = "bea")))
        assertFalse(people.addedByPartner(DocItem("a", "x", addedBy = "me")))
    }

    // ---- overview cards ----

    private fun entry(d: ListDoc, updatedBy: String? = "me", state: String? = null, error: String? = null) =
        OverviewEntry("l", d, ServerMeta(2, "me", updatedBy, ago(30)), state, error, ago(30))

    @Test fun `a to-do card previews 4 rows with boxes, counts the rest and shows progress`() {
        val d = ListDoc(items = (1..7).map { DocItem("i$it", "Item $it", done = it <= 3) } + DocItem("e", " "))
        val c = ListPreview.card(entry(d), People("me", "bea", "Raina"), now, zone)
        assertEquals(listOf("☑", "☑", "☑", "☐"), c.lines.map { it.prefix })
        assertEquals(3, c.more)
        assertEquals(3 to 7, c.done to c.total)
        assertTrue(c.showProgress)
        assertEquals("Untitled", c.title)
        assertEquals("To-do list", c.kindLabel)
    }

    @Test fun `numbered and bulleted cards, and a note card`() {
        val numbered = ListPreview.card(entry(ListDoc(marker = "number", checkboxes = false, items = listOf(DocItem("a", "One"), DocItem("b", "Two")))), People(null, null, null), now, zone)
        assertEquals(listOf("1.", "2."), numbered.lines.map { it.prefix })
        assertFalse(numbered.showProgress)
        val note = ListPreview.card(entry(ListDoc(kind = "note", title = "Ideas", body = "first\n\nsecond")), People(null, null, null), now, zone)
        assertEquals(listOf("first", "second"), note.lines.map { it.text })
        assertEquals("Note", note.kindLabel)
    }

    @Test fun `the footer shows the partner's newest touch`() {
        val people = People("me", "bea", "Raina")
        val ticked = ListDoc(items = listOf(DocItem("a", "A very long beach day out with friends", true, "bea", ago(5))))
        assertEquals("Raina ticked “A very long beach day o…” · 5m", ListPreview.footer(entry(ticked, updatedBy = "bea"), people, now, zone))
        assertEquals("Raina edited · 30m", ListPreview.footer(entry(ListDoc(), updatedBy = "bea"), people, now, zone))
        assertEquals("Edited · 30m", ListPreview.footer(entry(ListDoc()), people, now, zone))
    }

    @Test fun `draft chips`() {
        assertNull(ListPreview.chip(entry(ListDoc())))
        assertEquals(DraftChip.SAVING, ListPreview.chip(entry(ListDoc(), state = ListSyncEngine.STATE_PENDING)))
        assertEquals(DraftChip.ON_PHONE, ListPreview.chip(entry(ListDoc(), state = ListSyncEngine.STATE_PENDING, error = "offline")))
        assertEquals(DraftChip.NOT_SAVED, ListPreview.chip(entry(ListDoc(), state = ListSyncEngine.STATE_FAILED)))
        assertEquals(DraftChip.DELETED, ListPreview.chip(entry(ListDoc(), state = ListSyncEngine.STATE_ORPHANED)))
    }

    @Test fun `filters and search`() {
        val note = entry(ListDoc(kind = "note", title = "Ideas", body = "Paris in spring"))
        val todo = entry(ListDoc(title = "Trip", items = listOf(DocItem("a", "Passports"))))
        assertTrue(ListPreview.matches(note, ListFilter.NOTES, ""))
        assertFalse(ListPreview.matches(todo, ListFilter.NOTES, ""))
        assertTrue(ListPreview.matches(todo, ListFilter.TODOS, ""))
        assertTrue(ListPreview.matches(note, ListFilter.ALL, "paris"))
        assertTrue(ListPreview.matches(todo, ListFilter.ALL, "passport"))
        assertFalse(ListPreview.matches(todo, ListFilter.ALL, "paris"))
    }
}
