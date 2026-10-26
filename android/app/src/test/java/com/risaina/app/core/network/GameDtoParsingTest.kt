package com.risaina.app.core.network

import com.risaina.app.core.data.GameKinds
import com.risaina.app.core.data.GameStartOptions
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Games stream: what the app parses from the server and exactly what it sends. */
class GameDtoParsingTest {
    private val json = NetworkModule.json
    private val common = """"id":"g1","coupleId":"c1","createdBy":"a","createdAt":"2026-10-26T10:00:00.000Z","updatedAt":"2026-10-26T10:00:00.000Z","winnerId":null,"loserId":null,"challenge":null,"answer":null,"completedAt":null,"round":1,"myMove":null,"partnerMoved":false,"partnerMove":null"""

    @Test fun `a game from an older server, without the new fields, still parses`() {
        val g = json.decodeFromString(GameResponse.serializer(), """{"game":{$common,"kind":"rps","status":"waiting"}}""").game
        assertEquals("rps", g.kind)
        assertEquals("truth_or_dare", g.prize)
        assertNull(g.ttt)
        assertNull(g.quiz)
    }

    @Test fun `tic-tac-toe parses with the board and turn`() {
        val g = json.decodeFromString(
            GameResponse.serializer(),
            """{"game":{$common,"kind":"tictactoe","status":"waiting","prize":"none","quiz":null,
               "ttt":{"board":["x",null,null,null,"o",null,null,null,null],"xId":"b","turnId":"b","lastCell":4,"line":null,"moveCount":2,"myMark":"x","myTurn":true}}}""",
        ).game
        assertEquals("none", g.prize)
        val t = g.ttt!!
        assertEquals(listOf("x", null, null, null, "o", null, null, null, null), t.board)
        assertTrue(t.myTurn)
        assertEquals(4, t.lastCell)
    }

    @Test fun `a trivia game parses, including the hidden fields as nulls`() {
        val g = json.decodeFromString(
            GameResponse.serializer(),
            """{"game":{$common,"kind":"trivia","status":"waiting","prize":"truth_or_dare","ttt":null,
               "quiz":{"source":"opentdb","attribution":"Questions from Open Trivia Database (opentdb.com), CC BY-SA 4.0",
                 "category":{"id":23,"name":"History"},"difficulty":null,"deck":null,"total":5,
                 "items":[{"index":0,"text":"Who?","choices":["A","B","C","D"],"category":"History","difficulty":"easy","myChoice":2,"correct":2,"partnerChoice":null},
                          {"index":1,"text":"When?","choices":["A","B","C","D"],"category":"History","difficulty":"easy","myChoice":null,"correct":null,"partnerChoice":null}],
                 "myAnswered":1,"partnerAnswered":3,"myFinished":false,"partnerFinished":false,"myScore":1,"partnerScore":null,"matches":null}}}""",
        ).game
        val q = g.quiz!!
        assertEquals("History", q.category?.name)
        assertEquals(2, q.items.size)
        assertEquals(2, q.items[0].correct)
        assertNull(q.items[1].correct)
        assertNull(q.partnerScore)
        assertEquals(3, q.partnerAnswered)
    }

    @Test fun `an unknown kind and unknown fields still parse`() {
        val g = json.decodeFromString(GameResponse.serializer(), """{"game":{$common,"kind":"chess","status":"waiting","newThing":{"a":1}}}""").game
        assertEquals("chess", g.kind)
        assertFalse(g.kind in GameKinds.KNOWN)
    }

    @Test fun `the catalog parses and legacyServer is never set by the server`() {
        val c = json.decodeFromString(
            GameCatalogDto.serializer(),
            """{"kinds":[{"kind":"trivia","title":"Trivia duel","emoji":"🧠","blurb":"b","competitive":true}],
               "trivia":{"questionCount":5,"difficulties":["easy"],"categories":[{"id":9,"name":"General knowledge"}],"attribution":"x","attributionUrl":"https://opentdb.com"},
               "thisOrThat":{"promptCount":7,"decks":[{"id":"us","name":"Us","emoji":"💞"}]}}""",
        )
        assertEquals("trivia", c.kinds.single().kind)
        assertEquals(9, c.trivia.categories.single().id)
        assertFalse(c.legacyServer)
    }

    // ---- request bodies ----

    private val sentBodies = mutableListOf<String>()
    private val sentPaths = mutableListOf<String>()

    private fun client() = HttpClient(MockEngine { req ->
        sentPaths += req.url.encodedPath
        sentBodies += when (val b = req.body) { is TextContent -> b.text; is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString(); else -> "" }
        respond("""{"game":{$common,"kind":"rps","status":"waiting"}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        expectSuccess = true
        install(ContentNegotiation) { json(NetworkModule.json) }
        defaultRequest { url("https://example.test/api/v1/") }
    }

    @Test fun `create bodies carry only the kind's own options and never the default prize`() = runTest {
        val api = RisainaApi(client(), client())
        api.createGame()
        api.createGame(GameStartOptions(GameKinds.TICTACTOE).toRequest())
        api.createGame(GameStartOptions(GameKinds.TICTACTOE, justForFun = true).toRequest())
        api.createGame(GameStartOptions(GameKinds.TRIVIA, category = 23, difficulty = "hard", deck = "us").toRequest())
        api.createGame(GameStartOptions(GameKinds.THIS_OR_THAT, justForFun = true, category = 9, deck = "us").toRequest())
        assertEquals(
            listOf(
                """{"kind":"rps"}""",
                """{"kind":"tictactoe"}""",
                """{"kind":"tictactoe","prize":"none"}""",
                """{"kind":"trivia","category":23,"difficulty":"hard"}""",
                """{"kind":"this_or_that","deck":"us"}""",
            ),
            sentBodies,
        )
    }

    @Test fun `play bodies carry only the fields of the kind`() = runTest {
        val api = RisainaApi(client(), client())
        api.gamePlay("g1", GamePlayRequest(GameKinds.TICTACTOE, cell = 4))
        api.gamePlay("g1", GamePlayRequest(GameKinds.TRIVIA, item = 0, choice = 2))
        api.gamePlay("g1", GamePlayRequest(GameKinds.THIS_OR_THAT, picks = listOf(0, 1, 1, 0, 0, 1, 0)))
        api.gameEnd("g1")
        assertEquals(
            listOf(
                """{"kind":"tictactoe","cell":4}""",
                """{"kind":"trivia","item":0,"choice":2}""",
                """{"kind":"this_or_that","picks":[0,1,1,0,0,1,0]}""",
            ),
            sentBodies.take(3),
        )
        assertEquals(listOf("/api/v1/games/g1/play", "/api/v1/games/g1/play", "/api/v1/games/g1/play", "/api/v1/games/g1/end"), sentPaths)
    }
}
