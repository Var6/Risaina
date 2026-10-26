package com.risaina.app.feature.messages.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.QuizDto

/**
 * Seven quick picks, answered blind. The prompts aren't secret, so they all arrive with the game
 * and the picks are collected here ([draft]) and sent in one go after the last one. Your partner's
 * picks stay sealed on the server until you have both finished.
 */
@Composable
fun ThisOrThatSection(g: GameDto, ui: GameUiContext, actions: GameActions, draft: List<Int>) {
    val q = g.quiz ?: QuizDto()
    val total = q.total.takeIf { it > 0 } ?: q.items.size
    when {
        g.status == "waiting" && !q.myFinished -> {
            if (q.partnerFinished && draft.isEmpty()) Muted("${ui.partner} already picked. Your turn 💞")
            val index = draft.size
            val item = q.items.getOrNull(index)
            if (item != null) {
                Text("${index + 1} of $total", style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    item.choices.getOrNull(0)?.let { a ->
                        ChoiceButton(a, ChoiceState.Idle, enabled = !ui.busy, modifier = Modifier.weight(1f), minHeight = 84) { actions.thisOrThatPick(g.id, 0) }
                    }
                    Text("or", style = MaterialTheme.typography.labelLarge, color = Risaina.colors.textMuted)
                    item.choices.getOrNull(1)?.let { b ->
                        ChoiceButton(b, ChoiceState.Idle, enabled = !ui.busy, modifier = Modifier.weight(1f), minHeight = 84) { actions.thisOrThatPick(g.id, 1) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Muted("Your picks stay sealed until ${ui.partner} finishes")
                if (draft.isNotEmpty()) GhostButton("← Change the last one", onClick = { actions.thisOrThatUndo(g.id) }, enabled = !ui.busy)
            } else {
                // All picked; they go out automatically. This shows while sending, or after a failed send.
                Text("All $total picked ✓", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                PrimaryButton(if (ui.busy) "Sending…" else "Send my picks", onClick = { actions.thisOrThatSubmit(g.id) }, loading = ui.busy)
                GhostButton("← Change the last one", onClick = { actions.thisOrThatUndo(g.id) }, enabled = !ui.busy)
            }
        }
        g.status == "waiting" -> {
            Text("All $total picked ✓", style = MaterialTheme.typography.titleMedium)
            Muted("Waiting for ${ui.partner}… Your picks stay sealed until they finish.")
        }
        q.matches != null -> Results(q, ui.partner)
    }
}

@Composable
private fun Results(q: QuizDto, partner: String) {
    val matches = q.matches ?: 0
    val total = q.total.takeIf { it > 0 } ?: q.items.size
    Text("In sync: $matches of $total 💞", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(thisOrThatVerdict(matches, total), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth()) {
        q.items.forEach { item ->
            HorizontalDivider(Modifier.padding(vertical = 5.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val same = item.myChoice != null && item.myChoice == item.partnerChoice
            Text(item.choices.joinToString("  or  "), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
            Text(
                "You: ${item.myChoice?.let { item.choices.getOrNull(it) } ?: "–"}  ·  $partner: ${item.partnerChoice?.let { item.choices.getOrNull(it) } ?: "–"}" +
                    if (same) "  ✓" else "",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start,
            )
        }
    }
}
