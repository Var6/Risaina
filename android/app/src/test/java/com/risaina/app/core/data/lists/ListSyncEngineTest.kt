package com.risaina.app.core.data.lists

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.FixedTimeProvider
import com.risaina.app.core.data.ListsRepository
import com.risaina.app.core.data.toEntity
import com.risaina.app.core.database.ListDraftEntity
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.network.ConvertListRequest
import com.risaina.app.core.network.CreateListRequest
import com.risaina.app.core.network.ListItemDto
import com.risaina.app.core.network.ListResponse
import com.risaina.app.core.network.ListsResponse
import com.risaina.app.core.network.NetworkModule
import com.risaina.app.core.network.PatchListRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SharedListDto
import com.risaina.app.testutil.FakeListDao
import com.risaina.app.testutil.FakeListDraftDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The local-first engine against a fake server that checks versions, keeps client item ids and
 * stamps who ticked and added, like the real one (lists.md B, tests list).
 */
class ListSyncEngineTest {
    private val json = NetworkModule.json
    private val t0 = "2026-10-29T10:00:00.000Z"

    /** The server: lists by id, plus failures to answer the next requests with. */
    private inner class Server {
        val lists = linkedMapOf<String, SharedListDto>()
        val calls = mutableListOf<String>()
        val patches = mutableListOf<PatchListRequest>()
        val creates = mutableListOf<CreateListRequest>()
        val failures = ArrayDeque<Throwable>()
        private val keys = HashMap<String, String>()
        private var n = 0
        var patchDelayMs = 0L
        var convertDelayMs = 0L
        /** The create lands, then the connection drops before the phone hears back. */
        var loseCreateReply = false

        private fun failIfAsked() { failures.removeFirstOrNull()?.let { throw it } }
        private fun notFound() = AppError.NotFound("List not found")

        suspend fun patch(id: String, p: PatchListRequest): ListResponse {
            calls += "PATCH $id@${p.expectedVersion}"
            if (patchDelayMs > 0) delay(patchDelayMs)
            failIfAsked()
            patches += p
            val cur = lists[id] ?: throw notFound()
            if (p.expectedVersion != null && p.expectedVersion != cur.version) {
                throw AppError.VersionConflict("changed", JsonObject(mapOf("list" to json.encodeToJsonElement(SharedListDto.serializer(), cur))))
            }
            val prev = cur.items.associateBy { it.id }
            val items = p.items?.map { i ->
                val old = prev[i.id]
                when {
                    old == null -> i.copy(id = i.id ?: "s${n++}", doneBy = if (i.done) "me" else null, doneAt = if (i.done) t0 else null, addedBy = "me", addedAt = t0)
                    !i.done -> old.copy(text = i.text, done = false, doneBy = null, doneAt = null)
                    old.done -> old.copy(text = i.text)
                    else -> old.copy(text = i.text, done = true, doneBy = "me", doneAt = t0)
                }
            } ?: cur.items
            val next = cur.copy(
                version = cur.version + 1, updatedBy = "me", title = p.title ?: cur.title, body = p.body ?: cur.body, items = items,
                pinned = p.pinned ?: cur.pinned, marker = p.marker ?: cur.marker, checkboxes = p.checkboxes ?: cur.checkboxes,
            )
            lists[id] = next
            return ListResponse(next)
        }

        fun create(r: CreateListRequest): ListResponse {
            calls += "POST ${r.clientKey}"
            failIfAsked()
            creates += r
            r.clientKey?.let(keys::get)?.let { first -> return ListResponse(lists[first] ?: throw notFound()) }
            val id = "L${n++}"
            val made = SharedListDto(
                id, "c", "me", "me", r.title, r.kind, r.body ?: "",
                r.items.orEmpty().mapIndexed { i, it -> ListItemDto("$id-$i", it.text, it.done, addedBy = "me", addedAt = t0) },
                r.pinned ?: false, 1, t0, t0, marker = r.marker ?: "none", checkboxes = r.checkboxes ?: true,
            )
            lists[id] = made
            r.clientKey?.let { keys[it] = id }
            if (loseCreateReply) { loseCreateReply = false; throw IOException("connection reset") }
            return ListResponse(made)
        }

        fun partnerEdit(id: String, change: (SharedListDto) -> SharedListDto): SharedListDto {
            val next = change(lists.getValue(id)).let { it.copy(version = lists.getValue(id).version + 1, updatedBy = "bea") }
            lists[id] = next
            return next
        }
    }

