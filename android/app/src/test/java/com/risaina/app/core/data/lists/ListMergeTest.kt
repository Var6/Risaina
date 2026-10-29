package com.risaina.app.core.data.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The merge rules (ListMerge) and the properties the sync engine relies on. */
class ListMergeTest {
    private fun row(id: String, text: String = id, done: Boolean = false, doneBy: String? = null, addedBy: String? = null) =
        DocItem(id, text, done, doneBy = doneBy, doneAt = doneBy?.let { 1_000L }, addedBy = addedBy)
    private fun doc(vararg rows: DocItem, title: String = "Weekend", body: String = "") = ListDoc(title = title, body = body, items = rows.toList())
    private fun merge(b: ListDoc, m: ListDoc, t: ListDoc) = ListMerge.merge3(b, m, t).doc
    private fun ids(d: ListDoc) = d.items.map { it.id }

    @Test fun `the partner ticks a row while I add one, both are kept`() {
        val base = doc(row("a", "Picnic"))
        val mine = doc(row("a", "Picnic"), row("x", "Stargazing"))
        val theirs = doc(row("a", "Picnic", done = true, doneBy = "bea"))
        val r = merge(base, mine, theirs)
        assertEquals(listOf("a", "x"), ids(r))
        assertTrue(r.items[0].done)
        assertEquals("bea", r.items[0].doneBy)
    }

    @Test fun `concurrent adds at the same place are both kept`() {
        val base = doc(row("a"), row("b"))
        val r = merge(base, doc(row("a"), row("x"), row("b")), doc(row("a"), row("y"), row("b")))
        assertEquals(setOf("a", "b", "x", "y"), ids(r).toSet())
        assertEquals("a", ids(r).first())
        assertEquals("b", ids(r).last())
    }

    @Test fun `a tick on one side and a reword on the other both land`() {
        val base = doc(row("a", "Beach"))
        val r = merge(base, doc(row("a", "Beach at dawn")), doc(row("a", "Beach", done = true, doneBy = "bea")))
        assertEquals(DocItem("a", "Beach at dawn", true, "bea", 1_000L), r.items.single())
    }

    @Test fun `both reword the same row, mine wins, and both toggling it, mine wins`() {
        val base = doc(row("a", "Beach"))
        assertEquals("Lake", merge(base, doc(row("a", "Lake")), doc(row("a", "Hills"))).items.single().text)
        val ticked = merge(doc(row("a", done = true, doneBy = "bea")), doc(row("a", done = false)), doc(row("a", done = true, doneBy = "bea")))
        assertFalse(ticked.items.single().done)
    }

    @Test fun `the partner deletes a row I reworded, it stays, with my text`() {
        val base = doc(row("a", "Beach"), row("b"))
        val r = merge(base, doc(row("a", "Beach at dawn"), row("b")), doc(row("b")))
        assertEquals(listOf("a", "b"), ids(r))
        assertEquals("Beach at dawn", r.items[0].text)
    }

    @Test fun `the partner deletes a row I only ticked or left alone, it goes`() {
        val base = doc(row("a"), row("b"))
        assertEquals(listOf("b"), ids(merge(base, doc(row("a", done = true), row("b")), doc(row("b")))))
        assertEquals(listOf("b"), ids(merge(base, base, doc(row("b")))))
    }

    @Test fun `I delete a row the partner reworded, it stays deleted`() {
        val base = doc(row("a", "Beach"), row("b"))
        assertEquals(listOf("b"), ids(merge(base, doc(row("b")), doc(row("a", "Beach!"), row("b")))))
    }

    @Test fun `my reorder wins, and the partner's new row follows the row it was added after`() {
        val base = doc(row("a"), row("b"), row("c"))
        val mine = doc(row("c"), row("b"), row("a"))
        val theirs = doc(row("a"), row("y"), row("b"), row("c"))
        assertEquals(listOf("c", "b", "a", "y"), ids(merge(base, mine, theirs)))
    }

