package com.risaina.app.feature.lists

import com.risaina.app.core.model.ListItem
import com.risaina.app.core.model.SharedList

/**
 * One checklist row in the editor. [key] is local and never changes, so the row being typed in keeps
 * its focus when the server gives it an id (the LazyColumn key used to switch from "new-3" to the id).
 */
internal data class EditRow(val key: Long, val item: ListItem)

/**
 * How the list editor's own copy (title, body, rows) follows new versions of the list. A new version
 * that is this phone's own save coming back never refills the fields: they are at least as new as
 * what was sent. Refilling them used to replace whatever was typed after the save went out, and put
 * back the trimmed copy, so "Buy " then "milk" became "Buymilk". Any other new version is someone
 * else's change (the partner, or the server's copy after a conflict) and refills the editor.
 */
internal object ListEditorSync {
    /** What a save carries, compared without ids (the server assigns those to new items). */
    data class Content(val title: String, val body: String, val items: List<Pair<String, Boolean>>, val pinned: Boolean)

    fun contentOf(list: SharedList) = Content(list.title, list.body, list.items.map { it.text to it.done }, list.pinned)

    /** The content of a save, shaped exactly as ListsViewModel and ListsRepository send it. */
    fun sent(list: SharedList, title: String, body: String, items: List<ListItem>, pinned: Boolean) = Content(
        title = title.ifBlank { list.title }.trim(),
        body = body,
        items = items.filter { it.text.isNotBlank() }.map { it.text.trim() to it.done },
        pinned = pinned,
    )

    /**
     * After this phone's own save came back: copies the server's ids onto rows that are still new,
     * matched by trimmed text in order. The text stays exactly as typed. A row typed further since
     * the save does not match and stays new; the next save sends it again.
     */
    fun adoptIds(rows: List<EditRow>, server: List<ListItem>): List<EditRow> {
        val known = rows.mapNotNull { it.item.id }.toSet()
        val fresh = server.filter { it.id != null && it.id !in known }.toMutableList()
        return rows.map { r ->
            if (r.item.id != null || r.item.text.isBlank()) return@map r
            val match = fresh.firstOrNull { it.text == r.item.text.trim() } ?: return@map r
            fresh.remove(match)
            r.copy(item = r.item.copy(id = match.id))
        }
    }

    /** Rows for a refill from the server. A row whose id is still there keeps its key (and focus). */
    fun reseed(rows: List<EditRow>, server: List<ListItem>, newKey: () -> Long): List<EditRow> {
        val keyById = rows.mapNotNull { r -> r.item.id?.let { it to r.key } }.toMap()
        val used = HashSet<Long>()
        return server.map { item -> EditRow(item.id?.let(keyById::get)?.takeIf(used::add) ?: newKey(), item) }
    }
}

/**
 * The version each save is checked against. The editor's copy is based on the version it last loaded,
 * but this phone's own save moves the server on before its reply reaches the editor. A save that
 * starts in that window builds on the version this phone's previous save produced (not on someone
 * else's change), so it continues from there instead of failing with a false conflict.
 */
internal class SaveChain {
    private val next = HashMap<Pair<String, Int>, Int>()

    fun expected(listId: String, base: Int): Int {
        var v = base
        while (true) v = next[listId to v] ?: return v
    }

    fun record(listId: String, from: Int, to: Int) {
        if (to > from) next[listId to from] = to
    }
}
