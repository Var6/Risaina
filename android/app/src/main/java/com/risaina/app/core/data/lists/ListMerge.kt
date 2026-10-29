package com.risaina.app.core.data.lists

import java.util.Collections

/**
 * Three-way merge of a shared list: what both phones started from ([base]), this phone's unsent
 * edit ([mine]) and the server's newer copy ([theirs], the partner's save or this phone's other
 * device). Used on a 409 and whenever a newer copy arrives while there is an unsent edit, so
 * neither person's change is thrown away. Pure; ListMergeTest pins the rules down:
 *
 * - A field (title, pinned, format) is mine if I changed it, else theirs.
 * - A note's body is merged line by line (diff3). Changes to different lines are combined; where both
 *   changed the same lines, mine stay and theirs follow them ("both kept").
 * - Rows, matched by id: a row I deleted stays deleted; a row they deleted comes back only if I
 *   changed its text; for a row both kept, its text is mine if I changed it, else theirs, and its
 *   tick likewise. Rows either side added are kept.
 * - Order: the rows both kept follow my order if I reordered them, else theirs. Their new rows go
 *   after the row they followed on their side, then mine after the row they followed on mine.
 *
 * merge3(b, b, t) == t, merge3(b, m, b) == m, and merging again with the same copy changes nothing.
 */
object ListMerge {
    data class Result(val doc: ListDoc, val bodyConflict: Boolean)

    fun merge3(base: ListDoc, mine: ListDoc, theirs: ListDoc): Result {
        val body = Diff3.merge(base.body, mine.body, theirs.body)
        val doc = ListDoc(
            kind = pick(base.kind, mine.kind, theirs.kind),
            title = pickText(base.title, mine.title, theirs.title),
            body = body.text,
            pinned = pick(base.pinned, mine.pinned, theirs.pinned),
            marker = pick(base.marker, mine.marker, theirs.marker),
            checkboxes = pick(base.checkboxes, mine.checkboxes, theirs.checkboxes),
            items = mergeItems(base.items, mine.items, theirs.items),
        )
        return Result(doc, body.conflict)
    }

    private fun <T> pick(b: T, m: T, t: T): T = if (m != b) m else t

    /**
     * A text is "changed" only when it differs after trimming (the server trims), so a trailing
     * space being typed is not an edit that beats the partner's. When nobody changed it, the typed
     * copy stays as typed, so the text under the cursor never jumps.
     */
    internal fun pickText(b: String, m: String, t: String): String = when {
        m.trim() != b.trim() -> m
        t.trim() != b.trim() -> t
        m == b -> t
        else -> m
    }

    private fun mergeItems(base: List<DocItem>, mine: List<DocItem>, theirs: List<DocItem>): List<DocItem> {
        val b = base.associateBy { it.id }
        val m = mine.associateBy { it.id }
        val t = theirs.associateBy { it.id }
        val resolved = LinkedHashMap<String, DocItem>()
        for (id in (mine.map { it.id } + theirs.map { it.id }).distinct()) {
            val bi = b[id]
            val mi = m[id]
            val ti = t[id]
            val r = when {
                mi != null && ti != null -> if (bi != null) both(bi, mi, ti) else bothAdded(mi, ti)
                mi != null && bi == null -> mi // added on this phone
                mi != null -> mi.takeIf { it.text.trim() != bi!!.text.trim() } // they deleted it; kept if I reworded it
                ti != null && bi == null -> ti // the partner's new row
                else -> null // I deleted it
            }
            if (r != null) resolved[id] = r
        }
        return order(base, mine, theirs, resolved)
    }

    private fun both(bi: DocItem, mi: DocItem, ti: DocItem): DocItem {
        val tick = if (mi.done != bi.done) mi else ti
        return DocItem(
            id = bi.id, text = pickText(bi.text, mi.text, ti.text),
            done = tick.done, doneBy = tick.doneBy, doneAt = tick.doneAt,
            addedBy = pick(bi.addedBy, mi.addedBy, ti.addedBy), addedAt = pick(bi.addedAt, mi.addedAt, ti.addedAt),
        )
    }

    /** The same new row on both sides (a resend whose first try landed): mine, with the server's stamps. */
    private fun bothAdded(mi: DocItem, ti: DocItem): DocItem {
        val tick = if (mi.done == ti.done) ti else mi
        return mi.copy(done = tick.done, doneBy = tick.doneBy, doneAt = tick.doneAt, addedBy = ti.addedBy ?: mi.addedBy, addedAt = ti.addedAt ?: mi.addedAt)
    }