    @Test fun `their reorder is taken when I did not reorder, and my new row keeps its place`() {
        val base = doc(row("a"), row("b"), row("c"))
        val mine = doc(row("a"), row("x"), row("b"), row("c"))
        val theirs = doc(row("c"), row("a"), row("b"))
        assertEquals(listOf("c", "a", "x", "b"), ids(merge(base, mine, theirs)))
    }

    @Test fun `a new row at the top stays at the top`() {
        val base = doc(row("a"))
        assertEquals(listOf("x", "y", "a"), ids(merge(base, doc(row("x"), row("a")), doc(row("y"), row("a")))))
    }

    @Test fun `title, pinned and format are mine if I changed them, else theirs`() {
        val base = ListDoc(title = "Trip", pinned = false, marker = "none", checkboxes = true)
        val mine = base.copy(title = "Goa trip")
        val theirs = base.copy(title = "Hill trip", pinned = true, marker = "number")
        val r = merge(base, mine, theirs)
        assertEquals("Goa trip", r.title)
        assertTrue(r.pinned)
        assertEquals("number", r.marker)
        assertEquals(false, merge(base, base.copy(checkboxes = false), theirs).checkboxes)
    }

    @Test fun `a trailing space being typed does not beat the partner's title, and is kept when nobody changed it`() {
        assertEquals("Hill trip", ListMerge.pickText("Trip", "Trip ", "Hill trip"))
        assertEquals("Trip ", ListMerge.pickText("Trip", "Trip ", "Trip"))
        assertEquals("Buy ", merge(doc(row("a", "Buy")), doc(row("a", "Buy ")), doc(row("a", "Buy"), row("p"))).items[0].text)
    }

    @Test fun `server stamps from the partner's copy are kept on rows both kept`() {
        val base = doc(row("a"))
        val r = merge(base, doc(row("a"), row("x")), doc(row("a", addedBy = "bea")))
        assertEquals("bea", r.items[0].addedBy)
    }

    @Test fun `note bodies merge line by line`() {
        val base = "milk\neggs\nbread"
        val r = ListMerge.merge3(doc(body = base), doc(body = "milk\neggs\nbread\njam"), doc(body = "oat milk\neggs\nbread"))
        assertEquals("oat milk\neggs\nbread\njam", r.doc.body)
        assertFalse(r.bodyConflict)
    }

    @Test fun `overlapping body edits keep both, mine first`() {
        val r = ListMerge.merge3(doc(body = "a\nb\nc"), doc(body = "a\nB mine\nc"), doc(body = "a\nB theirs\nc"))
        assertEquals("a\nB mine\nB theirs\nc", r.doc.body)
        assertTrue(r.bodyConflict)
    }

    @Test fun `a line changed on one side next to lines only added on the other is no conflict`() {
        val base = doc(body = "a\nb")
        val theirs = doc(body = "a\nb\nc")
        val r = ListMerge.merge3(base, doc(body = "a\nB"), theirs)
        assertEquals("a\nB\nc", r.doc.body)
        assertFalse(r.bodyConflict)
        assertEquals("merging again adds nothing", r.doc, merge(base, r.doc, theirs))
        val mirrored = ListMerge.merge3(base, doc(body = "z\na\nb\nc"), doc(body = "a\nB"))
        assertEquals("z\na\nB\nc", mirrored.doc.body)
        assertFalse(mirrored.bodyConflict)
        assertEquals("deleted here, added after there: the added line stays", "a\nc", merge(base, doc(body = "a"), theirs).body)
    }

    @Test fun `the same body edit on both sides is taken once`() {
        val r = ListMerge.merge3(doc(body = "a\nb"), doc(body = "a\nb\nc"), doc(body = "a\nb\nc"))
        assertEquals("a\nb\nc", r.doc.body)
        assertFalse(r.bodyConflict)
    }

    @Test fun `merging a conflict again with the same copy does not repeat their lines`() {
        val base = doc(body = "a\nb\nc")
        val theirs = doc(body = "a\nB theirs\nc")
        val once = ListMerge.merge3(base, doc(body = "a\nB mine\nc"), theirs).doc
        assertEquals(once, merge(base, once, theirs))
    }

