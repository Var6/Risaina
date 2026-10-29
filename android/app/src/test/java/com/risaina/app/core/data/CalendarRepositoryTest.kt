package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.database.PartnerEntity
import com.risaina.app.core.model.CalendarEntryDraft
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.toDraft
import com.risaina.app.core.network.CalendarEntryDto
import com.risaina.app.core.network.CalendarEntryResponse
import com.risaina.app.core.network.CalendarListResponse
import com.risaina.app.core.network.NetworkModule
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.testutil.FakeCalendarDao
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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The calendar's repository against a fake server: Room follows every reply, conflicts reload. */
class CalendarRepositoryTest {
    private val json = NetworkModule.json
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val bodies = mutableListOf<String>()
    private val requests = mutableListOf<String>()
    private var oldServer = false
    private val server = linkedMapOf<String, CalendarEntryDto>()
    /** Runs while GET /calendar is "on its way": after the request started, before its answer is applied. */
    private var getHook: suspend () -> Unit = {}

    private fun dto(id: String, version: Int, title: String = "Dinner") = CalendarEntryDto(
        id = id, coupleId = "c", createdBy = "me", kind = "event", title = title, date = "2026-11-14", time = "20:00",
        reminders = listOf(60), remindUserIds = listOf("me", "bea"), version = version,
        createdAt = "2026-10-29T10:00:00.000Z", updatedAt = "2026-10-29T10:00:00.000Z",
    )

    private val engine = MockEngine { req ->
        val path = req.url.encodedPath.removePrefix("/api/v1/")
        val method = req.method.value
        requests += "$method $path"
        when (val b = req.body) { is TextContent -> bodies += b.text; is OutgoingContent.ByteArrayContent -> bodies += b.bytes().decodeToString(); else -> Unit }
        fun ok(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(body, status, jsonHeaders)
        val notFound = """{"error":{"code":"not_found","message":"Not found"}}"""
        if (oldServer) return@MockEngine ok(notFound, HttpStatusCode.NotFound)
        val id = path.removePrefix("calendar/")
        when {
            method == "GET" && path == "calendar" -> {
                val answer = json.encodeToString(CalendarListResponse.serializer(), CalendarListResponse(server.values.toList()))
                Thread.sleep(2) // the hook's writes land strictly after the GET started
                getHook()
                ok(answer)
            }
            method == "POST" && path == "calendar" -> {
                val e = dto("new", 1, title = "Picnic"); server[e.id] = e
                ok(json.encodeToString(CalendarEntryResponse.serializer(), CalendarEntryResponse(e)), HttpStatusCode.Created)
            }
            method == "PATCH" -> {
                val cur = server[id] ?: return@MockEngine ok(notFound, HttpStatusCode.NotFound)
                val sent = json.parseToJsonElement(bodies.last()).jsonObject
                val expected = sent["expectedVersion"]?.toString()?.toInt()
                if (expected != null && expected != cur.version) {
                    return@MockEngine ok(
                        """{"error":{"code":"version_conflict","message":"Changed","details":{"entry":${json.encodeToString(CalendarEntryDto.serializer(), cur)}}}}""",
                        HttpStatusCode.Conflict,
                    )
                }
                val next = cur.copy(version = cur.version + 1, done = sent["done"]?.toString() == "true", title = sent["title"]?.toString()?.trim('"') ?: cur.title)
                server[id] = next
                ok(json.encodeToString(CalendarEntryResponse.serializer(), CalendarEntryResponse(next)))
            }
            method == "DELETE" -> if (server.remove(id) != null) respond("", HttpStatusCode.NoContent) else ok(notFound, HttpStatusCode.NotFound)
            else -> ok(notFound, HttpStatusCode.NotFound)
        }
    }
    private val client = HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        defaultRequest { url("https://api.test/api/v1/") }
    }
    private val dao = FakeCalendarDao()
    private val coupleDao = mockk<CoupleDao>().also { coEvery { it.partner() } returns PartnerEntity("bea", "Bea", null) }
    private val deletions = CalendarDeletions()
    private val repo = CalendarRepository(RisainaApi(client, client), dao, coupleDao, json, deletions)

