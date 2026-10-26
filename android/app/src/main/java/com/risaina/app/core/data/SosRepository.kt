package com.risaina.app.core.data

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.network.CreateSosRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SosDto
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SOS alerts: kept in memory and refreshed from the server (they are rare and short-lived). What this
 * phone already did about an SOS (rang, acknowledged) survives process death in SosLocalState.
 */
@Singleton
class SosRepository @Inject constructor(private val api: RisainaApi) {
    private val _items = MutableStateFlow<List<SosDto>>(emptyList())
    val items: StateFlow<List<SosDto>> = _items

    suspend fun refresh(): AppResult<Unit> = apiCall { api.sosList() }.onSuccess { _items.value = it.items }.map { }

    /**
     * `idempotencyKey` makes a retry safe: the server returns the first SOS instead of ringing twice.
     * `panic` is sent only when true, so an ordinary SOS still works on an older server.
     */
    suspend fun send(message: String?, panic: Boolean = false, idempotencyKey: String? = null): AppResult<SosDto> =
        apiCall { api.createSos(CreateSosRequest(message?.trim()?.takeIf { it.isNotEmpty() }, panic.takeIf { it }, idempotencyKey)) }
            .onSuccess { r -> upsert(r.sos) }
            .map { it.sos }

    suspend fun acknowledge(id: String): AppResult<Unit> =
        apiCall { api.ackSos(id) }.onSuccess { r -> upsert(r.sos) }.map { }

    /** "I'm safe now": stops the partner's alarm and ends the location shared for this SOS. */
    suspend fun resolve(id: String): AppResult<SosDto> =
        apiCall { api.resolveSos(id) }.onSuccess { r -> upsert(r.sos) }.map { it.sos }

    fun upsert(s: SosDto) = _items.update { list -> (listOf(s) + list.filter { it.id != s.id }).sortedByDescending { it.createdAt } }
    fun markAcknowledged(id: String, at: String) = _items.update { list -> list.map { if (it.id == id) it.copy(acknowledgedAt = at) else it } }
    fun markDelivered(id: String, at: String) = _items.update { list -> list.map { if (it.id == id && it.deliveredAt == null) it.copy(deliveredAt = at) else it } }
    fun markResolved(id: String, at: String) = _items.update { list -> list.map { if (it.id == id) it.copy(resolvedAt = at) else it } }
    fun clear() { _items.value = emptyList() }
}
