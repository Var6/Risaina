package com.risaina.app.core.model

import java.time.Instant

enum class ListKind(val wire: String) {
    NOTE("note"), CHECKLIST("checklist");
    companion object { fun fromWire(v: String) = entries.firstOrNull { it.wire == v } ?: NOTE }
}

data class ListItem(val id: String?, val text: String, val done: Boolean, val doneBy: String? = null, val doneAt: Instant? = null)

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
) {
    val doneCount: Int get() = items.count { it.done }
}
