package com.risaina.app.feature.messages.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.TttDto

/**
 * A 3×3 board. Nothing here is secret, so the board is the server's, as is: a cell can be tapped
 * only on your turn, while it is empty, and while no move is in flight.
 */
@Composable
fun TicTacToeSection(g: GameDto, ui: GameUiContext, actions: GameActions) {
    val t = g.ttt ?: TttDto()
    // During the truth or dare the board steps aside; the prize section takes over.
    if (isChallengeUnderway(g)) return
    // Plain letters here: the board draws tinted X and O glyphs, which emoji can't match.
    val mine = t.myMark.uppercase()
    val theirs = if (t.myMark == "x") "O" else "X"
    when (g.status) {
        "waiting" -> {
            Text(if (t.myTurn) "Your turn ($mine)" else "${ui.partner}'s turn…", style = MaterialTheme.typography.titleMedium)
            if (t.moveCount == 0 && t.myTurn) Muted("You go first")
        }
        "draw" -> Text("It's a draw 🤝", style = MaterialTheme.typography.titleMedium)
        "completed" -> ResultHeadline(ui.iWon(g), ui.iLost(g), ui.partner, tie = "Called it a draw 🤝")
        // "revealed": the prize section says who won, right under the board.
    }
    Spacer(Modifier.height(4.dp))
    Muted("You play $mine · ${ui.partner} plays $theirs")
    Spacer(Modifier.height(12.dp))
    Board(tttCells(t, g.status, ui.busy)) { cell -> actions.tttPlay(g.id, cell) }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Board(cells: List<TttCell>, onTap: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { Cell(it, onTap) } }
        }
    }
}

@Composable
private fun Cell(cell: TttCell, onTap: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bg = if (cell.winning) colors.primaryContainer else colors.surfaceContainer
    Box(
        Modifier.size(76.dp).clip(MaterialTheme.shapes.medium).background(bg)
            .border(if (cell.last) 2.dp else 1.dp, if (cell.last) colors.primary else colors.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(enabled = cell.clickable, role = Role.Button) { onTap(cell.index) }
            .semantics { contentDescription = cell.label },
        contentAlignment = Alignment.Center,
    ) {
        val (text, color) = when (cell.mark) {
            "x" -> "✕" to Risaina.colors.accentWarm
            "o" -> "◯" to colors.primary
            else -> "" to Color.Transparent
        }
        Text(text, fontSize = 38.sp, color = color)
    }
}
