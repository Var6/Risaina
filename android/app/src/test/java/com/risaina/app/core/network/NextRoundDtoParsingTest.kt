package com.risaina.app.core.network

import com.risaina.app.core.common.AppError
import com.risaina.app.core.data.toDomain
import com.risaina.app.core.data.toEntity
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.core.model.CalendarSourceKind
import com.risaina.app.core.model.ListMarker
import com.risaina.app.core.model.WakeMode
import com.risaina.app.core.model.WakeOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Lists, calendar and wake-together shapes: what the app reads (tolerantly: a value from a newer
 * app is UNKNOWN, never a crash) and exactly what it sends (older servers have strict bodies).
 */
class NextRoundDtoParsingTest {
    private val json = NetworkModule.json
    private val stamps = """"createdAt":"2026-10-29T10:00:00.000Z","updatedAt":"2026-10-29T11:00:00.000Z""""

    // ---- lists ----

    @Test fun `a list from an older server, without format fields or attribution, gets the defaults`() {
        val l = json.decodeFromString(
            ListResponse.serializer(),
            """{"list":{"id":"l1","coupleId":"c","createdBy":"me","updatedBy":null,"title":"Trip","kind":"checklist","body":"",
               "items":[{"id":"a","text":"Tent","done":true,"doneBy":"bea","doneAt":"2026-10-29T09:00:00.000Z"}],"pinned":false,"version":2,$stamps}}""",
        ).list
        assertEquals("none", l.marker)
        assertTrue(l.checkboxes)
        val d = l.toEntity(json).toDomain(json)
        assertEquals(ListMarker.NONE, d.marker)
        assertTrue(d.checkboxes)
        assertNull(d.items.single().addedBy)
        assertEquals("bea", d.items.single().doneBy)
    }

    @Test fun `format fields and who added an item are read`() {
        val l = json.decodeFromString(
            ListResponse.serializer(),
            """{"list":{"id":"l1","coupleId":"c","createdBy":"me","title":"Steps","kind":"checklist","marker":"number","checkboxes":false,
               "items":[{"id":"a","text":"Book","done":false,"doneBy":null,"doneAt":null,"addedBy":"bea","addedAt":"2026-10-29T09:30:00.000Z"}],
               "pinned":true,"version":4,$stamps}}""",
        ).list.toEntity(json).toDomain(json)
        assertEquals(ListMarker.NUMBER, l.marker)
        assertFalse(l.checkboxes)
        assertEquals("bea", l.items.single().addedBy)
        assertEquals(Instant.parse("2026-10-29T09:30:00Z"), l.items.single().addedAt)
    }

    @Test fun `an unknown marker is UNKNOWN, not a crash`() {
        val l = json.decodeFromString(
            ListResponse.serializer(),
            """{"list":{"id":"l1","coupleId":"c","createdBy":"me","title":"x","kind":"checklist","marker":"roman","items":[],$stamps}}""",
        ).list.toEntity(json).toDomain(json)
        assertEquals(ListMarker.UNKNOWN, l.marker)
    }

    @Test fun `requests without the new fields are what older servers accept`() {
        val create = json.encodeToString(CreateListRequest.serializer(), CreateListRequest("Trip", "checklist", "", emptyList()))
        assertFalse(create.contains("marker") || create.contains("checkboxes") || create.contains("clientKey"))
        val item = json.encodeToString(ListItemDto.serializer(), ListItemDto(id = "a", text = "Tent", done = true))
        assertEquals("""{"id":"a","text":"Tent","done":true}""", item)
        val device = json.encodeToString(DeviceRequest.serializer(), DeviceRequest(deviceId = "d", token = "t", appVersion = "1"))
        assertFalse(device.contains("sdkInt"))
        val convert = json.encodeToString(ConvertListRequest.serializer(), ConvertListRequest("checklist", expectedVersion = 3))
        assertEquals("""{"to":"checklist","expectedVersion":3}""", convert)
    }

    // ---- calendar ----

    private val entryJson = """{"id":"e1","coupleId":"c","createdBy":"me","updatedBy":"bea","kind":"event","title":"Dinner","notes":null,"emoji":"🍝",
        "date":"2026-11-14","endDate":null,"time":"20:00","durationMinutes":90,"timeZone":"Asia/Kolkata","repeat":"none","repeatUntil":null,
        "reminders":[60,1440],"remindUserIds":["me","bea"],"pinned":true,"done":false,"doneAt":null,"doneBy":null,
        "source":{"kind":"message","messageId":"m1"},"clientKey":"k1","version":3,$stamps}"""

    @Test fun `a calendar entry is read into the model`() {
        val e = json.decodeFromString(CalendarEntryResponse.serializer(), """{"entry":$entryJson}""").entry.toDomain()!!
        assertEquals(CalendarKind.EVENT, e.kind)
        assertEquals(LocalDate.of(2026, 11, 14), e.date)
        assertEquals(LocalTime.of(20, 0), e.time)
        assertEquals("Asia/Kolkata", e.zone?.id)
        assertEquals(listOf(60, 1440), e.reminders)
        assertEquals(listOf("me", "bea"), e.remindUserIds)
        assertEquals(CalendarSourceKind.MESSAGE, e.source?.kind)
        assertEquals("m1", e.source?.messageId)
        assertFalse(e.allDay)
    }