    private fun order(base: List<DocItem>, mine: List<DocItem>, theirs: List<DocItem>, keep: Map<String, DocItem>): List<DocItem> {
        val inBase = base.mapTo(HashSet()) { it.id }
        val inMine = mine.mapTo(HashSet()) { it.id }
        val inTheirs = theirs.mapTo(HashSet()) { it.id }
        val common = base.map { it.id }.filterTo(HashSet()) { it in inMine && it in inTheirs && it in keep }
        val baseOrder = base.map { it.id }.filter { it in common }
        val mineOrder = mine.map { it.id }.filter { it in common }
        val out = (if (mineOrder != baseOrder) mineOrder else theirs.map { it.id }.filter { it in common }).toMutableList()
        insertAfterPredecessors(out, theirs.map { it.id }) { it !in inBase && it in keep }
        insertAfterPredecessors(out, mine.map { it.id }) { it !in inTheirs && it in keep }
        return out.map { keep.getValue(it) }
    }

    /** Each id of [seq] that [take] accepts goes right after the nearest earlier id of [seq] already placed (or first). */
    private fun insertAfterPredecessors(out: MutableList<String>, seq: List<String>, take: (String) -> Boolean) {
        val placed = out.toHashSet()
        seq.forEachIndexed { i, id ->
            if (id in placed || !take(id)) return@forEachIndexed
            var at = 0
            for (j in i - 1 downTo 0) {
                val p = out.indexOf(seq[j])
                if (p >= 0) { at = p + 1; break }
            }
            out.add(at, id)
            placed += id
        }
    }

    /**
     * After a save: the server's stamps (who ticked, who added, when) copied onto this phone's rows,
     * whose text stays as typed. A row whose tick changed since the save keeps its local stamp.
     */
    fun adoptServerMeta(mine: ListDoc, server: ListDoc): ListDoc {
        val byId = server.items.associateBy { it.id }
        return mine.copy(
            items = mine.items.map { r ->
                val s = byId[r.id] ?: return@map r
                val tick = if (s.done == r.done) s else r
                r.copy(doneBy = tick.doneBy, doneAt = tick.doneAt, addedBy = s.addedBy ?: r.addedBy, addedAt = s.addedAt ?: r.addedAt)
            },
        )
    }

    /** How a list made here maps onto the server's copy once created: row ids (phone → server), and the merge base. */
    data class Created(val ids: Map<String, String>, val base: ListDoc)

    /**
     * A list made here was created; [reply] is the server's copy. It is usually exactly what was
     * [sent], but a create retried after its reply was lost gets the list as it is now: perhaps made
     * by an earlier try with other rows, and changed by the partner since. So rows are matched by text
     * (in order), and the base for a three-way merge onto [reply] is what the first try sent
     * ([firstTry], as the server would have stored it): the partner's changes are kept, and so is
     * what was typed since. A first-try row with no match was deleted or reworded there; a row typed
     * after the first try counts as new here.
     */
    fun created(firstTry: ListDoc?, sent: ListDoc, reply: ListDoc): Created {
        val first = ListWire.asCreated(firstTry?.takeIf { ListWire.hasContent(it) } ?: sent)
        val free = reply.items.toMutableList()
        val ids = HashMap<String, String>()
        for (r in first.items + ListWire.asCreated(sent).items) {
            if (r.id in ids) continue
            val i = free.indexOfFirst { it.text == r.text }
            if (i >= 0) ids[r.id] = free.removeAt(i).id
        }
        return Created(ids, remapIds(first, ids))
    }

    /** A new list's rows take the ids the server gave them on create ([ids]: phone id → server id). */
    fun remapIds(doc: ListDoc, ids: Map<String, String>): ListDoc =
        if (ids.isEmpty()) doc else doc.copy(items = doc.items.map { r -> ids[r.id]?.let { r.copy(id = it) } ?: r })
}

/**
 * Line-based three-way merge (diff3) of a note's body. Lines that both sides kept are the anchors;
 * between two anchors a change made on one side only is taken, the same change on both sides is
 * taken once, lines one side only added next to lines the other side changed are both taken, and
 * other different changes are both kept, mine first. Pure.
 */
internal object Diff3 {
    data class Merged(val text: String, val conflict: Boolean)

