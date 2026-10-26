package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.network.CreateGameRequest
import com.risaina.app.core.network.DeckDto
import com.risaina.app.core.network.GameCatalogDto
import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.GameEventViewDto
import com.risaina.app.core.network.GameKindDto
import com.risaina.app.core.network.GamePlayRequest
import com.risaina.app.core.network.GameResponse
import com.risaina.app.core.network.GameSuggestions
import com.risaina.app.core.network.QuizCategoryDto
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.ThisOrThatCatalogDto
import com.risaina.app.core.network.TriviaCatalogDto
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Event views are read leniently: a newer server may add fields. */
private val eventJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

/**
 * My copy of a trivia duel after the partner ([partnerId]) answered one more question, or null when
 * that can't be derived locally (not a duel in play, another round, the partner just finished) and
 * the game must be re-fetched instead. Only `partnerAnswered` and `updatedAt` change: the partner's
 * picks and score stay sealed until both have finished, so nothing else in my view moves.
 */
internal fun withPartnerProgress(local: GameDto, event: GameEventViewDto, partnerId: String): GameDto? {
    val q = local.quiz ?: return null
    if (local.kind != GameKinds.TRIVIA || event.kind != GameKinds.TRIVIA) return null
    if (local.status != "waiting" || event.status != "waiting" || event.round != local.round) return null
    val p = event.quiz?.progress?.firstOrNull { it.userId == partnerId } ?: return null
    if (p.finished) return null
    // A catch-up sync can apply an older event after a newer re-fetch: progress never goes back.
    if (p.answered <= q.partnerAnswered) return local
    return local.copy(
        updatedAt = event.updatedAt ?: local.updatedAt,
        quiz = q.copy(partnerAnswered = p.answered),
    )
}

/** Game kinds the server knows. Anything else is a newer game this build can't play. */
object GameKinds {
    const val RPS = "rps"
    const val TICTACTOE = "tictactoe"
    const val TRIVIA = "trivia"
    const val THIS_OR_THAT = "this_or_that"
    val KNOWN = setOf(RPS, TICTACTOE, TRIVIA, THIS_OR_THAT)
    /** Kinds with a winner, and so the optional truth or dare. */
    val COMPETITIVE = setOf(RPS, TICTACTOE, TRIVIA)
}

/** Options for a new game; "Play again" reuses those of the finished one. */
data class GameStartOptions(
    val kind: String = GameKinds.RPS,
    /** "Just for fun": the winner gets no truth or dare. Competitive kinds only. */
    val justForFun: Boolean = false,
    /** Trivia: a curated category id, or null for any. */
    val category: Int? = null,
    /** Trivia: "easy" | "medium" | "hard", or null for any. */
    val difficulty: String? = null,
    /** This or That: a deck id, or null for the mix. */
    val deck: String? = null,
) {
    /**
     * Only the options the kind accepts. The default prize is expressed by leaving `prize` out:
     * the server accepts only "none", and an older server rejects any extra key at all.
     */
    fun toRequest(): CreateGameRequest = CreateGameRequest(
        kind = kind,
        prize = if (justForFun && kind in GameKinds.COMPETITIVE) "none" else null,
        category = category.takeIf { kind == GameKinds.TRIVIA },
        difficulty = difficulty.takeIf { kind == GameKinds.TRIVIA },
        deck = deck.takeIf { kind == GameKinds.THIS_OR_THAT },
    )

    companion object {
        /** This or That always reports prize "none" (it has no winner): that is not "just for fun". */
        fun from(g: GameDto) = GameStartOptions(
            kind = g.kind,
            justForFun = g.prize == "none" && g.kind in GameKinds.COMPETITIVE,
            // What was asked for, not what "any" happened to resolve to, so "any" stays any.
            category = g.quiz?.requestedCategoryId.takeIf { g.kind == GameKinds.TRIVIA },
            difficulty = g.quiz?.difficulty.takeIf { g.kind == GameKinds.TRIVIA },
            deck = g.quiz?.deck.takeIf { g.kind == GameKinds.THIS_OR_THAT },
        )
    }
}

/**
 * Two-player games shown inside the chat: stone–paper–scissors–thread, tic-tac-toe, a trivia duel
 * and This or That, with an optional blind truth or dare for the winner. The server returns a
 * viewer-specific view (secrets such as the partner's move or picks, and trivia answers, stay
 * hidden until they may be shown), so events just trigger a re-fetch of that game.
 */
@Singleton
class GameRepository @Inject constructor(private val api: RisainaApi) {
    private val _games = MutableStateFlow<List<GameDto>>(emptyList())
    val games: StateFlow<List<GameDto>> = _games
    private var suggestions: GameSuggestions? = null
    private var catalog: GameCatalogDto? = null

