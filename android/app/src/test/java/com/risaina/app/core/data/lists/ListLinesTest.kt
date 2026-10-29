package com.risaina.app.core.data.lists

import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.ListMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines ↔ rows the way the server's convert does it (ListLines, ListConvert). */
class ListLinesTest {
    @Test fun `markers and boxes are stripped, a ticked box ticks the row`() {
        assertEquals(ListLines.Parsed("milk", false), ListLines.parseLine("- milk"))
        assertEquals(ListLines.Parsed("call mum", true), ListLines.parseLine("1. [x] call mum"))
        assertEquals(ListLines.Parsed("pack", true), ListLines.parseLine("☑ 2) pack"))
        assertEquals(ListLines.Parsed("bread", false), ListLines.parseLine("• ☐ bread"))
        assertEquals("a box with any space inside is unticked", false, ListLines.parseLine("[ ] tea")?.done ?: false)
        assertNull(ListLines.parseLine("  •  "))
    }

    @Test fun `a marker needs a space after it, so numbers and minus signs stay`() {
        assertEquals("1.5 kg rice", ListLines.parseLine("1.5 kg rice")!!.text)
        assertEquals("-5 °C tonight", ListLines.parseLine("-5 °C tonight")!!.text)
    }

    @Test fun `long text is cut at a space into pieces of 300 or fewer, never inside an emoji`() {
        val words = (1..120).joinToString(" ") { "word$it" }
        val pieces = ListLines.splitText(words)
        assertTrue(pieces.all { it.length <= 300 })
        assertEquals(words, pieces.joinToString(" "))
        val emoji = "a".repeat(299) + "😀" + "b"
        assertEquals(299, ListLines.splitText(emoji)[0].length)
        assertEquals(3, ListLines.splitText("x".repeat(650)).size)
    }

    @Test fun `text becomes rows up to the room left, and says how many lines did not fit`() {
        val r = ListLines.toRows("- a\n\n- b\n- c\n- d", room = 2)
        assertEquals(listOf("a", "b"), r.rows.map { it.text })
        assertEquals(2, r.leftOver)
        assertEquals("- c\n- d", r.rest)
    }

    @Test fun `prefixes follow the format`() {
        assertEquals("3. ☑ ", ListLines.prefix(2, true, ListMarker.NUMBER, true))
        assertEquals("• ", ListLines.prefix(0, false, ListMarker.BULLET, false))
        assertEquals("☐ ", ListLines.prefix(0, false, ListMarker.NONE, true))
        assertEquals("", ListLines.prefix(0, false, ListMarker.UNKNOWN, false))
    }

    @Test fun `a list not on the server yet converts here, both ways`() {
        var n = 0
        val note = ListDoc(kind = "note", title = "Shop", body = "• milk\n[x] eggs")
        val list = ListConvert.locally(note, ListKind.CHECKLIST) { "r${n++}" }
        assertEquals("checklist", list.kind)
        assertEquals(listOf(DocItem("r0", "milk"), DocItem("r1", "eggs", done = true)), list.items)
        assertEquals("", list.body)
        val back = ListConvert.locally(list.copy(marker = "number", checkboxes = false), ListKind.NOTE)
        assertEquals("1. milk\n2. eggs", back.body)
        assertTrue(back.items.isEmpty())
        assertEquals(note, ListConvert.locally(note, ListKind.NOTE))
    }
}