    @Test fun `refresh makes Room the server's list and marks the calendar available`() = runTest {
        dao.upsert(dto("stale", 1).toEntity(syncedAt = 0))
        server["a"] = dto("a", 2)
        assertTrue(repo.refresh().isSuccess)
        assertEquals(setOf("a"), dao.rows.value.keys)
        assertEquals(true, repo.available.value)
    }

    @Test fun `an entry deleted while the GET was on its way does not come back`() = runTest {
        server["a"] = dto("a", 1); server["b"] = dto("b", 1)
        // The GET answers with both; meanwhile the partner's calendar.deleted for "b" was applied.
        getHook = { deletions.record("b") }
        assertTrue(repo.refresh().isSuccess)
        assertEquals(setOf("a"), dao.rows.value.keys)
    }

    @Test fun `an entry created while the GET was on its way is kept`() = runTest {
        server["a"] = dto("a", 1)
        getHook = { dao.upsert(dto("new", 1).toEntity()) }
        assertTrue(repo.refresh().isSuccess)
        assertEquals(setOf("a", "new"), dao.rows.value.keys)
    }

    @Test fun `an older server's 404 marks the calendar unavailable`() = runTest {
        oldServer = true
        assertTrue(repo.refresh().errorOrNull() is AppError.NotFound)
        assertEquals(false, repo.available.value)
    }

    @Test fun `a create stores the reply and sends a client key`() = runTest {
        val r = repo.create(CalendarEntryDraft(CalendarKind.EVENT, "Picnic", LocalDate.of(2026, 11, 1)), notifyPartner = true, clientKey = "k-1")
        assertEquals("Picnic", r.getOrNull()?.title)
        assertEquals("Picnic", dao.byId("new")!!.title)
        val sent = json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("\"k-1\"", sent["clientKey"].toString())
        assertEquals("true", sent["notifyPartner"].toString())
    }

    @Test fun `a stale edit reloads the partner's copy and says who changed it`() = runTest {
        server["e"] = dto("e", 5, title = "Dinner at 9")
        dao.upsert(dto("e", 4).toEntity())
        val mine = dao.byId("e")!!.toDomain()!!
        val r = repo.update(mine, mine.toDraft().copy(title = "Dinner at 8"), notifyPartner = false)
        val e = r.errorOrNull()
        assertTrue(e is AppError.VersionConflict)
        assertEquals("Bea changed this meanwhile", e!!.message)
        assertEquals("Dinner at 9", dao.byId("e")!!.title)
        assertEquals(5, dao.byId("e")!!.version)
    }

    @Test fun `an edit that changes nothing makes no request`() = runTest {
        dao.upsert(dto("e", 4).toEntity())
        val mine = dao.byId("e")!!.toDomain()!!
        assertEquals(mine, repo.update(mine, mine.toDraft(), notifyPartner = true).getOrNull())
        assertTrue(requests.isEmpty())
    }

    @Test fun `ticking a to-do sends no expected version, so a rename meanwhile does not block it`() = runTest {
        server["t"] = dto("t", 7)
        dao.upsert(dto("t", 6).toEntity())
        val r = repo.setDone("t", true)
        assertEquals(true, r.getOrNull()?.done)
        assertEquals("""{"done":true}""", bodies.single())
        assertEquals(8, dao.byId("t")!!.version)
    }

    @Test fun `deleting what the partner already deleted still removes it here`() = runTest {
        dao.upsert(dto("x", 1).toEntity())
        assertTrue(repo.delete("x").isSuccess)
        assertNull(dao.byId("x"))
        server["y"] = dto("y", 1); dao.upsert(dto("y", 1).toEntity())
        assertTrue(repo.delete("y").isSuccess)
        assertFalse(server.containsKey("y"))
    }

    @Test fun `an edit of an entry deleted meanwhile removes it here`() = runTest {
        dao.upsert(dto("z", 1).toEntity())
        assertTrue(repo.setDone("z", true).errorOrNull() is AppError.NotFound)
        assertNull(dao.byId("z"))
    }
}
