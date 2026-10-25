package com.risaina.app.feature.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.components.SecondaryButton
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.GameSuggestions

/** Stone, paper, scissors and thread. Thread beats stone and paper; scissors cut thread. */
private val MOVES = listOf("rock" to "✊", "paper" to "✋", "scissors" to "✌️", "thread" to "🧵")
private fun emojiOf(move: String?) = MOVES.firstOrNull { it.first == move }?.second ?: "❔"
private fun nameOf(move: String?) = when (move) { "rock" -> "Stone"; null -> ""; else -> move.replaceFirstChar { it.uppercase() } }

/** Everything a game card can ask the screen to do. */
data class GameActions(
    val move: (String, String) -> Unit,
    val rematch: (String) -> Unit,
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
    /** A fresh game (a rematch is only for draws). */
    val newGame: () -> Unit,
)

/**
 * Stone, paper, scissors, thread as a chat card. Moves stay secret until both have played. The
 * winner writes one truth AND one dare; the loser picks one without seeing either (both stay
 * sealed until they choose), does it, and it only counts once the winner confirms.
 */
@Composable
fun GameCard(g: GameDto, myId: String?, partnerName: String, actions: GameActions) {
    var showChallenge by remember { mutableStateOf(false) }
    var showReject by remember { mutableStateOf(false) }
    var answer by remember(g.id, g.status) { mutableStateOf("") }
    val partner = partnerName.substringBefore(" ")
    val iWon = g.winnerId != null && g.winnerId == myId
    val iLost = g.loserId != null && g.loserId == myId
    val c = g.challenge
    val chosenLabel = if (c?.chosen == "truth") "truth" else "dare"
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 340.dp).fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraLarge)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("STONE · PAPER · SCISSORS · THREAD" + if (g.round > 1) " · ROUND ${g.round}" else "", style = RisainaTextStyles.overline, color = Risaina.colors.accentWarm)
            Spacer(Modifier.height(10.dp))
            when (g.status) {
                "waiting" -> if (g.myMove == null) {
                    Text("Make your move", style = MaterialTheme.typography.titleMedium)
                    Text(if (g.partnerMoved) "$partner already chose 👀" else "It stays secret until you both play", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MOVES.forEach { (m, e) ->
                            Box(
                                Modifier.size(62.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                                    .clickable { actions.move(g.id, m) }.semantics { contentDescription = "Play ${nameOf(m)}" },
                                contentAlignment = Alignment.Center,
                            ) { Text(e, fontSize = 30.sp) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("🧵 thread beats ✊ stone and ✋ paper · ✌️ scissors cut 🧵 thread", style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted, textAlign = TextAlign.Center)
                } else {
                    Text(emojiOf(g.myMove), fontSize = 44.sp)
                    Text("You chose ${nameOf(g.myMove)}", style = MaterialTheme.typography.titleMedium)
                    Text("Waiting for $partner to play…", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                }
                "draw" -> {
                    Faceoff(g.myMove, g.partnerMove, partner)
                    Text("It's a draw!", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Play again", onClick = { actions.rematch(g.id) })
                }
                "revealed" -> {
                    Faceoff(g.myMove, g.partnerMove, partner)
                    if (iWon) {
                        Text("You won! 🏆", style = MaterialTheme.typography.titleMedium)
                        Text("Write one truth and one dare. $partner picks one without seeing either.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton("Write truth & dare", onClick = { showChallenge = true })
                    } else {
                        Text("$partner won this one", style = MaterialTheme.typography.titleMedium)
                        Text("They're writing a truth and a dare for you 😈", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                    }
                }
                "challenged" -> if (iLost) {
                    Text("Truth or dare?", style = MaterialTheme.typography.titleMedium)
                    Text("Both are sealed. Pick one to open it; there's no switching after.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SealedCard("🤔", "TRUTH", Modifier.weight(1f)) { actions.choose(g.id, "truth") }
                        SealedCard("🔥", "DARE", Modifier.weight(1f)) { actions.choose(g.id, "dare") }
                    }
                } else {
                    Text("Sealed and sent ✉️", style = MaterialTheme.typography.titleMedium)
                    Text("Waiting for $partner to pick truth or dare. They can't see either until they choose.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, textAlign = TextAlign.Center)
                    if (c?.truth != null || c?.dare != null) {
                        Spacer(Modifier.height(10.dp))
                        c?.truth?.let { Written("Your truth", it) }
                        c?.dare?.let { Written("Your dare", it) }
                    }
                }
                "chosen" -> {
                    ChosenHeader(c?.chosen)
                    Text(c?.chosenText ?: "", style = RisainaTextStyles.noteSerif, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    if (iLost) {
                        val note = c?.rejectNote
                        if (!note.isNullOrBlank()) {
                            Text("Sent back by $partner: “$note”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (c?.chosen == "truth") {
                            RisainaTextField(answer, { answer = it.take(500) }, "Your honest answer", singleLine = false, minLines = 2)
                            Spacer(Modifier.height(8.dp))
                            PrimaryButton("Send answer", onClick = { actions.complete(g.id, answer) }, enabled = answer.isNotBlank())
                        } else {
                            Text("Do it, then mark it done. $partner confirms it. A photo or voice note as proof is extra love.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            PrimaryButton("Mark as done ✓", onClick = { actions.complete(g.id, null) })
                        }
                    } else {
                        Text("$partner chose the $chosenLabel. Waiting for them to ${if (c?.chosen == "truth") "answer" else "do it"}…", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, textAlign = TextAlign.Center)
                    }
                }
                "awaiting_confirmation" -> {
                    ChosenHeader(c?.chosen)
                    Text(c?.chosenText ?: "", style = RisainaTextStyles.noteSerif, textAlign = TextAlign.Center)
                    if (!g.answer.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Text("“${g.answer}”", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(12.dp))
                    if (iWon) {
                        Text(if (c?.chosen == "truth") "Honest enough?" else "Did $partner really do it?", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton("Confirm ✓", onClick = { actions.confirm(g.id) })
                            GhostButton("Not quite", onClick = { showReject = true })
                        }
                    } else {
                        Text("Done! Waiting for $partner to confirm ✓", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                    }
                }
                "completed" -> {
                    Text("✓ " + (if (iLost) "You" else partner) + (if (c?.chosen == "truth") " answered the truth" else " did the dare"), style = MaterialTheme.typography.titleMedium)
                    Text("Confirmed by ${if (iWon) "you" else partner}", style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
                    c?.chosenText?.let { Spacer(Modifier.height(4.dp)); Text("“$it”", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, textAlign = TextAlign.Center) }
                    if (!g.answer.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Text(g.answer, style = RisainaTextStyles.noteSerif, textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Play again", onClick = actions.newGame)
                }
                else -> {
                    Text("Game cancelled", style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted)
                    Spacer(Modifier.height(6.dp))
                    GhostButton("New game", onClick = actions.newGame)
                }
            }
            if (g.status in setOf("waiting", "revealed", "draw", "challenged")) {
                Spacer(Modifier.height(4.dp))
                GhostButton("Cancel game", onClick = { actions.cancel(g.id) })
            }
        }
    }
    if (showChallenge) ChallengeSheet(partner = partner, suggestions = actions.suggestions, onDismiss = { showChallenge = false }) { truth, dare ->
        actions.challenge(g.id, truth, dare); showChallenge = false
    }
    if (showReject) RejectDialog(partner, onDismiss = { showReject = false }) { note -> actions.reject(g.id, note); showReject = false }
}

@Composable
private fun SealedCard(emoji: String, label: String, modifier: Modifier, onPick: () -> Unit) {
    Column(
        modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onPick).semantics { contentDescription = "Choose $label. It stays hidden until you pick." }
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 34.sp)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text("? ? ?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
    }
}

@Composable
private fun ChosenHeader(kind: String?) {
    Text(if (kind == "truth") "🤔 TRUTH" else "🔥 DARE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Written(label: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
        Text(text, style = MaterialTheme.typography.bodySmall)
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

/** Winner's prize: write one truth and one dare. Suggestions fill either field with a tap. */
@Composable
private fun ChallengeSheet(partner: String, suggestions: suspend () -> GameSuggestions, onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    var truth by remember { mutableStateOf("") }
    var dare by remember { mutableStateOf("") }
    val ideas by produceState<GameSuggestions?>(null) { value = suggestions() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Your prize", style = MaterialTheme.typography.headlineMedium)
            Text("Write both. $partner picks one blind, so make them equally tempting 😈", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            Spacer(Modifier.height(14.dp))
            Text("🤔 Truth", style = MaterialTheme.typography.titleSmall)
            RisainaTextField(truth, { truth = it.take(200) }, "Ask anything…", singleLine = false, minLines = 2, supporting = "${truth.length}/200")
            IdeaRow(ideas?.truths.orEmpty()) { truth = it }
            Spacer(Modifier.height(12.dp))
            Text("🔥 Dare", style = MaterialTheme.typography.titleSmall)
            RisainaTextField(dare, { dare = it.take(200) }, "Dare them to…", singleLine = false, minLines = 2, supporting = "${dare.length}/200")
            IdeaRow(ideas?.dares.orEmpty()) { dare = it }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Seal & send both", onClick = { onSend(truth.trim(), dare.trim()) }, enabled = truth.isNotBlank() && dare.isNotBlank(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IdeaRow(ideas: List<String>, onPick: (String) -> Unit) {
    if (ideas.isEmpty()) return
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        items(ideas) { idea ->
            Text(
                idea, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                modifier = Modifier.widthIn(max = 220.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { onPick(idea) }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun RejectDialog(partner: String, onDismiss: () -> Unit, onSend: (String?) -> Unit) {
    var note by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Not quite?") },
        text = {
            Column {
                Text("It goes back to $partner to try again. Tell them what's missing (optional).", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                RisainaTextField(note, { note = it.take(200) }, "e.g. I want the voice note, not a text 😄", singleLine = false, minLines = 2)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSend(note.trim().ifEmpty { "Not quite. Try again 😄" }) }) { Text("Send back") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
