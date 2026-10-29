package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.FixedTimeProvider
import com.risaina.app.core.model.ListItem
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.ListLimits
import com.risaina.app.core.network.ListItemDto
import com.risaina.app.core.network.ListResponse
import com.risaina.app.core.network.ListsResponse
import com.risaina.app.core.network.NetworkModule
import com.risaina.app.core.network.PatchListRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SharedListDto
import com.risaina.app.testutil.FakeListDao
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Appending to-dos from the chat (message-actions.md §3), the "To-dos" list, convert and get-by-id,
 * against a fake server that keeps client item ids and checks versions like the real one.
 */
class ListsRepositoryTest {
    private val json = NetworkModule.json
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<String>()
    private val server = linkedMapOf<String, SharedListDto>()
    /** The partner saves this many times right before each of our PATCHes lands. */
    private var partnerEditsFirst = 0
    private var sendConflictDetails = true

    private fun dto(id: String, version: Int, items: List<ListItemDto> = emptyList(), title: String = "Groceries", kind: String = "checklist", pinned: Boolean = false) =
        SharedListDto(id, "c", "me", null, title, kind, "", items, pinned, version, "2026-10-29T10:00:00.000Z", "2026-10-29T10:00:00.000Z")

    private val engine = MockEngine { req ->
        val path = req.url.encodedPath.removePrefix("/api/v1/")
        val method = req.method.value
        requests += "$method $path"
        fun ok(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(body, status, jsonHeaders)
        fun notFound() = ok("""{"error":{"code":"not_found","message":"List not found"}}""", HttpStatusCode.NotFound)
        val body = when (val b = req.body) { is TextContent -> b.text; is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString(); else -> null }
        when {
            method == "GET" && path == "lists" -> ok(json.encodeToString(ListsResponse.serializer(), ListsResponse(server.values.toList())))
            method == "POST" && path == "lists" -> {
                val r = json.parseToJsonElement(body!!).jsonObject
                val created = dto("new", 1, title = r["title"]!!.jsonPrimitive.content, pinned = r["pinned"]?.jsonPrimitive?.content == "true")
                server[created.id] = created
                ok(json.encodeToString(ListResponse.serializer(), ListResponse(created)), HttpStatusCode.Created)
            }
            method == "GET" && path.startsWith("lists/") -> server[path.removePrefix("lists/")]?.let { ok(json.encodeToString(ListResponse.serializer(), ListResponse(it))) } ?: notFound()
            method == "PATCH" && path.startsWith("lists/") -> {
                val id = path.removePrefix("lists/")
                var cur = server[id] ?: return@MockEngine notFound()
                if (partnerEditsFirst > 0) {
                    partnerEditsFirst--
                    cur = cur.copy(version = cur.version + 1, items = cur.items + ListItemDto("p${cur.version}", "Partner's ${cur.version}"))
                    server[id] = cur
                }
                val patch = json.decodeFromString(PatchListRequest.serializer(), body!!)
                if (patch.expectedVersion != null && patch.expectedVersion != cur.version) {
                    val details = if (sendConflictDetails) ""","details":{"list":${json.encodeToString(SharedListDto.serializer(), cur)}}""" else ""
                    return@MockEngine ok("""{"error":{"code":"version_conflict","message":"Changed"$details}}""", HttpStatusCode.Conflict)
                }
                // Like the server's mergeItems: a client id it has not seen is kept as given.
                val next = cur.copy(version = cur.version + 1, items = patch.items!!.map { it.copy(id = it.id ?: "s${it.text}") })
                server[id] = next
                ok(json.encodeToString(ListResponse.serializer(), ListResponse(next)))
            }
            method == "POST" && path.endsWith("/convert") -> {
                val id = path.removePrefix("lists/").removeSuffix("/convert")
                val next = server.getValue(id).copy(kind = "checklist", version = server.getValue(id).version + 1)
                server[id] = next
                ok(json.encodeToString(ListResponse.serializer(), ListResponse(next)))
            }
            else -> ok("{}", HttpStatusCode.NotFound)
        }
    }
    private val client = HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        defaultRequest { url("https://api.test/api/v1/") }
    }
    private val dao = FakeListDao()
    private val repo = ListsRepository(RisainaApi(client, client), dao, json, FixedTimeProvider(Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC)))

    private suspend fun seed(l: SharedListDto) { server[l.id] = l; dao.upsert(l.toEntity(json, syncedAt = 1)) }

    @Test fun `new items get client ids and go after the existing ones at the stored version`() = runTest {
        seed(dto("g", 3, listOf(ListItemDto("a", "Milk", done = true))))
        val r = repo.appendItems("g", listOf(" Eggs ", "", "Bread"))
        val ids = (r as AppResult.Success).value
        assertEquals(2, ids.size)
        assertTrue(ids.all { it.matches(Regex("[A-Za-z0-9_-]{1,32}")) })
        assertEquals(listOf("Milk", "Eggs", "Bread"), server.getValue("g").items.map { it.text })
        assertEquals(listOf("a") + ids, server.getValue("g").items.map { it.id })
        assertEquals(true, server.getValue("g").items[0].done)
        assertEquals(4, dao.byId("g")!!.version)
        assertEquals(listOf("PATCH lists/g"), requests)
    }

    @Test fun `a partner's save in between is kept, and ours goes on top of it`() = runTest {
        seed(dto("g", 3, listOf(ListItemDto("a", "Milk"))))
        partnerEditsFirst = 1
        val r = repo.appendItems("g", listOf("Eggs"))
        assertTrue(r.isSuccess)
        assertEquals(listOf("Milk", "Partner's 3", "Eggs"), server.getValue("g").items.map { it.text })
        assertEquals(listOf("PATCH lists/g", "PATCH lists/g"), requests)
    }

    @Test fun `without the server's copy in the 409, the list is fetched before retrying`() = runTest {
        seed(dto("g", 3))
        partnerEditsFirst = 1
        sendConflictDetails = false
        assertTrue(repo.appendItems("g", listOf("Eggs")).isSuccess)
        assertEquals(listOf("PATCH lists/g", "GET lists/g", "PATCH lists/g"), requests)
    }

    @Test fun `it gives up after three retries`() = runTest {
        seed(dto("g", 3))
        partnerEditsFirst = 10
        val r = repo.appendItems("g", listOf("Eggs"))
        assertTrue(r.errorOrNull() is AppError.VersionConflict)
        assertEquals(1 + ListsRepository.APPEND_RETRIES, requests.size)
    }

    @Test fun `a full list, a deleted list and a note say so`() = runTest {
        seed(dto("full", 1, List(ListLimits.MAX_ITEMS) { ListItemDto("i$it", "x$it") }))
        assertEquals("That list is full", repo.appendItems("full", listOf("one more")).errorOrNull()?.message)
        seed(dto("gone", 1)); server.remove("gone")
        assertEquals("That list was deleted", repo.appendItems("gone", listOf("x")).errorOrNull()?.message)
        seed(dto("note", 1, kind = "note"))
        assertTrue(repo.appendItems("note", listOf("x")).errorOrNull() is AppError.Validation)
    }

    @Test fun `appending nothing sends nothing`() = runTest {
        assertEquals(emptyList<String>(), (repo.appendItems("g", listOf(" ", "")) as AppResult.Success).value)
        assertTrue(requests.isEmpty())
    }

    @Test fun `the To-dos list already on the phone is used without asking the server`() = runTest {
        seed(dto("t", 2, title = "to-dos", pinned = true))
        assertEquals("t", repo.ensureTodoList().getOrNull()?.id)
        assertTrue(requests.isEmpty())
    }

    @Test fun `the To-dos list is looked for on the server, then created pinned`() = runTest {
        server["t"] = dto("t", 2, title = "To-dos")
        assertEquals("t", repo.ensureTodoList().getOrNull()?.id)
        assertEquals(listOf("GET lists"), requests)

        server.clear(); requests.clear(); dao.clear()
        val made = repo.ensureTodoList().getOrNull()!!
        assertEquals(listOf("GET lists", "POST lists"), requests)
        assertEquals(ListLimits.TODO_LIST_TITLE, made.title)
        assertTrue(made.pinned)
    }

    @Test fun `convert and get by id store the server's copy`() = runTest {
        seed(dto("n", 1, kind = "note"))
        assertEquals(ListKind.CHECKLIST, repo.convert("n", ListKind.CHECKLIST, expectedVersion = 1).getOrNull()?.kind)
        assertEquals("checklist", dao.byId("n")!!.kind)
        server["n"] = server.getValue("n").copy(version = 9, title = "Packing")
        assertEquals("Packing", repo.getById("n").getOrNull()?.title)
        assertEquals(9, dao.byId("n")!!.version)
        assertTrue(repo.getById("nope").errorOrNull() is AppError.NotFound)
    }

    @Test fun `appended items skip ids already present, and texts are cut at 300 without splitting an emoji`() {
        val current = listOf(ListItem("a", "Milk", false), ListItem("c1", "Eggs", false))
        val added = listOf(ListItem("c1", "Eggs", false), ListItem("c2", "Bread", false))
        assertEquals(listOf("a", "c1", "c2"), TodoAppend.merge(current, added)!!.map { it.id })
        assertNull(TodoAppend.merge(List(199) { ListItem("i$it", "x", false) }, added))

        val long = "a".repeat(299) + "😀"
        assertEquals(299, TodoAppend.cut(long).length)
        assertEquals(300, TodoAppend.newItems(listOf("b".repeat(650))).single().text.length)
        assertTrue(TodoAppend.newId().matches(Regex("c[0-9A-Za-z]{15}")))
    }
}
