package com.risaina.app.feature.messages.games

import com.risaina.app.core.common.AppError
import com.risaina.app.core.data.GameKinds
import com.risaina.app.core.data.GameRepository
import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.QuizDto
import com.risaina.app.core.network.TttDto

/**
 * Pure decisions behind the game cards (no Compose), so they are unit-tested on the JVM.
 * The server is authoritative; these only decide what to show and what may be tapped.
 */

val CLOSED_STATUSES = setOf("completed", "cancelled")

/** The truth-or-dare part of a game: after the winner is known, until it is confirmed. */
fun isPrizePhase(g: GameDto): Boolean =
    g.status in setOf("revealed", "challenged", "chosen", "awaiting_confirmation") || (g.status == "completed" && g.challenge != null)

/** Prize phases after the winner wrote the truth and dare: the board or scores step aside. */
fun isChallengeUnderway(g: GameDto): Boolean =
    g.status in setOf("challenged", "chosen", "awaiting_confirmation") || (g.status == "completed" && g.challenge != null)

/** Whether the next step is the viewer's (picker banner, auto-scroll). */
fun isMyTurn(g: GameDto, myId: String?): Boolean {
    val iWon = myId != null && g.winnerId == myId
    val iLost = myId != null && g.loserId == myId
    return when (g.status) {
        "waiting" -> when (g.kind) {
            GameKinds.RPS -> g.myMove == null
            GameKinds.TICTACTOE -> g.ttt?.myTurn == true
            GameKinds.TRIVIA, GameKinds.THIS_OR_THAT -> g.quiz?.myFinished == false
            else -> false
        }
        "draw" -> true
        "revealed", "awaiting_confirmation" -> iWon
        "challenged", "chosen" -> iLost
        else -> false
    }
}

fun gameTitle(kind: String): String = when (kind) {
    GameKinds.RPS -> "Stone Paper Scissors Thread"
    GameKinds.TICTACTOE -> "Tic-tac-toe"
    GameKinds.TRIVIA -> "Trivia duel"
    GameKinds.THIS_OR_THAT -> "This or That"
    else -> "A new game"
}

/** For sentences: "You have a trivia duel going". */
fun gamePhrase(kind: String): String = when (kind) {
    GameKinds.RPS -> "a game of Stone Paper Scissors Thread"
    GameKinds.TICTACTOE -> "a game of tic-tac-toe"
    GameKinds.TRIVIA -> "a trivia duel"
    GameKinds.THIS_OR_THAT -> "a round of This or That"
    else -> "a game"
}

// ---- tic-tac-toe ----

data class TttCell(val index: Int, val mark: String?, val clickable: Boolean, val winning: Boolean, val last: Boolean) {
    val row get() = index / 3 + 1
    val col get() = index % 3 + 1
    /** Screen-reader label, e.g. "Row 1, column 3, X". */
    val label get() = "Row $row, column $col, " + when (mark) { "x" -> "X"; "o" -> "O"; else -> "empty" }
}

/** Cells can be tapped only on your turn, while the game is on, when empty, and not mid-request. */
fun tttCells(t: TttDto, status: String, busy: Boolean): List<TttCell> {
    val canPlay = status == "waiting" && t.myTurn && !busy
    val line = t.line.orEmpty().toSet()
    return (0 until 9).map { i ->
        val mark = t.board.getOrNull(i)
        TttCell(i, mark, canPlay && mark == null, i in line, i == t.lastCell)
    }
}

// ---- trivia ----

sealed interface TriviaStage {
    /** Answer question [item] (0-based). */
    data class Answering(val item: Int) : TriviaStage
    /** Just answered [item]: show right/wrong before moving on. */
    data class Reviewing(val item: Int, val last: Boolean) : TriviaStage
    data object WaitingForPartner : TriviaStage
    data object Results : TriviaStage
}

/**
 * [reviewing] is the card's local "I just answered this one" index. It only counts once the server
 * has the answer (myAnswered > item); a failed request falls back to answering that question again.
 */
fun triviaStage(q: QuizDto, status: String, reviewing: Int?): TriviaStage = when {
    reviewing != null && reviewing < q.myAnswered -> TriviaStage.Reviewing(reviewing, last = reviewing + 1 >= q.total)
    status == "waiting" && !q.myFinished -> TriviaStage.Answering(q.myAnswered)
    status == "waiting" && !q.partnerFinished -> TriviaStage.WaitingForPartner
    else -> TriviaStage.Results
}

// ---- This or That ----

/** A warm one-liner for the match count; never a judgement. */
fun thisOrThatVerdict(matches: Int, total: Int): String = when {
    total <= 0 -> ""
    matches >= total -> "Same brain 🧠💞"
    matches * 7 >= total * 5 -> "Very in sync"
    matches * 7 >= total * 3 -> "Opposites attract"
    else -> "Wildly different. Perfect."
}

// ---- errors ----

/**
 * Snackbar text for a failed game action; null means stay quiet (the repository already re-fetched
 * the game, and the card now shows what happened). The server's messages are kept where they are
 * written for people (e.g. "Update Risaina to play this game").
 */
fun gameErrorMessage(error: AppError, kind: String?, partner: String, fallback: String): String? = when (error) {
    is AppError.Conflict -> when (error.code) {
        "not_your_turn" -> "It's $partner's turn"
        "invalid_move" -> if (kind == GameKinds.TICTACTOE) "That square is taken" else "Answer the current question first"
        "already_moved" -> null
        "game_in_progress" -> "Finish or end the current game first"
        "invalid_game_state" -> "The game has moved on"
        else -> error.message ?: fallback
    }
    is AppError.Validation -> if (error.message == "Request validation failed") fallback else error.message ?: fallback
    else -> error.message ?: fallback
}

// ---- attribution ----

/**
 * The links in the Open Trivia Database credit, as [start, end) ranges: the source's name to the
 * site and "CC BY-SA 4.0" to the licence (the licence asks for both). A credit worded some other
 * way links as a whole to the site.
 */
fun attributionLinks(credit: String): List<Triple<Int, Int, String>> {
    val links = mutableListOf<Triple<Int, Int, String>>()
    Regex("""Open Trivia Database( \(opentdb\.com\))?""").find(credit)
        ?.let { links += Triple(it.range.first, it.range.last + 1, GameRepository.OPENTDB_URL) }
    val licence = "CC BY-SA 4.0"
    credit.indexOf(licence).takeIf { it >= 0 }
        ?.let { links += Triple(it, it + licence.length, GameRepository.OPENTDB_LICENSE_URL) }
    return links.ifEmpty { listOf(Triple(0, credit.length, GameRepository.OPENTDB_URL)) }
}