    @Test fun `server stamps are adopted after a save without touching the typed text`() {
        val mine = doc(row("a", "Buy ", done = true), row("x", "Eggs"))
        val reply = doc(row("a", "Buy", done = true, doneBy = "me"), row("x", "Eggs", addedBy = "me"))
        val r = ListMerge.adoptServerMeta(mine, reply)
        assertEquals("Buy ", r.items[0].text)
        assertEquals("me", r.items[0].doneBy)
        assertEquals("me", r.items[1].addedBy)
        val unticked = ListMerge.adoptServerMeta(doc(row("a", done = false)), reply)
        assertEquals(null, unticked.items[0].doneBy)
    }

    @Test fun `a new list's rows take the server's ids`() {
        val r = ListMerge.remapIds(doc(row("c1", "Milk"), row("c2", "")), mapOf("c1" to "s1"))
        assertEquals(listOf("s1", "c2"), ids(r))
    }

    // ---- properties, over many random edits ----

    private fun randomEdit(r: Random, d: ListDoc, tag: String): ListDoc {
        val items = d.items.toMutableList()
        repeat(r.nextInt(4)) { n ->
            when (r.nextInt(6)) {
                0 -> items.add(r.nextInt(items.size + 1), DocItem("$tag$n", "new $tag$n"))
                1 -> if (items.isNotEmpty()) items.removeAt(r.nextInt(items.size))
                2 -> if (items.isNotEmpty()) r.nextInt(items.size).let { i -> items[i] = items[i].copy(text = items[i].text + " $tag") }
                3 -> if (items.isNotEmpty()) r.nextInt(items.size).let { i -> items[i] = items[i].copy(done = !items[i].done) }
                4 -> if (items.size > 1) { val from = r.nextInt(items.size); val it = items.removeAt(from); items.add(r.nextInt(items.size + 1), it) }
                else -> Unit
            }
        }
        val lines = d.body.split('\n').toMutableList()
        if (r.nextBoolean()) lines.add(r.nextInt(lines.size + 1), "line $tag")
        return d.copy(
            title = if (r.nextInt(4) == 0) "${d.title} $tag" else d.title,
            pinned = if (r.nextInt(4) == 0) !d.pinned else d.pinned,
            body = lines.joinToString("\n"),
            items = items,
        )
    }

    private fun randomBase(r: Random) = ListDoc(
        title = "List", body = (1..r.nextInt(1, 4)).joinToString("\n") { "l$it" },
        items = (1..r.nextInt(0, 6)).map { DocItem("b$it", "item $it", done = r.nextBoolean()) },
    )

    @Test fun `merge3(b, b, t) == t and merge3(b, m, b) == m`() {
        val r = Random(7)
        repeat(2_000) {
            val b = randomBase(r)
            val t = randomEdit(r, b, "t")
            val m = randomEdit(r, b, "m")
            assertEquals(t, merge(b, b, t))
            assertEquals(m, merge(b, m, b))
            assertEquals(m, merge(b, m, m))
        }
    }

    @Test fun `merging again with the same copy changes nothing, and nothing is duplicated`() {
        val r = Random(11)
        repeat(2_000) {
            val b = randomBase(r)
            val t = randomEdit(r, b, "t")
            val m = randomEdit(r, b, "m")
            val once = merge(b, m, t)
            assertEquals("ids unique", once.items.size, once.items.map { it.id }.toSet().size)
            assertEquals(once, merge(b, once, t))
            // After a merge the engine rebases on theirs: a second rebase on the same copy is a no-op.
            assertEquals(once, merge(t, once, t))
        }
    }

    @Test fun `every row added on either side survives a merge`() {
        val r = Random(3)
        repeat(1_000) {
            val b = randomBase(r)
            val t = randomEdit(r, b, "t")
            val m = randomEdit(r, b, "m")
            val added = (t.items + m.items).map { it.id }.filter { id -> b.items.none { it.id == id } }
            assertTrue(ids(merge(b, m, t)).containsAll(added))
        }
    }
}