    private val server = Server()
    private val api = mockk<RisainaApi>().also { a ->
        coEvery { a.patchList(any(), any()) } coAnswers { server.patch(firstArg(), secondArg()) }
        coEvery { a.createList(any()) } coAnswers { server.create(firstArg()) }
        coEvery { a.list(any()) } coAnswers { server.calls += "GET ${firstArg<String>()}"; ListResponse(server.lists[firstArg()] ?: throw AppError.NotFound()) }
        coEvery { a.lists() } coAnswers { ListsResponse(server.lists.values.toList()) }
        coEvery { a.deleteList(any()) } coAnswers { server.calls += "DELETE ${firstArg<String>()}"; server.lists.remove(firstArg<String>()) ?: throw AppError.NotFound() }
        coEvery { a.convertList(any(), any()) } coAnswers {
            val id = firstArg<String>()
            val req = secondArg<ConvertListRequest>()
            server.calls += "CONVERT $id"
            if (server.convertDelayMs > 0) delay(server.convertDelayMs)
            val cur = server.lists.getValue(id)
            if (req.expectedVersion != null && req.expectedVersion != cur.version) {
                throw AppError.VersionConflict("changed", JsonObject(mapOf("list" to json.encodeToJsonElement(SharedListDto.serializer(), cur))))
            }
            val items = cur.body.lines().filter { it.isNotBlank() }.mapIndexed { i, l -> ListItemDto("cv$i", l.removePrefix("• "), addedBy = "me") }
            val next = cur.copy(kind = req.to, body = "", items = cur.items + items, version = cur.version + 1)
            server.lists[id] = next
            ListResponse(next)
        }
    }
    private val listDao = FakeListDao()
    private val draftDao = FakeListDraftDao()
    private val time = FixedTimeProvider(Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC))
    private val repo = ListsRepository(api, listDao, json, time)
    private val scheduled = mutableListOf<Long>()
    private var cancelled = 0
    private val scheduler = object : ListSaveScheduler {
        override fun schedule(delaySeconds: Long) { scheduled += delaySeconds }
        override fun cancel() { cancelled++ }
    }

    /**
     * The engine's scope runs on the test scheduler as ordinary (not background) work, so
     * advanceUntilIdle runs its debounces and sends; it is not a child of the test, so its endless
     * watcher does not keep the test from finishing. Cancelled after each test.
     */
    private val scopes = mutableListOf<CoroutineScope>()
    private fun TestScope.engineScope() = scopes.firstOrNull() ?: CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
    private fun TestScope.engine() = ListSyncEngine(api, listDao, draftDao, repo, json, time, scheduler, engineScope())

    @After fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun item(id: String, text: String, done: Boolean = false) = ListItemDto(id, text, done)
    private suspend fun seed(id: String, version: Int, vararg items: ListItemDto, kind: String = "checklist", body: String = "") {
        val dto = SharedListDto(id, "c", "me", "me", "Groceries", kind, body, items.toList(), false, version, t0, t0)
        server.lists[id] = dto
        listDao.upsert(dto.toEntity(json, syncedAt = 1))
    }

    /** Opens the editor on [id]; the returned function gives its newest snapshot. */
    private fun TestScope.open(e: ListSyncEngine, id: String): () -> EditorSnapshot {
        var last: EditorSnapshot? = null
        engineScope().launch { e.observe(id).collect { last = it } }
        runCurrent()
        return { last!! }
    }

    private fun addRow(id: String, text: String): (ListDoc) -> ListDoc = { it.copy(items = it.items + DocItem(id, text)) }
    private fun setText(id: String, text: String): (ListDoc) -> ListDoc = { d -> d.copy(items = d.items.map { if (it.id == id) it.copy(text = text) else it }) }

    @Test fun `an edit is on this phone before any network, then sent after a pause in typing`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        val snap = open(e, "l")
        e.edit("l", addRow("x", "Eggs"))
        runCurrent()
        assertEquals(SyncStatus.Saving, snap().status)
        advanceTimeBy(ListSyncEngine.PERSIST_DEBOUNCE_MS + 10); runCurrent()
        assertNotNull("the draft is stored before anything is sent", draftDao.rows.value["l"])
        assertTrue(server.calls.isEmpty())

        advanceTimeBy(ListSyncEngine.SEND_DEBOUNCE_MS); runCurrent()
        assertEquals(listOf("PATCH l@1"), server.calls)
        assertEquals(listOf("Milk", "Eggs"), server.lists.getValue("l").items.map { it.text })
        assertEquals("x", server.lists.getValue("l").items[1].id)
        assertNull("sent: no draft is left", draftDao.rows.value["l"])
        assertEquals(SyncStatus.Saved, snap().status)
        assertEquals(2, listDao.byId("l")!!.version)
        assertEquals("the server's stamp is shown", "me", snap().doc.items[1].addedBy)
    }

    @Test fun `leaving sends at once, and just looking at a list sends nothing`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        open(e, "l")
        e.flush("l"); runCurrent()
        assertTrue(server.calls.isEmpty())
        e.edit("l", setText("a", "Oat milk"))
        e.flush("l"); runCurrent()
        assertEquals(listOf("PATCH l@1"), server.calls)
    }

    @Test fun `typing on while a save is on its way is kept and sent next, with the text as typed`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        val snap = open(e, "l")
        server.patchDelayMs = 1_000
        e.edit("l", addRow("x", "Buy "))
        advanceTimeBy(ListSyncEngine.SEND_DEBOUNCE_MS + 100); runCurrent()
        e.edit("l", setText("x", "Buy milk")) // while "Buy" is on its way
        advanceTimeBy(1_000); runCurrent()
        assertEquals("Buy milk", snap().doc.items[1].text)
        advanceUntilIdle()
        assertEquals("Buy milk", server.lists.getValue("l").items[1].text)
        assertEquals(listOf("PATCH l@1", "PATCH l@2"), server.calls)
        assertNull(draftDao.rows.value["l"])
    }

    @Test fun `a trailing space is not sent again and never replaced by the trimmed copy`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        val snap = open(e, "l")
        e.edit("l", addRow("x", "Buy "))
        advanceUntilIdle()
        assertEquals("Buy", server.lists.getValue("l").items[1].text)
        assertEquals("Buy ", snap().doc.items[1].text)
        assertEquals(1, server.calls.size)
        assertEquals(SyncStatus.Saved, snap().status)
    }

    @Test fun `a 409 is merged with the partner's copy and sent again on top of it`() = runTest {
        seed("l", 1, item("a", "Picnic"))
        server.partnerEdit("l") { it.copy(items = listOf(item("a", "Picnic", done = true).copy(doneBy = "bea", doneAt = t0))) }
        val e = engine()
        val snap = open(e, "l")
        e.edit("l", addRow("x", "Stargazing"))
        advanceUntilIdle()
        assertEquals(listOf("PATCH l@1", "PATCH l@2"), server.calls)
        val items = server.lists.getValue("l").items
        assertEquals(listOf("Picnic", "Stargazing"), items.map { it.text })
        assertTrue("the partner's tick is kept", items[0].done)
        assertEquals("bea", items[0].doneBy)
        assertEquals(SyncStatus.Saved, snap().status)
        assertEquals(3, listDao.byId("l")!!.version)
    }

    @Test fun `no network keeps the draft, says so, and leaves it to the worker`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        val snap = open(e, "l")
        server.failures += IOException("offline")
        e.edit("l", addRow("x", "Eggs"))
        advanceUntilIdle()
        assertEquals(SyncStatus.Offline, snap().status)
        val d = draftDao.rows.value.getValue("l")
        assertEquals(ListSyncEngine.STATE_PENDING, d.state)
        assertTrue(scheduled.contains(0L))

        // The network is back: the worker sends it.
        assertTrue(e.sendAll())
        runCurrent()
        assertEquals(listOf("Milk", "Eggs"), server.lists.getValue("l").items.map { it.text })
        assertNull(draftDao.rows.value["l"])
        assertEquals(SyncStatus.Saved, snap().status)
    }

    @Test fun `a 400 is sent once more sanitized, then kept as not saved`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        val snap = open(e, "l")
        server.failures += AppError.Validation("bad")
        server.failures += AppError.Validation("bad")
        e.edit("l", addRow("x", "Eggs"))
        advanceUntilIdle()
        assertEquals(2, server.calls.size)
        assertTrue(snap().status is SyncStatus.Failed)
        assertEquals(ListSyncEngine.STATE_FAILED, draftDao.rows.value.getValue("l").state)
        assertTrue("a refused draft is not retried by the worker", e.sendAll())
        assertEquals(2, server.calls.size)

        e.retry("l"); advanceUntilIdle()
        assertEquals(listOf("Milk", "Eggs"), server.lists.getValue("l").items.map { it.text })
        assertEquals(SyncStatus.Saved, snap().status)
    }

    @Test fun `over 200 items nothing is sent and it says why`() = runTest {
        seed("l", 1)
        val e = engine()
        val snap = open(e, "l")
        e.edit("l") { d -> d.copy(items = List(201) { DocItem("i$it", "x$it") }) }
        advanceUntilIdle()
        assertTrue(server.calls.isEmpty())
        assertEquals(SyncStatus.Failed(ListSyncEngine.TOO_MANY_ITEMS), snap().status)
    }

    @Test fun `a list deleted on the server while edited becomes orphaned, and can be kept as a new list`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        val snap = open(e, "l")
        server.lists.remove("l")
        e.edit("l", addRow("x", "Eggs"))
        advanceUntilIdle()
        assertEquals(SyncStatus.Orphaned, snap().status)
        assertNull("gone from the overview's server copies", listDao.byId("l"))
        assertEquals(ListSyncEngine.STATE_ORPHANED, draftDao.rows.value.getValue("l").state)

        val newId = e.keepAsNew("l")!!
        assertTrue(ListSyncEngine.isLocal(newId))
        advanceUntilIdle()
        assertNull(draftDao.rows.value["l"])
        val made = server.lists.values.single()
        assertEquals(listOf("Milk", "Eggs"), made.items.map { it.text })
        assertEquals("the editor follows the new list to its server id", made.id, open(e, newId)().listId)
    }

    @Test fun `this phone's own echo is ignored, and a partner's newer copy is merged without losing what is typed`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        val snap = open(e, "l")
        e.edit("l", addRow("x", "Eggs"))
        advanceUntilIdle()
        // The WebSocket brings back this phone's own v2: nothing changes.
        listDao.upsertIfNewer(server.lists.getValue("l").toEntity(json))
        runCurrent()
        assertEquals(listOf("a", "x"), snap().doc.items.map { it.id })

        // Typing, not sent yet, when the partner's v3 arrives.
        e.edit("l", addRow("y", "Bre"))
        val theirs = server.partnerEdit("l") { it.copy(items = it.items + item("p", "Jam"), title = "Shopping") }
        listDao.upsertIfNewer(theirs.toEntity(json))
        runCurrent()
        // My new row stays under the row I added it after; theirs follows.
        assertEquals(listOf("Milk", "Eggs", "Bre", "Jam"), snap().doc.items.map { it.text })
        assertEquals("Shopping", snap().doc.title)
        e.edit("l", setText("y", "Bread"))
        advanceUntilIdle()
        assertEquals("PATCH l@3", server.calls.last())
        assertEquals(listOf("Milk", "Eggs", "Bread", "Jam"), server.lists.getValue("l").items.map { it.text })
    }

    @Test fun `an older copy (a slow GET, a replay) never undoes a newer edit`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        val snap = open(e, "l")
        e.edit("l", addRow("x", "Eggs"))
        advanceUntilIdle()
        listDao.upsertIfNewer(SharedListDto("l", "c", "me", "me", "Groceries", "checklist", "", listOf(item("a", "Milk")), false, 1, t0, t0).toEntity(json))
        runCurrent()
        assertEquals(listOf("Milk", "Eggs"), snap().doc.items.map { it.text })
    }

    @Test fun `a new list is made on the server once it has something in it, and keeps working under its new id`() = runTest {
        val e = engine()
        val id = e.createLocal(ListDoc(kind = "checklist", marker = "number", checkboxes = false))
        val snap = open(e, id)
        advanceUntilIdle()
        assertTrue("an empty new list is not sent", server.calls.isEmpty())

        e.edit(id) { it.copy(title = "Trip", items = listOf(DocItem("c1", "Tickets"), DocItem("c2", ""))) }
        advanceUntilIdle()
        val made = server.lists.values.single()
        assertEquals(id.removePrefix(ListSyncEngine.LOCAL_PREFIX), server.creates.single().clientKey)
        assertEquals("number", made.marker)
        assertEquals(made.id, snap().listId)
        assertEquals("rows take the server's ids", listOf(made.items[0].id, "c2"), snap().doc.items.map { it.id })
        assertTrue(draftDao.rows.value.isEmpty())

        e.edit(id, setText(made.items[0].id!!, "Tickets and passports")) // the old id still works
        advanceUntilIdle()
        assertEquals(listOf("POST ${server.creates.single().clientKey}", "PATCH ${made.id}@1"), server.calls)
        assertEquals("Tickets and passports", server.lists.getValue(made.id).items.single().text)
    }

    @Test fun `an empty new list that is left is dropped, not saved as Untitled`() = runTest {
        val e = engine()
        val id = e.createLocal(ListDoc(kind = "note"))
        open(e, id)
        e.release(id)
        advanceUntilIdle()
        assertTrue(server.calls.isEmpty())
        assertTrue(draftDao.rows.value.isEmpty())
    }

    @Test fun `a new list whose create reply was lost is retried with its key, and a 404 then drops it and says so`() = runTest {
        val e = engine()
        val notices = mutableListOf<ListNotice>()
        engineScope().launch { e.notices.collect { notices += it } }
        server.loseCreateReply = true
        val id = e.createLocal(ListDoc(kind = "checklist", title = "Weekend", items = listOf(DocItem("c1", "Hike"))))
        val snap = open(e, id)
        advanceUntilIdle()
        assertEquals(SyncStatus.Offline, snap().status)
        assertNotNull(draftDao.rows.value[id])

        server.lists.clear() // the partner deleted it meanwhile
        e.sendAll()
        runCurrent()
        assertEquals("both tries carry the same key", List(2) { "POST ${id.removePrefix(ListSyncEngine.LOCAL_PREFIX)}" }, server.calls)
        assertTrue(draftDao.rows.value.isEmpty())
        assertEquals(SyncStatus.Gone, snap().status)
        assertEquals(ListNotice.DeletedBeforeSaved(id, "Weekend"), notices.single())
    }

    @Test fun `a create retried after its reply was lost keeps what the partner did meanwhile`() = runTest {
        val e = engine()
        server.loseCreateReply = true
        val id = e.createLocal(ListDoc(kind = "checklist", title = "Groceries", items = listOf(DocItem("c1", "Milk"), DocItem("c2", "Eggs "), DocItem("c3", "Rice"))))
        val snap = open(e, id)
        advanceUntilIdle()
        assertEquals(SyncStatus.Offline, snap().status)
        val made = server.lists.values.single().id
        // The partner heard list.created: ticks Milk, deletes Eggs, adds Bread (v2). Here, Jam is typed.
        server.partnerEdit(made) { l ->
            l.copy(items = listOf(l.items[0].copy(done = true, doneBy = "bea", doneAt = t0), item("p", "Bread").copy(addedBy = "bea"), l.items[2]))
        }
        e.edit(id, addRow("c4", "Jam"))
        e.sendAll()
        advanceUntilIdle()

        val items = server.lists.getValue(made).items
        assertEquals(setOf("Milk", "Bread", "Rice", "Jam"), items.map { it.text }.toSet())
        assertEquals(4, items.size)
        assertTrue("their tick is kept", items.first { it.text == "Milk" }.done)
        assertEquals("bea", items.first { it.text == "Bread" }.addedBy)
        assertEquals(items.map { it.text }, snap().doc.items.map { it.text })
        assertEquals("only Jam was sent on top of their v2", listOf("PATCH $made@2"), server.calls.filter { it.startsWith("PATCH") })
        assertEquals(SyncStatus.Saved, snap().status)
        assertTrue(draftDao.rows.value.isEmpty())
    }

    @Test fun `a retried create that finds the partner's changes and nothing new here sends nothing more`() = runTest {
        val e = engine()
        server.loseCreateReply = true
        val id = e.createLocal(ListDoc(kind = "checklist", title = "Trip", items = listOf(DocItem("c1", "Tickets"))))
        val snap = open(e, id)
        advanceUntilIdle()
        val made = server.lists.values.single().id
        server.partnerEdit(made) { it.copy(items = it.items + item("p", "Passports")) }
        e.sendAll()
        advanceUntilIdle()
        assertEquals(listOf("Tickets", "Passports"), snap().doc.items.map { it.text })
        assertTrue(server.calls.none { it.startsWith("PATCH") })
        assertEquals(SyncStatus.Saved, snap().status)
    }

    @Test fun `a list deleted by the partner marks a stored draft orphaned`() = runTest {
        seed("l", 1, item("a", "Milk"))
        draftDao.upsert(ListDraftEntity("l", 1, "{}", "{}", 5, ListSyncEngine.STATE_PENDING, null, 0))
        val e = engine()
        e.onDeleted("l")
        assertEquals(ListSyncEngine.STATE_ORPHANED, draftDao.rows.value.getValue("l").state)
        assertNull(listDao.byId("l"))
        assertTrue("an orphaned draft is never sent", e.sendAll())
        assertTrue(server.calls.isEmpty())
    }

    @Test fun `a list deleted while open with nothing unsent is gone`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        val snap = open(e, "l")
        e.onDeleted("l"); runCurrent()
        assertEquals(SyncStatus.Gone, snap().status)
        assertTrue(draftDao.rows.value.isEmpty())
    }

    @Test fun `a draft left by a closed app is sent by the worker`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val base = ListDoc(title = "Groceries", items = listOf(DocItem("a", "Milk")))
        val mine = base.copy(items = base.items + DocItem("x", "Eggs"))
        draftDao.upsert(ListDraftEntity("l", 1, json.encodeToString(ListDoc.serializer(), base), json.encodeToString(ListDoc.serializer(), mine), 5, ListSyncEngine.STATE_SENDING, null, 0))
        assertTrue(engine().sendAll())
        assertEquals(listOf("Milk", "Eggs"), server.lists.getValue("l").items.map { it.text })
        assertTrue(draftDao.rows.value.isEmpty())
    }

    @Test fun `too many requests waits for Retry-After, then sends`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        open(e, "l")
        server.failures += AppError.RateLimited(5)
        e.edit("l", addRow("x", "Eggs"))
        advanceTimeBy(ListSyncEngine.SEND_DEBOUNCE_MS + 10); runCurrent()
        assertEquals(1, server.calls.size)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(1, server.calls.size)
        advanceTimeBy(1_100); runCurrent()
        assertEquals(2, server.calls.size)
        assertEquals(listOf("Milk", "Eggs"), server.lists.getValue("l").items.map { it.text })
    }

    @Test fun `sign-out drops edits in memory and cancels the worker`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        open(e, "l")
        e.edit("l", addRow("x", "Eggs"))
        e.reset()
        advanceUntilIdle()
        assertTrue(server.calls.isEmpty())
        assertEquals(1, cancelled)
        assertTrue(draftDao.rows.value.isEmpty())
    }

    @Test fun `a note becomes a checklist on the server once what is typed is sent`() = runTest {
        seed("n", 1, kind = "note", body = "• milk")
        val e = engine()
        val snap = open(e, "n")
        e.edit("n") { it.copy(body = "• milk\n• eggs") }
        assertTrue(e.convert("n", ListKind.CHECKLIST).isSuccess)
        runCurrent()
        assertEquals(listOf("PATCH n@1", "CONVERT n"), server.calls)
        assertEquals("checklist", snap().doc.kind)
        assertEquals(listOf("milk", "eggs"), snap().doc.items.map { it.text })
        assertEquals(SyncStatus.Saved, snap().status)
    }

    @Test fun `what is typed while a note turns into a checklist is kept, as rows`() = runTest {
        seed("n", 1, kind = "note", body = "• milk")
        val e = engine()
        val snap = open(e, "n")
        server.convertDelayMs = 1_000
        var result: AppResult<Unit>? = null
        engineScope().launch { result = e.convert("n", ListKind.CHECKLIST) }
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf("CONVERT n"), server.calls)
        e.edit("n") { it.copy(body = it.body + "\n• bread") } // while the convert is on its way
        advanceUntilIdle()
        assertTrue(result!!.isSuccess)
        assertEquals("checklist", snap().doc.kind)
        assertEquals(listOf("milk", "bread"), snap().doc.items.map { it.text })
        assertEquals(listOf("CONVERT n", "PATCH n@2"), server.calls)
        assertEquals(listOf("milk", "bread"), server.lists.getValue("n").items.map { it.text })
        assertEquals(SyncStatus.Saved, snap().status)
    }

    @Test fun `deleting a list deletes it for both, and an unsaved new list just goes`() = runTest {
        seed("l", 1, item("a", "Milk"))
        val e = engine()
        open(e, "l")
        assertTrue(e.delete("l").isSuccess)
        assertNull(listDao.byId("l"))
        assertFalse(server.lists.containsKey("l"))
        val local = e.createLocal(ListDoc(title = "Draft"))
        advanceUntilIdle()
        server.calls.clear()
        val made = server.lists.values.single().id
        assertTrue(e.delete(local).isSuccess)
        assertEquals(listOf("DELETE $made"), server.calls)
    }
}
