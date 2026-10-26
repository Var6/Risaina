package com.risaina.app.feature.messages.games

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.SecondaryButton
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.network.GameDto
import com.risaina.app.core.network.QuizDto
import com.risaina.app.core.network.QuizItemDto

/**
 * The same five questions for both, one at a time. The server only sends the questions you have
 * reached and the right answer of the ones you answered; your partner's answers and score arrive
 * only once you have both finished.
 */
@Composable
fun TriviaSection(g: GameDto, ui: GameUiContext, actions: GameActions) {
    val q = g.quiz ?: QuizDto()
    // "I just answered this one": keeps the feedback up until "Next question". Keyed by round so a
    // rematch starts clean.
    var reviewing by remember(g.id, g.round) { mutableStateOf<Int?>(null) }
    var pending by remember(g.id, g.round) { mutableStateOf<Pair<Int, Int>?>(null) }
    val showAnswers = remember(g.id, g.round) { mutableStateOf(false) }
    val challengeUnderway = isChallengeUnderway(g)

    val stage = triviaStage(q, g.status, reviewing)
    if (!challengeUnderway) {
        ProgressDots(progressMarks(q, (stage as? TriviaStage.Answering)?.item))
        Muted("${ui.partner}: ${q.partnerAnswered}/${q.total}" + if (q.partnerFinished) " ✓" else "")
        Spacer(Modifier.height(10.dp))
    }
    when (stage) {
        is TriviaStage.Answering -> q.items.getOrNull(stage.item)?.let { item ->
            QuestionText(item, stage.item, q.total)
            // The tapped answer stays highlighted while its request is in flight; if it fails, busy
            // clears and the question can simply be answered again.
            val inFlight = pending?.takeIf { ui.busy && it.first == stage.item }?.second
            item.choices.forEachIndexed { i, choice ->
                ChoiceButton(choice, if (inFlight == i) ChoiceState.Picked else ChoiceState.Idle, enabled = !ui.busy) {
                    pending = stage.item to i
                    reviewing = stage.item
                    actions.triviaAnswer(g.id, stage.item, i)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        is TriviaStage.Reviewing -> q.items.getOrNull(stage.item)?.let { item ->
            QuestionText(item, stage.item, q.total)
            item.choices.forEachIndexed { i, choice ->
                ChoiceButton(choice, reviewState(item, i), enabled = false) {}
                Spacer(Modifier.height(6.dp))
            }
            Text(if (item.myChoice == item.correct) "Right! 🎉" else "Not this time", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            SecondaryButton(if (stage.last) "See results" else "Next question →", onClick = { reviewing = null })
        }
        TriviaStage.WaitingForPartner -> {
            Text("You got ${q.myScore ?: 0} of ${q.total} ✓", style = MaterialTheme.typography.titleMedium)
            Muted("Waiting for ${ui.partner} (${q.partnerAnswered}/${q.total})…")
        }
        TriviaStage.Results -> if (!challengeUnderway) {
            Text("You ${q.myScore ?: 0} · ${q.partnerScore ?: 0} ${ui.partner}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            when (g.status) {
                "draw" -> Text("It's a tie 🤝", style = MaterialTheme.typography.titleMedium)
                "completed" -> ResultHeadline(ui.iWon(g), ui.iLost(g), ui.partner, tie = "Called it a tie 🤝")
            }
            Spacer(Modifier.height(4.dp))
            GhostButton(if (showAnswers.value) "Hide answers" else "See answers", onClick = { showAnswers.value = !showAnswers.value })
            if (showAnswers.value) AnswerReview(q, ui.partner)
        } else {
            Muted("Trivia: you ${q.myScore ?: 0} · ${q.partnerScore ?: 0} ${ui.partner}")
            Spacer(Modifier.height(8.dp))
        }
    }
    if (!challengeUnderway) {
        Spacer(Modifier.height(6.dp))
        if (q.source == "opentdb") AttributionLine(q.attribution) else Muted("Offline question set")
    }
}

@Composable
private fun QuestionText(item: QuizItemDto, index: Int, total: Int) {
    Text("Question ${index + 1} of $total" + (item.category?.let { " · $it" } ?: ""), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
    Spacer(Modifier.height(4.dp))
    Text(item.text.orEmpty(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(10.dp))
}

private fun reviewState(item: QuizItemDto, i: Int): ChoiceState = when {
    i == item.correct -> ChoiceState.Right
    i == item.myChoice -> ChoiceState.Wrong
    else -> ChoiceState.Dim
}

/** ✓ / ✗ for answered questions, ● the one being answered, ○ to come. */
private fun progressMarks(q: QuizDto, current: Int?): List<String> = (0 until q.total).map { i ->
    val item = q.items.getOrNull(i)
    when {
        item?.myChoice != null && item.correct != null -> if (item.myChoice == item.correct) "✓" else "✗"
        i == current -> "●"
        else -> "○"
    }
}

/** Once both finished: every question with both picks and the right answer. */
@Composable
private fun AnswerReview(q: QuizDto, partner: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        q.items.forEach { item ->
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("${item.index + 1}. ${item.text.orEmpty()}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            val right = item.correct?.let { item.choices.getOrNull(it) }
            Text("You: ${pickLabel(item, item.myChoice)}", style = MaterialTheme.typography.bodySmall)
            Text("$partner:${pickLabel(item, item.partnerChoice)}", style = MaterialTheme.typography.bodySmall)
            if (right != null) Text("Answer: $right", style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
        }
    }
}

private fun pickLabel(item: QuizItemDto, pick: Int?): String {
    val text = pick?.let { item.choices.getOrNull(it) } ?: return "–"
    return text + if (pick == item.correct) " ✓" else " ✗"
}
