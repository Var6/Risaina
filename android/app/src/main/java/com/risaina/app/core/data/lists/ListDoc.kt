package com.risaina.app.core.data.lists

import com.risaina.app.core.data.TodoAppend
import com.risaina.app.core.data.toInstantOrNull
import com.risaina.app.core.database.ListEntity
import com.risaina.app.core.model.ListItem
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.ListLimits
import com.risaina.app.core.model.ListMarker
import com.risaina.app.core.network.CreateListRequest
import com.risaina.app.core.network.ListItemDto
import com.risaina.app.core.network.PatchListRequest
import com.risaina.app.core.network.SharedListDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * A list as the editor holds it: what is typed, exactly as typed (a row's trailing space stays
 * while the person is still typing), plus the server-owned details shown next to a row. It is
 * stored as JSON in list_drafts (the base and "mine" of a draft), so it changes only by adding
 * fields with defaults. [marker] keeps the wire string, so a marker from a newer app survives.
 */
@Serializable
data class ListDoc(
    val kind: String = ListKind.CHECKLIST.wire,
    val title: String = "",
    val body: String = "",
    val pinned: Boolean = false,
    val marker: String = ListMarker.NONE.wire,
    val checkboxes: Boolean = true,
    val items: List<DocItem> = emptyList(),
) {
    val isNote: Boolean get() = kind == ListKind.NOTE.wire
    val markerKind: ListMarker get() = ListMarker.fromWire(marker)
}

/**
 * One row. Every row has an id from the moment it exists: rows typed on this phone get one made here
 * (TodoAppend.newId), which the server keeps, so a row is the same row on both phones and in every
 * merge. doneBy/doneAt/addedBy/addedAt are the server's (a tick made here carries a local stamp
 * until the server's comes back); they are shown, never sent.
 */
@Serializable
data class DocItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
    val doneBy: String? = null,
    val doneAt: Long? = null,
    val addedBy: String? = null,
    val addedAt: Long? = null,
) {
    fun toListItem() = ListItem(id, text, done, doneBy, doneAt?.let(Instant::ofEpochMilli), addedBy, addedAt?.let(Instant::ofEpochMilli))
}

/** The server's version and authorship of the copy a doc came from. */
data class ServerMeta(val version: Int, val createdBy: String, val updatedBy: String?, val updatedAt: Long)

fun SharedListDto.toDoc(): ListDoc = ListDoc(
    kind = kind, title = ListWire.displayTitle(title), body = body, pinned = pinned, marker = marker, checkboxes = checkboxes,
    items = items.map { it.toDocItem() },
)

fun SharedListDto.meta() = ServerMeta(version, createdBy, updatedBy, updatedAt.toInstantOrNull()?.toEpochMilli() ?: 0L)

fun ListEntity.toDoc(json: Json): ListDoc {
    val items = runCatching { json.decodeFromString(ListSerializer(ListItemDto.serializer()), itemsJson) }.getOrDefault(emptyList())
    return ListDoc(kind, ListWire.displayTitle(title), body, pinned, marker, checkboxes, items.map { it.toDocItem() })
}

fun ListEntity.meta() = ServerMeta(version, createdBy, updatedBy, updatedAt)

// The server gives every item an id; the fallback only keeps a malformed copy from crashing.
private fun ListItemDto.toDocItem() = DocItem(
    id = id ?: TodoAppend.newId(), text = text, done = done, doneBy = doneBy, doneAt = doneAt.toInstantOrNull()?.toEpochMilli(),
    addedBy = addedBy, addedAt = addedAt.toInstantOrNull()?.toEpochMilli(),
)

/**
 * What a doc looks like on the wire, and the requests made from it. Rows with no text are kept on
 * the phone but never sent (the server refuses an empty item); texts are trimmed (as the server
 * trims, trimJs) and cut to the server's limits, so a save never fails as a whole over one long line.
 */
object ListWire {
    /** The title a list gets when none is typed. The editor shows it as the placeholder, not as text. */
    const val UNTITLED = "Untitled"
    private val ITEM_ID = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val SENDABLE_MARKERS = setOf(ListMarker.NONE.wire, ListMarker.NUMBER.wire, ListMarker.BULLET.wire)