    @Test fun `unknown kinds, repeats, source kinds and fields from a newer app decode as UNKNOWN`() {
        val e = json.decodeFromString(
            CalendarListResponse.serializer(),
            """{"items":[{"id":"e2","kind":"habit","title":"Walk","date":"2026-11-01","repeat":"daily","reminders":[],"remindUserIds":["me"],
               "source":{"kind":"photo","mediaId":"x"},"colour":"#ff0000","version":1,$stamps}]}""",
        ).items.single().toDomain()!!
        assertEquals(CalendarKind.UNKNOWN, e.kind)
        assertEquals(CalendarRepeat.UNKNOWN, e.repeat)
        assertEquals(CalendarSourceKind.UNKNOWN, e.source?.kind)
        assertTrue(e.allDay)
    }

    @Test fun `an entry with an unreadable date is skipped, the rest of the calendar still loads`() {
        val items = json.decodeFromString(
            CalendarListResponse.serializer(),
            """{"items":[{"id":"bad","kind":"event","title":"?","date":"someday",$stamps},$entryJson]}""",
        ).items
        assertEquals(listOf("e1"), items.mapNotNull { it.toDomain() }.map { it.id })
    }

    @Test fun `a calendar patch sends only what changed, and explicit nulls for what was cleared`() = runTest {
        var body = ""
        val api = api { req ->
            body = (req.body as TextContent).text
            respond("""{"entry":$entryJson}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        api.patchCalendar("e1", PatchCalendarRequest(title = "Lunch", expectedVersion = 3, clear = setOf("time", "timeZone")))
        val sent = json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("title", "expectedVersion", "time", "timeZone"), sent.keys)
        assertEquals(JsonNull, sent["time"])
        assertFalse("the clear set itself is never sent", body.contains("clear"))
    }

    // ---- wake together ----

    private val alarmJson = """{"id":"a1","coupleId":"c","createdBy":"me","updatedBy":"me","mode":"together","label":null,"time":"06:30",
        "timeZone":"Asia/Kolkata","days":[1,2,3,4,5],"onceAt":null,"enabled":true,"snoozeMinutes":10,"version":2,$stamps,
        "members":[{"userId":"me","ring":true,"skips":["2026-10-30T01:00:00.000Z"],"ready":true,"reason":null,"armedFor":"2026-10-30T01:00:00.000Z","reportedAt":null,
                    "lastWake":{"occurrence":"2026-10-29T01:00:00.000Z","outcome":"up","at":"2026-10-29T01:04:00.000Z","snoozes":1}},
                   {"userId":"bea","ring":true,"skips":[],"ready":null,"reason":null,"armedFor":null,"reportedAt":null,"lastWake":null}]}"""

    @Test fun `an alarm is read with both members`() {
        val a = json.decodeFromString(AlarmResponse.serializer(), """{"alarm":$alarmJson}""").alarm.toDomain()!!
        assertEquals(WakeMode.TOGETHER, a.mode)
        assertEquals(LocalTime.of(6, 30), a.time)
        assertEquals(listOf(1, 2, 3, 4, 5), a.days)
        val me = a.member("me")!!
        assertEquals(WakeOutcome.UP, me.lastWake?.outcome)
        assertEquals(1, me.skips.size)
        assertNull("never reported: a phone without alarms", a.member("bea")!!.ready)
    }

    @Test fun `an alarm in a newer mode, with a newer outcome, decodes and is marked UNKNOWN`() {
        val a = json.decodeFromString(
            AlarmListResponse.serializer(),
            """{"items":[${alarmJson.replace("\"together\"", "\"local\"").replace("\"up\"", "\"snoozed\"")}]}""",
        ).items.single().toDomain()!!
        assertEquals(WakeMode.UNKNOWN, a.mode)
        assertEquals(WakeOutcome.UNKNOWN, a.member("me")!!.lastWake?.outcome)
    }

    @Test fun `a readiness report sends its nulls`() = runTest {
        var body = ""
        val api = api { req -> body = (req.body as TextContent).text; respond("", HttpStatusCode.NoContent) }
        api.reportAlarms(AlarmReportRequest(listOf(AlarmReportItemDto("a1", ready = false, reason = "exact_alarms_off"))))
        assertEquals("""{"items":[{"alarmId":"a1","ready":false,"reason":"exact_alarms_off","armedFor":null}]}""", body)
    }

    // ---- errors ----

    @Test fun `a 409 version_conflict carries the server's copy, other 409s keep their code`() = runTest {
        val conflict = api {
            respond(
                """{"error":{"code":"version_conflict","message":"Changed","details":{"entry":$entryJson}}}""",
                HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val e = runCatching { conflict.patchCalendar("e1", PatchCalendarRequest(title = "x", expectedVersion = 1)) }.exceptionOrNull()!!.toAppError()
        assertTrue(e is AppError.VersionConflict)
        assertEquals(3, (e as AppError.VersionConflict).entry(json)?.version)
        assertNull(e.list(json))

        val other = api {
            respond("""{"error":{"code":"game_in_progress","message":"Busy","details":{"game":{"id":"g"}}}}""", HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val g = runCatching { other.games() }.exceptionOrNull()!!.toAppError() as AppError.Conflict
        assertEquals("game_in_progress", g.code)
        assertTrue(g.details!!.containsKey("game"))
    }

    private fun api(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): RisainaApi {
        val client = HttpClient(MockEngine(handler)) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
            defaultRequest { url("https://api.test/api/v1/") }
        }
        return RisainaApi(client, client)
    }
}
