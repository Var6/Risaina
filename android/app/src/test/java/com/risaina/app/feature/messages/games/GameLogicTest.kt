package com.risaina.app.feature.messages.games

import com.risaina.app.core.common.AppError
import com.risaina.app.core.data.GameKinds
import com.risaina.app.core.network.GameChallengeDto
import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.QuizDto
import com.risaina.app.core.network.QuizItemDto
import com.risaina.app.core.network.TttDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLogicTest {
    private fun game(kind: String, status: String, ttt: TttDto? = null, quiz: QuizDto? = null, winner: String? = null, loser: String? = null, myMove: String? = null, challenge: GameChallengeDto? = null) = GameDto(
        id = "g", coupleId = "c", kind = kind, createdBy = "a", createdAt = "2026-10-26T10:00:00Z", updatedAt = "2026-10-26T10:00:00Z",
        status = status, ttt = ttt, quiz = quiz, winnerId = winner, loserId = loser, myMove = myMove, challenge = challenge,
    )

    // ---- tic-tac-toe ----

    @Test fun `cells are tappable only on my turn, while waiting, when empty and not busy`() {
        val t = TttDto(board = listOf("x", null, null, null, "o", null, null, null, null), myTurn = true, lastCell = 4)
        val cells = tttCells(t, "waiting", busy = false)
        assertEquals(listOf(1, 2, 3, 5, 6, 7, 8), cells.filter { it.clickable }.map { it.index })
        assertTrue(cells[4].last)
        assertEquals("Row 1, column 1, X", cells[0].label)
        assertEquals("Row 2, column 2, O", cells[4].label)
        assertEquals("Row 3, column 3, empty", cells[8].label)

        assertTrue(tttCells(t.copy(myTurn = false), "waiting", busy = false).none { it.clickable })
        assertTrue(tttCells(t, "waiting", busy = true).none { it.clickable })
        assertTrue(tttCells(t, "revealed", busy = false).none { it.clickable })
    }

    @Test fun `the winning line is highlighted`() {
        val t = TttDto(board = listOf("x", "x", "x", "o", "o", null, null, null, null), line = listOf(0, 1, 2))
        assertEquals(listOf(0, 1, 2), tttCells(t, "revealed", busy = false).filter { it.winning }.map { it.index })
    }

    // ---- trivia ----

    private fun quiz(myAnswered: Int, myFinished: Boolean = false, partnerFinished: Boolean = false) =
        QuizDto(total = 5, myAnswered = myAnswered, myFinished = myFinished, partnerFinished = partnerFinished, items = (0..minOf(myAnswered, 4)).map { QuizItemDto(index = it) })

    @Test fun `trivia stages - answer, review, wait, results`() {
        assertEquals(TriviaStage.Answering(0), triviaStage(quiz(0), "waiting", reviewing = null))
        // Tapped an answer, request still in flight: keep answering that question.
        assertEquals(TriviaStage.Answering(0), triviaStage(quiz(0), "waiting", reviewing = 0))
        assertEquals(TriviaStage.Reviewing(0, last = false), triviaStage(quiz(1), "waiting", reviewing = 0))
        assertEquals(TriviaStage.Answering(1), triviaStage(quiz(1), "waiting", reviewing = null))
        assertEquals(TriviaStage.Reviewing(4, last = true), triviaStage(quiz(5, myFinished = true), "waiting", reviewing = 4))
        assertEquals(TriviaStage.WaitingForPartner, triviaStage(quiz(5, myFinished = true), "waiting", reviewing = null))
        assertEquals(TriviaStage.Results, triviaStage(quiz(5, myFinished = true, partnerFinished = true), "revealed", reviewing = null))
        assertEquals(TriviaStage.Results, triviaStage(quiz(5, myFinished = true, partnerFinished = true), "draw", reviewing = null))
    }

    // ---- This or That ----

    @Test fun `this or that verdicts`() {
        assertEquals("Same brain 🧠💞", thisOrThatVerdict(7, 7))
        assertEquals("Very in sync", thisOrThatVerdict(6, 7))
        assertEquals("Very in sync", thisOrThatVerdict(5, 7))
        assertEquals("Opposites attract", thisOrThatVerdict(4, 7))
        assertEquals("Opposites attract", thisOrThatVerdict(3, 7))
        assertEquals("Wildly different. Perfect.", thisOrThatVerdict(2, 7))
        assertEquals("Wildly different. Perfect.", thisOrThatVerdict(0, 7))
    }

    // ---- whose turn ----

    @Test fun `my turn, per kind and status`() {
        assertTrue(isMyTurn(game(GameKinds.RPS, "waiting"), "me"))
        assertFalse(isMyTurn(game(GameKinds.RPS, "waiting", myMove = "rock"), "me"))
        assertTrue(isMyTurn(game(GameKinds.TICTACTOE, "waiting", ttt = TttDto(myTurn = true)), "me"))
        assertFalse(isMyTurn(game(GameKinds.TICTACTOE, "waiting", ttt = TttDto(myTurn = false)), "me"))
        assertTrue(isMyTurn(game(GameKinds.TRIVIA, "waiting", quiz = QuizDto(myFinished = false)), "me"))
        assertFalse(isMyTurn(game(GameKinds.THIS_OR_THAT, "waiting", quiz = QuizDto(myFinished = true)), "me"))
        assertTrue(isMyTurn(game(GameKinds.TICTACTOE, "revealed", winner = "me", loser = "you"), "me"))
        assertFalse(isMyTurn(game(GameKinds.TICTACTOE, "revealed", winner = "you", loser = "me"), "me"))
        assertTrue(isMyTurn(game(GameKinds.TRIVIA, "challenged", winner = "you", loser = "me"), "me"))
        assertTrue(isMyTurn(game(GameKinds.TRIVIA, "draw"), "me"))
        assertFalse(isMyTurn(game(GameKinds.TRIVIA, "completed"), "me"))
        assertFalse(isMyTurn(game("chess", "waiting"), "me"))
    }

    @Test fun `prize phases`() {
        assertTrue(isPrizePhase(game(GameKinds.TICTACTOE, "revealed")))
        assertTrue(isPrizePhase(game(GameKinds.TICTACTOE, "completed", challenge = GameChallengeDto(chosen = "dare"))))
        assertFalse(isPrizePhase(game(GameKinds.TICTACTOE, "completed"))) // "just for fun" win or a draw called off
        assertFalse(isChallengeUnderway(game(GameKinds.TRIVIA, "revealed")))
        assertTrue(isChallengeUnderway(game(GameKinds.TRIVIA, "chosen")))
    }

    // ---- errors ----

    @Test fun `error messages use the server's codes`() {
        assertEquals("It's Bea's turn", gameErrorMessage(AppError.Conflict("not_your_turn", "x"), GameKinds.TICTACTOE, "Bea", "f"))
        assertEquals("That square is taken", gameErrorMessage(AppError.Conflict("invalid_move", "x"), GameKinds.TICTACTOE, "Bea", "f"))
        assertEquals("Answer the current question first", gameErrorMessage(AppError.Conflict("invalid_move", "x"), GameKinds.TRIVIA, "Bea", "f"))
        assertNull(gameErrorMessage(AppError.Conflict("already_moved", "x"), GameKinds.TRIVIA, "Bea", "f"))
        assertEquals("Finish or end the current game first", gameErrorMessage(AppError.Conflict("game_in_progress", "x"), null, "Bea", "f"))
        assertEquals("Update Risaina to play this game", gameErrorMessage(AppError.Validation("Update Risaina to play this game"), "chess", "Bea", "f"))
        assertEquals("f", gameErrorMessage(AppError.Validation("Request validation failed"), null, "Bea", "f"))
        assertEquals("You're offline", gameErrorMessage(AppError.Offline(), null, "Bea", "f"))
    }
}
