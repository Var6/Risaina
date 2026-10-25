package com.risaina.app.core.network

import android.util.Log
import com.risaina.app.BuildConfig
import com.risaina.app.core.datastore.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.min

enum class SocketState { DISCONNECTED, CONNECTING, SYNCING, LIVE }

sealed interface SocketMessage {
    data class Ready(val coupleId: String, val latestSeq: Long) : SocketMessage
    data class Event(val event: EventDto) : SocketMessage
    data object Synced : SocketMessage
    data object Revoked : SocketMessage
}

/**
 * Implements the WS protocol from docs/API.md: connect without a token in the URL, send `auth`
 * within 5 s, send `sync{since}`, then stream events. Reconnects with capped exponential backoff
 * while the collector is active; the collector (EventSyncManager) is lifecycle-scoped so no
 * socket is kept alive while the app is in the background.
 */
@Singleton
class EventSocket @Inject constructor(
    @Named("plain") private val client: HttpClient,
    private val sessionStore: SessionStore,
    private val json: Json,
) {
    private val _state = MutableStateFlow(SocketState.DISCONNECTED)
    val state: StateFlow<SocketState> = _state

    private val wsUrl: String by lazy {
        val base = BuildConfig.API_BASE_URL.trimEnd('/')
        val scheme = if (base.startsWith("https")) "wss" else "ws"
        base.replaceFirst(Regex("^https?"), scheme) + "/api/v1/ws"
    }

    /** Emits messages; `sinceProvider` is consulted on every (re)connect so replay is exact. */
    fun connect(sinceProvider: suspend () -> Long): Flow<SocketMessage> = flow {
        var attempt = 0
        while (true) {
            val session = sessionStore.current()
            if (session == null) { _state.value = SocketState.DISCONNECTED; delay(2_000); continue }
            _state.value = SocketState.CONNECTING
            try {
                client.webSocket(wsUrl) {
                    send(Frame.Text(json.encodeToString(WsAuth.serializer(), WsAuth(token = session.accessToken))))
                    val ready = withTimeoutOrNull(5_000) { awaitInbound() }
                    if (ready == null || ready.type != "ready") {
                        throw IllegalStateException("socket did not become ready")
                    }
                    attempt = 0
                    emit(SocketMessage.Ready(ready.coupleId ?: "", ready.latestSeq ?: 0L))
                    _state.value = SocketState.SYNCING
                    send(Frame.Text(json.encodeToString(WsSync.serializer(), WsSync(since = sinceProvider()))))
                    while (true) {
                        val msg = awaitInbound() ?: run { if (BuildConfig.DEBUG) Log.d("RisainaWs", "inbound closed: ${closeReason.await()}"); null } ?: break
                        when (msg.type) {
                            "event" -> msg.event?.let { emit(SocketMessage.Event(it)) }
                            "synced" -> { _state.value = SocketState.LIVE; emit(SocketMessage.Synced) }
                            "revoked" -> { emit(SocketMessage.Revoked); return@webSocket }
                            else -> Unit
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (BuildConfig.DEBUG) Log.d("RisainaWs", "socket cancelled: ${e::class.simpleName} ${e.message} cause=${e.cause?.let { it::class.simpleName + ": " + it.message }}")
                _state.value = SocketState.DISCONNECTED
                throw e
            } catch (e: ClosedReceiveChannelException) {
                if (BuildConfig.DEBUG) Log.d("RisainaWs", "socket closed by server")
            } catch (e: Throwable) {
                if (BuildConfig.DEBUG) Log.d("RisainaWs", "socket error: ${e::class.simpleName}")
            }
            _state.value = SocketState.DISCONNECTED
            val backoffMs = min(30_000L, 1_000L shl min(attempt, 5))
            attempt++
            delay(backoffMs)
        }
    }

    private suspend fun io.ktor.websocket.WebSocketSession.awaitInbound(): WsInbound? {
        while (true) {
            val frame = incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Text) {
                return runCatching { json.decodeFromString(WsInbound.serializer(), frame.readText()) }.getOrNull() ?: continue
            }
        }
    }
}
