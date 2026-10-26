package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.network.GameCatalogDto
import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.GameEventViewDto
import com.risaina.app.core.network.GameList
import com.risaina.app.core.network.GameResponse
import com.risaina.app.core.network.QuizDto
import com.risaina.app.core.network.QuizEventDto
import com.risaina.app.core.network.QuizProgressDto
import com.risaina.app.core.network.RisainaApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * How many requests game events cost. A push-triggered sync has about 8 s, so a catch-up batch
 * must fetch each game once at the end, not once per event.
 */
class GameRepositoryTest {
    private val me = "me"
    private val partner = "bea"
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** "METHOD path" of every request the repository made. */
    private val requests = mutableListOf<String>()
    private var offline = false
    /** The server's current copy of each game, keyed by id. */
    private val server = linkedMapOf<String, GameDto>()
    private var createConflict = false
    private var catalog: GameCatalogDto? = null

    private val engine = MockEngine { req ->
        requests += "${req.method.value} ${req.url.encodedPath.removePrefix("/api/v1/")}"
        if (offline) throw IOException("offline")
        val path = req.url.encodedPath.removePrefix("/api/v1/")
        when {
            path == "games" && req.method.value == "POST" && createConflict ->
                respond("""{"error":{"code":"game_in_progress","message":"A game is already in progress"}}""", HttpStatusCode.Conflict, jsonHeaders)
            path == "games" -> respond(json.encodeToString(GameList.serializer(), GameList(server.values.toList())), HttpStatusCode.OK, jsonHeaders)
            path == "games/catalog" -> catalog?.let { respond(json.encodeToString(GameCatalogDto.serializer(), it), HttpStatusCode.OK, jsonHeaders) }
                ?: respond("""{"error":{"code":"not_found","message":"Game not found"}}""", HttpStatusCode.NotFound, jsonHeaders)
            path.startsWith("games/") -> server[path.removePrefix("games/")]
                ?.let { respond(json.encodeToString(GameResponse.serializer(), GameResponse(it)), HttpStatusCode.OK, jsonHeaders) }
                ?: respond("""{"error":{"code":"not_found","message":"Game not found"}}""", HttpStatusCode.NotFound, jsonHeaders)
            else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
        }
    }
    private val client = HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        defaultRequest { url("https://api.test/api/v1/") }
    }
    private val repo = GameRepository(RisainaApi(client, client))

    private fun trivia(id: String = "g", status: String = "waiting", partnerAnswered: Int = 0, updatedAt: String = "2026-10-26T10:00:00.000Z") = GameDto(
        id = id, coupleId = "c", kind = GameKinds.TRIVIA, createdBy = me, createdAt = "2026-10-26T10:00:00.000Z",
        updatedAt = updatedAt, status = status,
        quiz = QuizDto(source = "opentdb", total = 5, myAnswered = 5, partnerAnswered = partnerAnswered, myFinished = true),
    )

    private fun event(answered: Int, finished: Boolean = false, status: String = "waiting", id: String = "g"): JsonObject =
        json.encodeToJsonElement(
            GameEventViewDto.serializer(),
            GameEventViewDto(
                id = id, kind = GameKinds.TRIVIA, status = status, round = 1, updatedAt = "2026-10-26T10:0$answered:00.000Z",
                quiz = QuizEventDto(total = 5, progress = listOf(QuizProgressDto(me, 5, true), QuizProgressDto(partner, answered, finished))),
            ),
        ).jsonObject

    @Test fun `a catch-up batch of four answers and the result fetches the game once, after the batch`() = runTest {
        // B finished first and the app was killed; A then answered 4 questions and the 5th resolved the duel.
        server["g"] = trivia(status = "revealed", partnerAnswered = 5)
        (1..4).forEach { n -> repo.onEvent("g", "answered", partner, fromPartner = true, view = event(n), defer = true) }
        repo.onEvent("g", "revealed", partner, fromPartner = true, view = event(5, finished = true, status = "revealed"), defer = true)
        assertEquals("nothing is fetched inside the batch", emptyList<String>(), requests)

        repo.flushStale()
        assertEquals(listOf("GET games/g"), requests)
        assertEquals("revealed", repo.games.value.single().status)

        repo.flushStale()
        assertEquals("nothing left to fetch", 1, requests.size)
    }

    @Test fun `with a current copy, the partner's answers come from the events and only the end is fetched`() = runTest {
        server["g"] = trivia(partnerAnswered = 1)
        repo.refresh()
        requests.clear()
        server["g"] = trivia(status = "revealed", partnerAnswered = 5)

        (2..4).forEach { n -> repo.onEvent("g", "answered", partner, fromPartner = true, view = event(n), defer = true) }
        assertEquals(4, repo.games.value.single().quiz!!.partnerAnswered)
        repo.onEvent("g", "revealed", partner, fromPartner = true, view = event(5, finished = true, status = "revealed"), defer = true)
        repo.flushStale()
        assertEquals(listOf("GET games/g"), requests)
        assertEquals("revealed", repo.games.value.single().status)
    }

    @Test fun `a live event outside a batch is fetched right away`() = runTest {
        server["g"] = trivia(status = "draw")
        repo.onEvent("g", "draw", partner, fromPartner = true, view = event(5, finished = true, status = "draw"))
        assertEquals(listOf("GET games/g"), requests)
        assertEquals("draw", repo.games.value.single().status)
    }

    @Test fun `offline, the game stays marked and the next flush fetches it`() = runTest {
        server["g"] = trivia(status = "revealed", partnerAnswered = 5)
        repo.onEvent("g", "revealed", partner, fromPartner = true, view = event(5, finished = true, status = "revealed"), defer = true)
        offline = true
        // Reported, so the deferred queue keeps its "games:stale" job for the next drain.
        assertTrue(repo.flushStale() is AppError.Offline)
        assertTrue(repo.games.value.isEmpty())

        offline = false
        assertEquals(null, repo.flushStale())
        assertEquals(listOf("GET games/g", "GET games/g"), requests)
        assertEquals("revealed", repo.games.value.single().status)
    }

    @Test fun `a game that is gone is not retried forever`() = runTest {
        repo.onEvent("gone", "cancelled", partner, fromPartner = true, view = event(1, id = "gone"), defer = true)
        assertEquals("a 404 is not worth retrying", null, repo.flushStale())
        repo.flushStale()
        assertEquals(listOf("GET games/gone"), requests)
    }

    @Test fun `a full replay, or many games due, is one list request`() = runTest {
        (1..6).forEach { i -> server["g$i"] = trivia(id = "g$i", status = "completed") }
        (1..6).forEach { i -> repeat(4) { repo.onEvent("g$i", "answered", partner, fromPartner = true, view = event(it + 1, id = "g$i"), defer = true) } }
        repo.flushStale(full = true)
        assertEquals(listOf("GET games"), requests)
        assertEquals(6, repo.games.value.size)

        requests.clear()
        (1..4).forEach { i -> repo.onEvent("g$i", "moved", partner, fromPartner = true, view = null, defer = true) }
        repo.flushStale()
        assertEquals("more than 3 games due: one list request", listOf("GET games"), requests)
    }

    @Test fun `starting while an unknown game is open loads it, so the picker can offer to end it`() = runTest {
        server["open"] = trivia(id = "open")
        createConflict = true
        val r = repo.start(GameStartOptions(kind = GameKinds.TICTACTOE))
        assertEquals("game_in_progress", (r.errorOrNull() as AppError.Conflict).code)
        assertEquals(listOf("POST games", "GET games"), requests)
        assertEquals("open", repo.games.value.single().id)
    }

    @Test fun `an older server's rps-only catalog is not kept once the server is updated`() = runTest {
        assertTrue(repo.catalog().legacyServer)
        catalog = GameRepository.DEFAULT_CATALOG
        val updated = repo.catalog()
        assertEquals(4, updated.kinds.size)
        repo.catalog()
        assertEquals("a real catalog is cached", listOf("GET games/catalog", "GET games/catalog"), requests)
    }
}
