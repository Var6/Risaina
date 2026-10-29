package com.risaina.app.core.network

import com.risaina.app.core.common.AppError
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.UnknownHostException

/** Translate transport and HTTP failures into the small set of errors the UI understands. */
suspend fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this
    is ClientRequestException -> response.toAppError()
    is ServerResponseException -> response.toAppError()
    is HttpRequestTimeoutException, is SocketTimeoutException, is ConnectTimeoutException -> AppError.Timeout()
    is UnknownHostException -> AppError.Offline()
    is IOException -> AppError.Offline()
    else -> AppError.Unknown(this)
}

suspend fun HttpResponse.toAppError(): AppError {
    val body = runCatching { body<ErrorBody>() }.getOrNull()?.error
    val message = body?.message
    return when (status) {
        HttpStatusCode.Unauthorized -> AppError.Unauthorized()
        HttpStatusCode.Forbidden -> AppError.Forbidden(message ?: "You can't do that")
        HttpStatusCode.NotFound -> AppError.NotFound(message ?: "Not found")
        HttpStatusCode.Conflict, HttpStatusCode.Gone -> when (body?.code) {
            "version_conflict" -> AppError.VersionConflict(message ?: "This was changed meanwhile", body?.details as? JsonObject)
            else -> AppError.Conflict(body?.code ?: "conflict", message ?: "That didn't go through", body?.details as? JsonObject)
        }
        HttpStatusCode.TooManyRequests -> AppError.RateLimited(headers["Retry-After"]?.toIntOrNull())
        HttpStatusCode.BadRequest, HttpStatusCode.UnprocessableEntity -> AppError.Validation(message ?: "Please check what you entered")
        HttpStatusCode.PayloadTooLarge -> AppError.Validation(message ?: "That file is too large")
        else -> when {
            body?.code == "google_not_configured" -> AppError.Conflict("google_not_configured", "Google sign-in isn't enabled on the server yet")
            status.value >= 500 -> AppError.Server()
            else -> AppError.Unknown(IllegalStateException("HTTP ${status.value}"))
        }
    }
}

/** The server's current list sent with a lists 409 (details.list), or null when absent or unreadable. */
fun AppError.VersionConflict.list(json: Json): SharedListDto? = decode(json, "list", SharedListDto.serializer())

/** The server's current calendar entry sent with a calendar 409 (details.entry). */
fun AppError.VersionConflict.entry(json: Json): CalendarEntryDto? = decode(json, "entry", CalendarEntryDto.serializer())

/** The server's current alarm sent with an alarms 409 (details.alarm). */
fun AppError.VersionConflict.alarm(json: Json): AlarmDto? = decode(json, "alarm", AlarmDto.serializer())

private fun <T> AppError.VersionConflict.decode(json: Json, key: String, serializer: KSerializer<T>): T? =
    details?.get(key)?.let { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() }
