package com.risaina.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.risaina.app.core.data.GameKinds
import com.risaina.app.core.data.GameRepository
import com.risaina.app.core.designsystem.theme.RisainaTheme
import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.GameSuggestions
import com.risaina.app.core.network.QuizDto
import com.risaina.app.core.network.QuizItemDto
import com.risaina.app.core.network.TttDto
import com.risaina.app.feature.messages.games.GameActions
import com.risaina.app.feature.messages.games.GameCard
import com.risaina.app.feature.messages.games.GamePickerSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Games stream: the picker and the new game cards, driven by DTOs (no Hilt graph, no server).
 * The server is authoritative; these check that the cards offer exactly the taps the state allows
 * and never show a secret the DTO doesn't carry.
 */
@RunWith(AndroidJUnit4::class)
class GameCardsTest {
    @get:Rule val rule = createComposeRule()

    private val me = "me"
    private val bea = "bea"

    private fun game(kind: String, status: String = "waiting", ttt: TttDto? = null, quiz: QuizDto? = null) = GameDto(
        id = "g1", coupleId = "c", kind = kind, createdBy = bea, createdAt = "2026-10-26T10:00:00Z",
        updatedAt = "2026-10-26T10:00:00Z", status = status, ttt = ttt, quiz = quiz,
    )

    private fun actions(onTtt: (String, Int) -> Unit = { _, _ -> }) = GameActions(
        move = { _, _ -> }, tttPlay = onTtt, triviaAnswer = { _, _, _ -> }, thisOrThatPick = { _, _ -> },
        thisOrThatUndo = {}, thisOrThatSubmit = {}, rematch = {}, end = {}, challenge = { _, _, _ -> },
        choose = { _, _ -> }, complete = { _, _ -> }, confirm = {}, reject = { _, _ -> }, cancel = {},
        suggestions = { GameSuggestions(emptyList(), emptyList()) }, playAgain = {}, openPicker = {},
    )

    @Test
    fun picker_listsFourGames_andAsksBeforeEndingTheOpenOne() {
        val open = game(GameKinds.TICTACTOE, ttt = TttDto(myMark = "o", myTurn = false))
        var endedAndStarted: Pair<String, String>? = null
        rule.setContent {
            RisainaTheme {
                GamePickerSheet(
                    catalog = GameRepository.DEFAULT_CATALOG, openGame = open, myId = me, partner = "Bea",
                    startingKind = null, onStart = {}, onEndAndStart = { id, opts -> endedAndStarted = id to opts.kind },
                    onEndOpen = {}, onGoTo = {}, onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("You have a game of tic-tac-toe going").assertIsDisplayed()
        rule.onNodeWithText("Waiting for Bea").assertIsDisplayed()
        listOf("Stone Paper Scissors Thread", "Tic-tac-toe", "Trivia duel", "This or That").forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("Let's play").performScrollTo().performClick()
        rule.onNodeWithText("End the current game?").assertIsDisplayed()
        rule.onNodeWithText("End and start").performClick()
        assertEquals("g1" to GameKinds.TICTACTOE, endedAndStarted)
    }

    @Test
    fun ticTacToe_tapPlaysThatCell_onlyOnMyTurn() {
        val played = mutableListOf<Int>()
        val board = listOf("x", null, null, null, null, null, null, null, null)
        rule.setContent {
            RisainaTheme {
                GameCard(
                    game(GameKinds.TICTACTOE, ttt = TttDto(board = board, myMark = "o", xId = bea, turnId = me, myTurn = true, lastCell = 0, moveCount = 1)),
                    me, "Bea", actions(onTtt = { _, cell -> played += cell }),
                )
            }
        }
        rule.onNodeWithText("Your turn (O)").assertIsDisplayed()
        rule.onNodeWithContentDescription("Row 2, column 3, empty").performClick()
        assertEquals(listOf(5), played)
        rule.onNodeWithContentDescription("Row 1, column 1, X").assertIsNotEnabled()
    }

    @Test
    fun ticTacToe_partnersTurn_cellsAreNotTappable() {
        var taps = 0
        rule.setContent {
            RisainaTheme {
                GameCard(
                    game(GameKinds.TICTACTOE, ttt = TttDto(myMark = "o", xId = bea, turnId = bea, myTurn = false)),
                    me, "Bea", actions(onTtt = { _, _ -> taps++ }),
                )
            }
        }
        rule.onNodeWithText("Bea's turn…").assertIsDisplayed()
        rule.onNodeWithContentDescription("Row 2, column 2, empty").assertIsNotEnabled().performClick()
        assertEquals(0, taps)
    }

    @Test
    fun trivia_partnerStillPlaying_showsNoPartnerPicks() {
        val items = (0 until 5).map { i ->
            QuizItemDto(index = i, text = "Question $i", choices = listOf("A$i", "B$i", "C$i", "D$i"), myChoice = 1, correct = if (i % 2 == 0) 1 else 2)
        }
        val quiz = QuizDto(
            source = "opentdb", attribution = GameRepository.OPENTDB_ATTRIBUTION, total = 5, items = items,
            myAnswered = 5, partnerAnswered = 2, myFinished = true, partnerFinished = false, myScore = 3,
        )
        rule.setContent { RisainaTheme { GameCard(game(GameKinds.TRIVIA, quiz = quiz), me, "Bea", actions()) } }
        rule.onNodeWithText("You got 3 of 5 ✓").assertIsDisplayed()
        rule.onNodeWithText("Waiting for Bea (2/5)…").assertIsDisplayed()
        // Bea's progress count only: no review of her answers, no score, until both finished.
        rule.onNodeWithText("Bea: 2/5").assertIsDisplayed()
        rule.onNodeWithText("See answers").assertDoesNotExist()
        rule.onNodeWithText("You 3", substring = true).assertDoesNotExist()
        rule.onNodeWithText(GameRepository.OPENTDB_ATTRIBUTION).assertIsDisplayed()
        assertTrue(quiz.items.all { it.partnerChoice == null })
    }
}