    /** A server title as the editor shows it: "Untitled" is the empty title. */
    fun displayTitle(title: String): String = if (title.trimJs() == UNTITLED) "" else title

    fun title(d: ListDoc): String = TodoAppend.cut(d.title.trimJs(), ListLimits.MAX_TITLE).trimJs().ifEmpty { UNTITLED }
    fun body(d: ListDoc): String = TodoAppend.cut(d.body, ListLimits.MAX_BODY)
    fun sentRows(d: ListDoc): List<DocItem> = d.items.filter { !it.text.isBlankJs() }
    fun items(d: ListDoc): List<ListItemDto> = sentRows(d).map { ListItemDto(id = it.id, text = itemText(it.text), done = it.done) }
    fun itemText(t: String) = TodoAppend.cut(t.trimJs()).trimJs()

    /** [d] as the server stores it once created (texts trimmed and cut, empty rows left out), with this phone's row ids. */
    fun asCreated(d: ListDoc): ListDoc = d.copy(
        title = displayTitle(title(d)), body = body(d), marker = d.marker.takeIf { it in SENDABLE_MARKERS } ?: ListMarker.NONE.wire,
        items = sentRows(d).map { DocItem(it.id, itemText(it.text), it.done) },
    )

    private data class Shape(val title: String, val body: String, val pinned: Boolean, val marker: String, val checkboxes: Boolean, val items: List<Triple<String, String, Boolean>>)
    private fun shape(d: ListDoc) = Shape(title(d), body(d), d.pinned, d.marker, d.checkboxes, itemsShape(d))
    private fun itemsShape(d: ListDoc) = sentRows(d).map { Triple(it.id, itemText(it.text), it.done) }

    /** Whether saving [a] over [b] would change nothing on the server. */
    fun same(a: ListDoc, b: ListDoc): Boolean = shape(a) == shape(b)

    /** Anything worth creating a list for (a new list left empty is dropped, not saved as "Untitled"). */
    fun hasContent(d: ListDoc): Boolean = !d.title.isBlankJs() || !d.body.isBlankJs() || d.items.any { !it.text.isBlankJs() }

    fun tooManyItems(d: ListDoc): Boolean = sentRows(d).size > ListLimits.MAX_ITEMS

    /** The changes from [base] to [mine], checked against [expectedVersion]: only fields that differ are sent. */
    fun patch(base: ListDoc, mine: ListDoc, expectedVersion: Int) = PatchListRequest(
        title = title(mine).takeIf { it != title(base) },
        body = body(mine).takeIf { it != body(base) },
        items = items(mine).takeIf { itemsShape(mine) != itemsShape(base) },
        pinned = mine.pinned.takeIf { it != base.pinned },
        expectedVersion = expectedVersion,
        marker = mine.marker.takeIf { it != base.marker && it in SENDABLE_MARKERS },
        checkboxes = mine.checkboxes.takeIf { it != base.checkboxes },
    )

    /** A list made on this phone. [clientKey] makes a retry return the list the first try made. */
    fun create(mine: ListDoc, clientKey: String) = CreateListRequest(
        title = title(mine), kind = mine.kind, body = body(mine).ifEmpty { null },
        items = items(mine).map { ListItemDto(text = it.text, done = it.done) }.ifEmpty { null },
        pinned = mine.pinned.takeIf { it },
        marker = mine.marker.takeIf { it in SENDABLE_MARKERS }, checkboxes = mine.checkboxes, clientKey = clientKey,
    )

    /**
     * After a 400: a copy the server cannot refuse. Ids that are malformed or repeated get new ones
     * (a repeated id makes the whole save fail), and every text is cut to its limit.
     */
    fun sanitize(d: ListDoc, newId: () -> String = TodoAppend::newId): ListDoc {
        val seen = HashSet<String>()
        return d.copy(
            title = TodoAppend.cut(d.title, ListLimits.MAX_TITLE),
            body = body(d),
            items = d.items.map { i ->
                val id = i.id.takeIf { ITEM_ID.matches(it) && seen.add(it) } ?: newId().also { seen.add(it) }
                i.copy(id = id, text = TodoAppend.cut(i.text))
            },
        )
    }
}