    /**
     * This or That picks made but not yet sent, per game. The prompts are not secret, so the card
     * collects all picks locally and sends them in one request; kept here (not in the screen) so
     * leaving the chat mid-round doesn't lose them.
     */
    private val _drafts = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    val drafts: StateFlow<Map<String, List<Int>>> = _drafts

    private fun put(g: GameDto) = _games.update { list -> (listOf(g) + list.filter { it.id != g.id }).sortedByDescending { it.createdAt } }

    /**
     * Games whose copy is behind an event that a catch-up batch applied without re-fetching
     * ([onEvent] with `defer`). [flushStale] re-fetches each one once, at the end of the batch.
     * Kept in memory like the games list: if the process dies, both are gone and the next open
     * loads the list afresh. Concurrent, because the socket and a push-triggered sync can overlap.
     */
    private val stale: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun refresh(): AppResult<Unit> {
        // Unmarked before the call, so a game an event marks during it is still fetched next time.
        val due = stale.toSet().also { stale.removeAll(it) }
        return apiCall { api.games() }.onSuccess { _games.value = it.items }
            .onFailure { if (it.isRetryable) stale.addAll(due) }.map { }
    }

    /** Re-fetches one game. When offline (or the server is busy) it stays marked for the next flush. */
    suspend fun refreshOne(id: String): AppResult<Unit> {
        stale.remove(id)
        return apiCall { api.game(id) }.onSuccess { put(it.game) }
            .onFailure { if (it.isRetryable) stale.add(id) }.map { }
    }

    /**
     * A `game.updated` event (socket or catch-up sync). Only the server can build this viewer's
     * view, so the game is re-fetched, with two savings that keep a push-triggered sync inside its
     * few seconds (RisainaMessagingService allows 8):
     * - The partner answering one more trivia question changes nothing in my view except their
     *   progress count, which the event carries, so it is applied locally.
     * - In a catch-up batch ([defer]), the game is only marked, and [flushStale] fetches it once
     *   after the batch. A trivia duel is up to 12 events and a full replay holds every past game:
     *   one GET each would blow the budget, and the copy fetched for the first event already shows
     *   the rest.
     */
    suspend fun onEvent(id: String, action: String?, actorId: String, fromPartner: Boolean, view: JsonObject?, defer: Boolean = false) {
        // A game already due for a re-fetch: that fetch shows this event too, so don't patch an old copy.
        if (id !in stale && fromPartner && action == "answered" && view != null) {
            val event = runCatching { eventJson.decodeFromJsonElement(GameEventViewDto.serializer(), view) }.getOrNull()
            val local = _games.value.firstOrNull { it.id == id }
            val merged = if (event != null && local != null) withPartnerProgress(local, event, actorId) else null
            if (merged != null) { put(merged); return }
        }
        if (defer) stale.add(id) else refreshOne(id)
    }

    /**
     * Re-fetches the games a catch-up batch marked, once each. After a full replay ([full]: a new
     * sign-in or pairing), or with several games due, one list request replaces them all. Stops at
     * the first retryable failure; whatever is left stays marked for the next sync.
     */
    suspend fun flushStale(full: Boolean = false) {
        if (stale.isEmpty()) return
        if (full || stale.size > MAX_SINGLE_REFRESHES) { refresh(); return }
        while (true) {
            val id = stale.firstOrNull() ?: return
            if (refreshOne(id).errorOrNull()?.isRetryable == true) return
        }
    }

    suspend fun start(opts: GameStartOptions = GameStartOptions()): AppResult<GameDto> {
        val r = apiCall { api.createGame(opts.toRequest()) }.onSuccess { put(it.game) }.map { it.game }
        // An open game this phone doesn't know about (an event it missed): load it, so the picker
        // shows it with "End it" instead of only refusing to start another.
        if ((r.errorOrNull() as? AppError.Conflict)?.code == "game_in_progress") refresh()
        return r
    }
    suspend fun move(id: String, move: String) = write(id) { api.gameMove(id, move) }
    suspend fun play(id: String, req: GamePlayRequest) = write(id) { api.gamePlay(id, req) }
    suspend fun end(id: String) = write(id) { api.gameEnd(id) }
    suspend fun rematch(id: String) = write(id) { api.gameRematch(id) }
    suspend fun challenge(id: String, truth: String, dare: String) = write(id) { api.gameChallenge(id, truth.trim(), dare.trim()) }
    suspend fun choose(id: String, kind: String) = write(id) { api.gameChoose(id, kind) }
    suspend fun confirm(id: String) = write(id) { api.gameConfirm(id) }
    suspend fun reject(id: String, note: String?) = write(id) { api.gameReject(id, note?.trim()?.ifEmpty { null }) }
    suspend fun complete(id: String, answer: String?) = write(id) { api.gameComplete(id, answer?.trim()?.ifEmpty { null }) }
    suspend fun cancel(id: String) = write(id) { api.gameCancel(id) }

