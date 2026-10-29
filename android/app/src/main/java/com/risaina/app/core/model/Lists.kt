package com.risaina.app.core.model

import java.time.Instant

enum class ListKind(val wire: String) {
    NOTE("note"), CHECKLIST("checklist");
    companion object { fun fromWire(v: String) = entries.firstOrNull { it.wire == v } ?: NOTE }
}

/**
 * How a list's rows are numbered or bulleted. A format field, not a new kind: the live lists
 * validator allows only note and checklist, and v75 shows a numbered list as a plain checklist.
 */
enum class ListMarker(val wire: String) {
    NONE("none"), NUMBER("number"), BULLET("bullet"),
    /** A marker from a newer app. Shown like [NONE], and never sent back: requests carry only a marker the user picked. */
    UNKNOWN("");
    companion object { fun fromWire(v: String) = entries.firstOrNull { it != UNKNOWN && it.wire == v } ?: UNKNOWN }
}

/** The server's limits (backend lists/schemas.ts). A save over them fails as a whole with 400. */
object ListLimits {
    const val MAX_ITEMS = 200
    const val MAX_ITEM_TEXT = 300
    const val MAX_TITLE = 120
    const val MAX_BODY = 10_000
    /** The pinned shared checklist that undated to-dos from the chat go into (created when missing). */
    const val TODO_LIST_TITLE = "To-dos"
}

/** [addedBy]/[addedAt] are stamped by the server on items added since they existed; null on older items. */
data class ListItem(
    val id: String?, val text: String, val done: Boolean, val doneBy: String? = null, val doneAt: Instant? = null,
    val addedBy: String? = null, val addedAt: Instant? = null,
)

data class SharedList(
    val id: String,
    val title: String,
    val kind: ListKind,
    val body: String,
    val items: List<ListItem>,
    val pinned: Boolean,
    val version: Int,
    val createdBy: String,
    val updatedBy: String?,
    val updatedAt: Instant,
    val marker: ListMarker = ListMarker.NONE,
    /** Whether rows show a checkbox. On by default, so older lists stay to-do lists. */
    val checkboxes: Boolean = true,
) {
    val doneCount: Int get() = items.count { it.done }
}