    /** Above this many line pairs the middle is not diffed; it then merges as one block. */
    private const val MAX_CELLS = 2_000_000

    fun merge(base: String, mine: String, theirs: String): Merged = when {
        mine == theirs || theirs == base -> Merged(mine, false)
        mine == base -> Merged(theirs, false)
        else -> {
            val (lines, conflict) = mergeLines(base.split('\n'), mine.split('\n'), theirs.split('\n'))
            Merged(lines.joinToString("\n"), conflict)
        }
    }

    fun mergeLines(o: List<String>, a: List<String>, b: List<String>): Pair<List<String>, Boolean> {
        val ma = match(o, a)
        val mb = match(o, b)
        val out = ArrayList<String>()
        var conflict = false
        var i = 0
        var j = 0
        var k = 0
        while (true) {
            while (i < o.size && ma[i] == j && mb[i] == k) { out += o[i]; i++; j++; k++ }
            var io = i
            while (io < o.size && (ma[io] < 0 || mb[io] < 0)) io++
            val ja = if (io < o.size) ma[io] else a.size
            val kb = if (io < o.size) mb[io] else b.size
            val oc = o.subList(i, io)
            val ac = a.subList(j, ja)
            val bc = b.subList(k, kb)
            val theirsAround = around(oc, bc)
            val mineAround = around(oc, ac)
            when {
                ac == oc -> out += bc
                bc == oc || ac == bc -> out += ac
                // One side kept these lines and only added next to them; the other changed (or deleted) them: both.
                theirsAround != null -> out += wrap(theirsAround, ac)
                mineAround != null -> out += wrap(mineAround, bc)
                Collections.indexOfSubList(ac, bc) >= 0 -> out += ac // theirs is already in mine
                Collections.indexOfSubList(bc, ac) >= 0 -> out += bc
                else -> { out += ac; out += bc; conflict = true }
            }
            if (io >= o.size) break
            i = io; j = ja; k = kb
        }
        return out to conflict
    }

    /** When [x] is [o] unchanged with lines added before and/or after it: those lines. */
    private fun around(o: List<String>, x: List<String>): Pair<List<String>, List<String>>? {
        if (o.isEmpty()) return null
        val at = Collections.indexOfSubList(x, o)
        if (at < 0) return null
        return x.subList(0, at) to x.subList(at + o.size, x.size)
    }

    /** [changed] with the lines added around it, each unless it is already there (merging twice adds nothing). */
    private fun wrap(added: Pair<List<String>, List<String>>, changed: List<String>): List<String> {
        val (pre, post) = added
        val keepPre = pre.isNotEmpty() && Collections.indexOfSubList(changed, pre) < 0
        val keepPost = post.isNotEmpty() && Collections.indexOfSubList(changed, post) < 0
        return (if (keepPre) pre else emptyList()) + changed + (if (keepPost) post else emptyList())
    }

    /** For each line of [o], the line of [x] it is matched to by a longest common subsequence, or -1. */
    fun match(o: List<String>, x: List<String>): IntArray {
        val m = IntArray(o.size) { -1 }
        var pre = 0
        while (pre < o.size && pre < x.size && o[pre] == x[pre]) { m[pre] = pre; pre++ }
        var suf = 0
        while (suf < o.size - pre && suf < x.size - pre && o[o.size - 1 - suf] == x[x.size - 1 - suf]) {
            m[o.size - 1 - suf] = x.size - 1 - suf; suf++
        }
        val n1 = o.size - pre - suf
        val n2 = x.size - pre - suf
        if (n1 == 0 || n2 == 0 || n1.toLong() * n2 > MAX_CELLS) return m
        // lcs[p][q]: the LCS of o[pre+p..] and x[pre+q..] in the middle part.
        val lcs = Array(n1 + 1) { IntArray(n2 + 1) }
        for (p in n1 - 1 downTo 0) for (q in n2 - 1 downTo 0) {
            lcs[p][q] = if (o[pre + p] == x[pre + q]) lcs[p + 1][q + 1] + 1 else maxOf(lcs[p + 1][q], lcs[p][q + 1])
        }
        var p = 0
        var q = 0
        while (p < n1 && q < n2) {
            when {
                o[pre + p] == x[pre + q] -> { m[pre + p] = pre + q; p++; q++ }
                lcs[p + 1][q] >= lcs[p][q + 1] -> p++
                else -> q++
            }
        }
        return m
    }
}
