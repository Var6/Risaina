package com.risaina.app.core.data.lists

import com.risaina.app.core.network.ListItemDto
import com.risaina.app.core.network.SharedListDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a doc becomes requests (ListWire): what is sent, what is left out, and what counts as a change. */
class ListWireTest {
    private fun doc(vararg rows: DocItem, title: String = "Trip") = ListDoc(title = title, items = rows.toList())

    @Test fun `empty rows are kept on the phone but not sent, and texts are trimmed`() {
        val d = doc(DocItem("a", " Milk "), DocItem("b", "  "), DocItem("c", "Eggs", done = true))
        assertEquals(listOf(ListItemDto("a", "Milk"), ListItemDto("c", "Eggs", done = true)), ListWire.items(d))
    }

    @Test fun `a trailing space or an empty row is not a change worth saving`() {
        val base = doc(DocItem("a", "Milk"))
        assertTrue(ListWire.same(doc(DocItem("a", "Milk "), DocItem("b", "")), base))
        assertFalse(ListWire.same(doc(DocItem("a", "Milk", done = true)), base))
        assertFalse(ListWire.same(base.copy(marker = "number"), base))
    }

    @Test fun `a blank title is sent as Untitled, and Untitled shows as the empty title`() {
        assertEquals("Untitled", ListWire.title(doc(title = "  ")))
        assertEquals("", ListWire.displayTitle("Untitled"))
        assertEquals("Goa", ListWire.displayTitle("Goa"))
        assertEquals(120, ListWire.title(doc(title = "x".repeat(200))).length)
    }

    @Test fun `a patch carries only what changed, at the base version`() {
        val base = doc(DocItem("a", "Milk"))
        val p = ListWire.patch(base, base.copy(pinned = true, checkboxes = false), expectedVersion = 4)
        assertEquals(4, p.expectedVersion)
        assertEquals(true, p.pinned)
        assertEquals(false, p.checkboxes)
        assertNull(p.title)
        assertNull(p.items)
        assertNull(p.body)
        val withItems = ListWire.patch(base, doc(DocItem("a", "Milk"), DocItem("x", "Eggs")), 4)
        assertEquals(listOf("a", "x"), withItems.items!!.map { it.id })
    }

    @Test fun `a marker from a newer app is never sent back`() {
        val base = doc().copy(marker = "stars")
        assertNull(ListWire.patch(base.copy(marker = "none"), base, 1).marker)
        assertEquals("number", ListWire.patch(base, base.copy(marker = "number"), 1).marker)
    }

    @Test fun `a create carries the format, the rows without ids and the client key`() {
        val d = ListDoc(kind = "checklist", title = "", marker = "number", checkboxes = false, items = listOf(DocItem("c1", "One"), DocItem("c2", "")))
        val r = ListWire.create(d, "key1")
        assertEquals("Untitled", r.title)
        assertEquals(listOf(ListItemDto(text = "One")), r.items)
        assertEquals("number", r.marker)
        assertEquals(false, r.checkboxes)
        assertEquals("key1", r.clientKey)
        assertNull(r.pinned)
    }

    @Test fun `content and the item limit`() {
        assertFalse(ListWire.hasContent(doc(DocItem("a", " "), title = "")))
        assertTrue(ListWire.hasContent(doc(title = "x")))
        assertTrue(ListWire.tooManyItems(doc(*Array(201) { DocItem("i$it", "x") })))
        assertFalse(ListWire.tooManyItems(doc(*Array(200) { DocItem("i$it", "x") }, DocItem("e", ""))))
    }

    @Test fun `sanitize gives bad or repeated ids new ones and cuts long texts`() {
        var n = 0
        val d = ListWire.sanitize(doc(DocItem("a", "x"), DocItem("a", "y"), DocItem("bad id!", "z".repeat(400))), newId = { "n${n++}" })
        assertEquals(listOf("a", "n0", "n1"), d.items.map { it.id })
        assertEquals(300, d.items[2].text.length)
    }

    @Test fun `a server copy becomes a doc with its stamps`() {
        val dto = SharedListDto(
            "l", "c", "me", "bea", "Untitled", "checklist", "", listOf(ListItemDto("a", "Milk", true, "bea", "2026-10-29T10:00:00.000Z", "me", null)),
            false, 3, "2026-10-29T09:00:00.000Z", "2026-10-29T10:00:00.000Z", marker = "bullet", checkboxes = false,
        )
        val d = dto.toDoc()
        assertEquals("", d.title)
        assertEquals("bullet", d.marker)
        assertEquals("bea", d.items.single().doneBy)
        assertEquals(1_793_268_000_000L, d.items.single().doneAt)
        assertEquals(3, dto.meta().version)
    }
}
