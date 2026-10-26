package com.risaina.app.feature.messages.games

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.risaina.app.core.data.GameKinds
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.SecondaryButton
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.network.GameDto

/**
 * A game as a chat card: the kind's own section (board, questions, picks), then the shared blind
 * truth or dare once there is a winner, then the footer (cancel, rematch, play again). A kind this
 * build doesn't know asks for an update instead of guessing.
 */
@Composable
fun GameCard(
    g: GameDto,
    myId: String?,
    partnerName: String,
    actions: GameActions,
    busy: Boolean = false,
    thisOrThatDraft: List<Int> = emptyList(),
) {
    val ui = GameUiContext(myId, partnerName.substringBefore(" "), busy)
    GameCardFrame(overline(g)) {
        when {
            g.status == "cancelled" -> Text("Game cancelled", style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted)
            g.kind == GameKinds.RPS -> RpsSection(g, ui, actions)
            g.kind == GameKinds.TICTACTOE -> TicTacToeSection(g, ui, actions)
            g.kind == GameKinds.TRIVIA -> TriviaSection(g, ui, actions)
            g.kind == GameKinds.THIS_OR_THAT -> ThisOrThatSection(g, ui, actions, thisOrThatDraft)
            else -> Muted("Update Risaina to play this game")
        }
        if (g.kind in GameKinds.KNOWN && isPrizePhase(g)) PrizeSection(g, ui, actions)
        GameFooter(g, ui, actions)
    }
}

private fun overline(g: GameDto): String {
    val round = if (g.round > 1) " · ROUND ${g.round}" else ""
    return when (g.kind) {
        GameKinds.RPS -> "STONE · PAPER · SCISSORS · THREAD$round"
        GameKinds.TICTACTOE -> "TIC-TAC-TOE$round"
        GameKinds.TRIVIA -> listOfNotNull(
            "TRIVIA DUEL", g.quiz?.category?.name?.takeIf { it.isNotBlank() }, g.quiz?.difficulty,
        ).joinToString(" · ").uppercase() + round
        GameKinds.THIS_OR_THAT -> "THIS OR THAT"
        else -> "A NEW GAME"
    }
}

@Composable
private fun GameFooter(g: GameDto, ui: GameUiContext, actions: GameActions) {
    val known = g.kind in GameKinds.KNOWN
    when (g.status) {
        "draw" -> {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(if (g.kind == GameKinds.TRIVIA) "Rematch: 5 new questions" else "Play again", onClick = { actions.rematch(g.id) }, enabled = !ui.busy)
            GhostButton("Call it a draw", onClick = { actions.end(g.id) }, enabled = !ui.busy)
        }
        "completed", "cancelled" -> {
            Spacer(Modifier.height(10.dp))
            if (known) SecondaryButton("Play again", onClick = { actions.playAgain(g) })
            GhostButton("Another game", onClick = actions.openPicker)
        }
        "waiting", "revealed", "challenged" -> {
            Spacer(Modifier.height(4.dp))
            GhostButton("Cancel game", onClick = { actions.cancel(g.id) }, enabled = !ui.busy)
        }
    }
}
