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

/** SOS alerts: kept in memory and refreshed from the server (they are rare and short-lived). */
@Singleton
class SosRepository @Inject constructor(private val api: RisainaApi) {
    private val _items = MutableStateFlow<List<SosDto>>(emptyList())
    val items: StateFlow<List<SosDto>> = _items

    suspend fun refresh(): AppResult<Unit> = apiCall { api.sosList() }.onSuccess { _items.value = it.items }.map { }

    suspend fun send(message: String?): AppResult<SosDto> =
        apiCall { api.createSos(CreateSosRequest(message?.trim()?.takeIf { it.isNotEmpty() })) }
            .onSuccess { r -> _items.update { listOf(r.sos) + it.filter { s -> s.id != r.sos.id } } }
            .map { it.sos }

    suspend fun acknowledge(id: String): AppResult<Unit> =
        apiCall { api.ackSos(id) }.onSuccess { r -> upsert(r.sos) }.map { }

    fun upsert(s: SosDto) = _items.update { list -> (listOf(s) + list.filter { it.id != s.id }).sortedByDescending { it.createdAt } }
    fun markAcknowledged(id: String, at: String) = _items.update { list -> list.map { if (it.id == id) it.copy(acknowledgedAt = at) else it } }
}
