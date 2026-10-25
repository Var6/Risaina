package com.risaina.app.core.data

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.GameSuggestions
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stone–paper–scissors–thread games shown inside the chat. The winner writes a truth and a dare,
 * the loser picks one blind, does it, and the winner confirms. The server returns a viewer-specific view
 * (your own move is visible to you, your partner's only after both have played), so events just
 * trigger a re-fetch of that game.
 */
@Singleton
class GameRepository @Inject constructor(private val api: RisainaApi) {
    private val _games = MutableStateFlow<List<GameDto>>(emptyList())
    val games: StateFlow<List<GameDto>> = _games
    private var suggestions: GameSuggestions? = null

    private fun put(g: GameDto) = _games.update { list -> (listOf(g) + list.filter { it.id != g.id }).sortedByDescending { it.createdAt } }

    suspend fun refresh(): AppResult<Unit> = apiCall { api.games() }.onSuccess { _games.value = it.items }.map { }
    suspend fun refreshOne(id: String) { apiCall { api.game(id) }.onSuccess { put(it.game) } }

    suspend fun start(): AppResult<GameDto> = apiCall { api.createGame() }.onSuccess { put(it.game) }.map { it.game }
    suspend fun move(id: String, move: String) = apiCall { api.gameMove(id, move) }.onSuccess { put(it.game) }.map { }
    suspend fun rematch(id: String) = apiCall { api.gameRematch(id) }.onSuccess { put(it.game) }.map { }
    suspend fun challenge(id: String, truth: String, dare: String) = apiCall { api.gameChallenge(id, truth.trim(), dare.trim()) }.onSuccess { put(it.game) }.map { }
    suspend fun choose(id: String, kind: String) = apiCall { api.gameChoose(id, kind) }.onSuccess { put(it.game) }.map { }
    suspend fun confirm(id: String) = apiCall { api.gameConfirm(id) }.onSuccess { put(it.game) }.map { }
    suspend fun reject(id: String, note: String?) = apiCall { api.gameReject(id, note?.trim()?.ifEmpty { null }) }.onSuccess { put(it.game) }.map { }
    suspend fun complete(id: String, answer: String?) = apiCall { api.gameComplete(id, answer?.trim()?.ifEmpty { null }) }.onSuccess { put(it.game) }.map { }
    suspend fun cancel(id: String) = apiCall { api.gameCancel(id) }.onSuccess { put(it.game) }.map { }

    suspend fun suggestions(): GameSuggestions {
        suggestions?.let { return it }
        return (apiCall { api.gameSuggestions() } as? AppResult.Success)?.value?.also { suggestions = it }
            ?: GameSuggestions(
                truths = listOf("What did you first notice about me?", "What's a moment with me you replay in your head?"),
                dares = listOf("Send me a voice note singing our song", "Take a selfie right now, no retakes"),
            )
    }

    fun clear() { _games.value = emptyList() }
}
