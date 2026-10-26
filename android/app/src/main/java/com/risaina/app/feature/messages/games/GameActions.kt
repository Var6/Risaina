package com.risaina.app.feature.messages.games

import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.GameSuggestions

/** Everything a game card can ask the screen to do. Ids are game ids. */
data class GameActions(
    /** Stone-paper-scissors: (gameId, move). */
    val move: (String, String) -> Unit,
    /** Tic-tac-toe: (gameId, cell 0..8). */
    val tttPlay: (String, Int) -> Unit,
    /** Trivia: (gameId, item, choice). */
    val triviaAnswer: (String, Int, Int) -> Unit,
    /** This or That: (gameId, choice) for the next prompt; sends all picks once the last is made. */
    val thisOrThatPick: (String, Int) -> Unit,
    /** This or That: take back the last pick (before sending). */
    val thisOrThatUndo: (String) -> Unit,
    /** This or That: send the picks again after a failed send. */
    val thisOrThatSubmit: (String) -> Unit,
    /** After a draw: the next round. */
    val rematch: (String) -> Unit,
    /** After a draw: call it a draw. */
    val end: (String) -> Unit,
    /** Winner: (gameId, truth, dare). */
    val challenge: (String, String, String) -> Unit,
    /** Loser: (gameId, "truth" | "dare"), picked blind. */
    val choose: (String, String) -> Unit,
    /** Loser: (gameId, answer); the answer is required for a truth. */
    val complete: (String, String?) -> Unit,
    /** Winner: they really did it. */
    val confirm: (String) -> Unit,
    /** Winner: not quite, with an optional note; it goes back to the loser. */
    val reject: (String, String?) -> Unit,
    val cancel: (String) -> Unit,
    val suggestions: suspend () -> GameSuggestions,
    /** A new game of the same kind, with the same options. */
    val playAgain: (GameDto) -> Unit,
    /** Open the game picker. */
    val openPicker: () -> Unit,
)

/** What every section needs to know about the viewer. */
data class GameUiContext(val myId: String?, val partner: String, val busy: Boolean) {
    fun iWon(g: GameDto) = g.winnerId != null && g.winnerId == myId
    fun iLost(g: GameDto) = g.loserId != null && g.loserId == myId
}