    /**
     * A 409 means our copy is stale (the partner moved, the game ended…). The server sends the
     * current game in the error details, but AppError.Conflict doesn't carry them, so re-fetch.
     */
    private suspend fun write(id: String, call: suspend () -> GameResponse): AppResult<Unit> {
        val r = apiCall { call() }.onSuccess { put(it.game) }
        if ((r as? AppResult.Failure)?.error is AppError.Conflict) refreshOne(id)
        return r.map { }
    }

    fun setDraft(id: String, picks: List<Int>) = _drafts.update { it + (id to picks) }
    fun clearDraft(id: String) = _drafts.update { it - id }

    /**
     * What the picker offers. Cached once loaded. A 404 means a server from before these games
     * (GET /games/catalog falls through to /games/:id there): offer stone-paper-scissors only.
     * Offline: the built-in copy. Neither is cached, so the next open asks again: the server may
     * be deployed (or rolled forward) while the app keeps running.
     */
    suspend fun catalog(): GameCatalogDto {
        catalog?.let { return it }
        return when (val r = apiCall { api.gameCatalog() }) {
            is AppResult.Success -> r.value.also { catalog = it }
            is AppResult.Failure -> if (r.error is AppError.NotFound) LEGACY_CATALOG else DEFAULT_CATALOG
        }
    }

    suspend fun suggestions(): GameSuggestions {
        suggestions?.let { return it }
        return (apiCall { api.gameSuggestions() } as? AppResult.Success)?.value?.also { suggestions = it }
            ?: GameSuggestions(
                truths = listOf("What did you first notice about me?", "What's a moment with me you replay in your head?"),
                dares = listOf("Send me a voice note singing our song", "Take a selfie right now, no retakes"),
            )
    }

    fun clear() { _games.value = emptyList(); _drafts.value = emptyMap(); catalog = null; stale.clear() }

    companion object {
        /** More games than this due after a batch: one list request instead of one GET each. */
        private const val MAX_SINGLE_REFRESHES = 3

        private val RPS_KIND = GameKindDto(GameKinds.RPS, "Stone Paper Scissors Thread", "✊", "One secret move each. Thread beats stone and paper; scissors cut thread.", true)

        /** Mirrors GET /games/catalog, for when it can't be loaded (offline). */
        val DEFAULT_CATALOG = GameCatalogDto(
            kinds = listOf(
                RPS_KIND,
                GameKindDto(GameKinds.TICTACTOE, "Tic-tac-toe", "❌", "Take turns on a 3×3 board. Three in a row wins.", true),
                GameKindDto(GameKinds.TRIVIA, "Trivia duel", "🧠", "The same 5 questions for both of you. Most right answers wins.", true),
                GameKindDto(GameKinds.THIS_OR_THAT, "This or That", "💞", "7 quick picks, answered blind. See how in sync you are.", false),
            ),
            trivia = TriviaCatalogDto(
                questionCount = 5, difficulties = listOf("easy", "medium", "hard"),
                categories = listOf(
                    9 to "General knowledge", 11 to "Film", 12 to "Music", 14 to "Television", 15 to "Video games",
                    17 to "Science & nature", 21 to "Sports", 22 to "Geography", 23 to "History", 27 to "Animals",
                ).map { (id, name) -> QuizCategoryDto(id, name) },
                attribution = OPENTDB_ATTRIBUTION, attributionUrl = OPENTDB_URL,
            ),
            thisOrThat = ThisOrThatCatalogDto(
                promptCount = 7,
                decks = listOf(
                    DeckDto("mix", "A bit of everything", "🎲"), DeckDto("everyday", "Everyday", "☕"), DeckDto("us", "Us", "💞"),
                    DeckDto("travel_food", "Travel & food", "🌍"), DeckDto("dreams", "Dreams", "✨"),
                ),
            ),
        )

        /** An older server: stone-paper-scissors only, and none of the new options. */
        val LEGACY_CATALOG = GameCatalogDto(kinds = listOf(RPS_KIND), legacyServer = true)

        /**
         * Open Trivia Database's licence (CC BY-SA 4.0) requires this credit wherever its questions
         * show, with links to the source and the licence. The server shortens category labels
         * ("Entertainment: Film" becomes "Film"), and the licence asks for changes to be indicated.
         */
        const val OPENTDB_ATTRIBUTION = "Questions from Open Trivia Database (opentdb.com), CC BY-SA 4.0. Category names shortened."
        const val OPENTDB_URL = "https://opentdb.com"
        const val OPENTDB_LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
    }
}
