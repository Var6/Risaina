package com.risaina.app.core.data.lists

import com.risaina.app.core.database.ListDraftEntity
import com.risaina.app.core.database.ListEntity
import kotlinx.serialization.json.Json

/** A list on the overview: this phone's unsent draft over the server copy when there is one. */
data class OverviewEntry(
    val id: String,
    val doc: ListDoc,
    /** Null for a list made here that is not on the server yet, or one deleted while it had a draft. */
    val server: ServerMeta?,
    /** list_drafts.state (pending, sending, failed, orphaned) and why, when something is unsent. */
    val draftState: String? = null,
    val draftError: String? = null,
    /** When it last changed, here or on the server: the order within Pinned and Others. */
    val changedAt: Long,
)

object ListOverview {
    /** Every list the overview shows, pinned first, then the most recently changed. Pure. */
    fun entries(rows: List<ListEntity>, drafts: List<ListDraftEntity>, json: Json): List<OverviewEntry> {
        val byId = drafts.associateBy { it.listId }
        val fromServer = rows.map { e ->
            val d = byId[e.id]
            val mine = d?.let { decode(json, it.mineJson) }
            OverviewEntry(e.id, mine ?: e.toDoc(json), e.meta(), d?.state, d?.lastError, maxOf(e.updatedAt, d?.editedAt ?: 0L))
        }
        val known = rows.mapTo(HashSet()) { it.id }
        // Made here and not created yet, or deleted while it had unsent changes: only the draft is left.
        val draftsOnly = drafts.filter { it.listId !in known }.mapNotNull { d ->
            decode(json, d.mineJson)?.let { OverviewEntry(d.listId, it, null, d.state, d.lastError, d.editedAt) }
        }
        return (fromServer + draftsOnly).sortedWith(compareByDescending<OverviewEntry> { it.doc.pinned }.thenByDescending { it.changedAt })
    }

    private fun decode(json: Json, s: String): ListDoc? = runCatching { json.decodeFromString(ListDoc.serializer(), s) }.getOrNull()
}
