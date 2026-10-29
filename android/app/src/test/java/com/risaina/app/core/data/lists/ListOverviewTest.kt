package com.risaina.app.core.data.lists

import com.risaina.app.core.database.ListDraftEntity
import com.risaina.app.core.database.ListEntity
import com.risaina.app.core.network.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The overview's entries: drafts over server copies, drafts on their own, pinned first then newest. */
class ListOverviewTest {
    private val json = NetworkModule.json
    private fun row(id: String, title: String, updatedAt: Long, pinned: Boolean = false) = ListEntity(
        id = id, title = title, kind = "checklist", body = "", itemsJson = "[]", pinned = pinned, version = 1,
        createdBy = "me", updatedBy = "me", createdAt = 0, updatedAt = updatedAt,
    )
    private fun draft(id: String, mine: ListDoc, editedAt: Long, state: String = ListSyncEngine.STATE_PENDING, error: String? = null) =
        ListDraftEntity(id, 1, "{}", json.encodeToString(ListDoc.serializer(), mine), editedAt, state, error, 0)

    @Test fun `a draft is shown over its list, a draft alone is shown too, pinned first then newest`() {
        val entries = ListOverview.entries(
            rows = listOf(row("a", "Old", 100), row("b", "Pinned", 50, pinned = true), row("c", "Edited here", 10)),
            drafts = listOf(
                draft("c", ListDoc(title = "Edited here, unsent"), editedAt = 500),
                draft("new-x", ListDoc(title = "Made offline"), editedAt = 300, error = "offline"),
            ),
            json = json,
        )
        assertEquals(listOf("b", "c", "new-x", "a"), entries.map { it.id })
        assertEquals("Edited here, unsent", entries[1].doc.title)
        assertEquals(ListSyncEngine.STATE_PENDING, entries[1].draftState)
        assertNull(entries[2].server)
        assertEquals("offline", entries[2].draftError)
        assertNull(entries[3].draftState)
    }

    @Test fun `an unreadable draft falls back to the server copy`() {
        val bad = ListDraftEntity("a", 1, "{}", "not json", 5, ListSyncEngine.STATE_PENDING, null, 0)
        assertEquals("Old", ListOverview.entries(listOf(row("a", "Old", 1)), listOf(bad), json).single().doc.title)
    }
}
