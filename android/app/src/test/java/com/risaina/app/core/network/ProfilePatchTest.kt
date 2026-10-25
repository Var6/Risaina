package com.risaina.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the exact client path the Profile screen uses to save birthday and city. */
class ProfilePatchTest {
    private val sent = mutableListOf<Pair<HttpMethod, String>>()
    private val sentBodies = mutableListOf<String>()

    private fun client() = HttpClient(MockEngine { req ->
        sent += req.method to req.url.encodedPath
        sentBodies += when (val b = req.body) { is io.ktor.http.content.TextContent -> b.text; is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString(); else -> b.toString() }
        respond(
            """{"user":{"id":"u1","displayName":"Asha","createdAt":"2026-10-25T00:00:00Z","birthday":"1999-03-14","city":{"name":"Delhi","latitude":28.61,"longitude":77.21}}}""",
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }) {
        expectSuccess = true
        install(ContentNegotiation) { json(NetworkModule.json) }
        defaultRequest { url("https://example.test/api/v1/") }
    }

    @Test fun `birthday patch sends the date`() = runTest {
        val api = RisainaApi(client(), client())
        val r = api.patchMeRaw(buildJsonObject { put("birthday", JsonPrimitive("1999-03-14")) })
        assertEquals("/api/v1/me", sent.single().second)
        assertEquals(HttpMethod.Patch, sent.single().first)
        assertEquals("""{"birthday":"1999-03-14"}""", sentBodies.single())
        assertEquals("1999-03-14", r.user.birthday)
        assertEquals("Delhi", r.user.city?.name)
    }

    @Test fun `their widget publish sends mood note and emoji`() = runTest {
        sentBodies.clear()
        val c = HttpClient(MockEngine { req ->
            sentBodies += (req.body as io.ktor.http.content.TextContent).text
            respond("""{"board":{"note":"hi","emoji":"🥺","mood":"Missing you"}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(NetworkModule.json) }; defaultRequest { url("https://example.test/api/v1/") } }
        val r = RisainaApi(c, c).putWidgetBoard(buildJsonObject { put("note", JsonPrimitive("hi")); put("emoji", JsonPrimitive("🥺")); put("mood", JsonPrimitive("Missing you")); put("photoMediaId", JsonNull) })
        assertEquals("""{"note":"hi","emoji":"🥺","mood":"Missing you","photoMediaId":null}""", sentBodies.single())
        assertEquals("Missing you", r.board.mood)
    }

    @Test fun `clearing sends an explicit null`() = runTest {
        RisainaApi(client(), client()).patchMeRaw(buildJsonObject { put("birthday", JsonNull) })
        assertEquals("""{"birthday":null}""", sentBodies.single())
    }

    @Test fun `city patch sends name and coordinates`() = runTest {
        RisainaApi(client(), client()).patchMeRaw(buildJsonObject {
            put("city", buildJsonObject { put("name", JsonPrimitive("Delhi")); put("latitude", JsonPrimitive(28.61)); put("longitude", JsonPrimitive(77.21)) })
        })
        assertEquals("""{"city":{"name":"Delhi","latitude":28.61,"longitude":77.21}}""", sentBodies.single())
    }
}
