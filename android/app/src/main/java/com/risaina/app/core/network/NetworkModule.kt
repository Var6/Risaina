package com.risaina.app.core.network

import android.util.Log
import com.risaina.app.BuildConfig
import com.risaina.app.core.datastore.SessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Upper bound for one token refresh, which runs even when its caller was cancelled. */
    private const val REFRESH_BUDGET_MS = 15_000L

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    @Provides @Singleton
    fun json(): Json = json

    private fun baseClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        install(WebSockets) { pingIntervalMillis = 30_000 }
        if (BuildConfig.DEBUG) {
            // INFO level logs method + URL only. Headers and bodies are never logged: they carry
            // tokens and private messages.
            install(Logging) {
                level = LogLevel.INFO
                logger = object : Logger { override fun log(message: String) { Log.d("RisainaHttp", message) } }
            }
        }
        defaultRequest {
            url(BuildConfig.API_BASE_URL.trimEnd('/') + "/api/v1/")
            header(HttpHeaders.UserAgent, "Risaina-Android/${BuildConfig.VERSION_NAME}")
        }
    }

    /** No auth: sign-in, refresh, presigned uploads/downloads. */
    @Provides @Singleton @Named("plain")
    fun plainClient(): HttpClient = baseClient()

    /**
     * Bearer client with automatic refresh. Refresh is serialised behind a mutex so parallel 401s
     * do not burn the one-time refresh token (the server rotates it and detects reuse).
     */
    @Provides @Singleton @Named("authed")
    fun authedClient(
        sessionStore: SessionStore,
        @Named("plain") plain: HttpClient,
        sessionEvents: SessionEvents,
    ): HttpClient {
        val refreshMutex = Mutex()
        val apiHost = URI(BuildConfig.API_BASE_URL).host
        return baseClient().config {
            install(Auth) {
                bearer {
                    loadTokens {
                        sessionStore.current()?.let { BearerTokens(it.accessToken, it.refreshToken) }
                    }
                    refreshTokens {
                        refreshMutex.withLock {
                            val current = sessionStore.current() ?: return@withLock null
                            // Another caller may have refreshed while we waited on the mutex.
                            if (oldTokens?.accessToken != current.accessToken) {
                                return@withLock BearerTokens(current.accessToken, current.refreshToken)
                            }
                            // The server rotates the refresh token as soon as it answers, so the request and
                            // storing its answer must finish together: a background sync's time budget (or
                            // any caller's cancellation) must not stop in between, or the phone would keep a
                            // dead token and be signed out on its next refresh. Bounded so a hung request
                            // cannot hold the caller for long; the server's short grace window covers a retry.
                            val result = withContext(NonCancellable) {
                                withTimeoutOrNull(REFRESH_BUDGET_MS) {
                                    runCatching {
                                        plain.post("auth/refresh") {
                                            contentType(ContentType.Application.Json)
                                            // The install id lets the server honour a retry of this
                                            // refresh if its answer is lost (refresh grace window).
                                            setBody(RefreshRequest(current.refreshToken, current.deviceId.ifBlank { null }))
                                        }.body<TokensResponse>()
                                    }.onSuccess { r ->
                                        sessionStore.updateTokens(
                                            r.tokens.accessToken, Instant.parse(r.tokens.accessExpiresAt),
                                            r.tokens.refreshToken, Instant.parse(r.tokens.refreshExpiresAt),
                                        )
                                    }
                                } ?: Result.failure(java.io.IOException("token refresh timed out"))
                            }
                            result.fold(
                                onSuccess = { r -> BearerTokens(r.tokens.accessToken, r.tokens.refreshToken) },
                                onFailure = { e ->
                                    if (e is ClientRequestException && e.response.status.value in setOf(400, 401, 403)) {
                                        sessionEvents.signalExpired()
                                    }
                                    null
                                },
                            )
                        }
                    }
                    // Never attach bearer tokens to presigned storage URLs on other hosts.
                    sendWithoutRequest { request -> request.url.host == apiHost }
                }
            }
        }
    }
}
