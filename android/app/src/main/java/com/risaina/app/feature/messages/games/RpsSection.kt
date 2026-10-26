package com.risaina.app.feature.messages.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.network.GameDto

/** Stone, paper, scissors and thread. Thread beats stone and paper; scissors cut thread. */
private val MOVES = listOf("rock" to "✊", "paper" to "✋", "scissors" to "✌️", "thread" to "🧵")
private fun emojiOf(move: String?) = MOVES.firstOrNull { it.first == move }?.second ?: "❔"
private fun nameOf(move: String?) = when (move) { "rock" -> "Stone"; null -> ""; else -> move.replaceFirstChar { it.uppercase() } }

/** Moves stay secret until both have played. The truth or dare that follows is in PrizeSection. */
@Composable
fun RpsSection(g: GameDto, ui: GameUiContext, actions: GameActions) {
    when (g.status) {
        "waiting" -> if (g.myMove == null) {
            Text("Make your move", style = MaterialTheme.typography.titleMedium)
            Muted(if (g.partnerMoved) "${ui.partner} already chose 👀" else "It stays secret until you both play")
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MOVES.forEach { (m, e) ->
                    Box(
                        Modifier.size(62.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable(enabled = !ui.busy) { actions.move(g.id, m) }.semantics { contentDescription = "Play ${nameOf(m)}" },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 30.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("🧵 thread beats ✊ stone and ✋ paper · ✌️ scissors cut 🧵 thread", style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted, textAlign = TextAlign.Center)
        } else {
            Text(emojiOf(g.myMove), fontSize = 44.sp)
            Text("You chose ${nameOf(g.myMove)}", style = MaterialTheme.typography.titleMedium)
            Muted("Waiting for ${ui.partner} to play…")
        }
        "draw" -> {
            Faceoff(g.myMove, g.partnerMove, ui.partner)
            Text("It's a draw!", style = MaterialTheme.typography.titleMedium)
        }
        "revealed" -> Faceoff(g.myMove, g.partnerMove, ui.partner)
        // "Just for fun" ends right at the result.
        "completed" -> if (g.challenge == null) {
            if (g.partnerMove != null) Faceoff(g.myMove, g.partnerMove, ui.partner)
            ResultHeadline(ui.iWon(g), ui.iLost(g), ui.partner, tie = "Called it a draw 🤝")
        }
    }
}

@Composable
private fun Faceoff(mine: String?, theirs: String?, partner: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(emojiOf(mine), fontSize = 40.sp); Text("You", style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted) }
        Text("  vs  ", style = MaterialTheme.typography.titleSmall, color = Risaina.colors.textMuted)
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(emojiOf(theirs), fontSize = 40.sp); Text(partner, style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted) }
    }
    Spacer(Modifier.height(8.dp))
}
