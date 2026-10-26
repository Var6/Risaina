package com.risaina.app.feature.messages.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.risaina.app.core.data.GameRepository
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles

/** The card chrome shared by every game: rounded surface, centred content, an overline. */
@Composable
fun GameCardFrame(overline: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 340.dp).fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraLarge)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(overline, style = RisainaTextStyles.overline, color = Risaina.colors.accentWarm, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** "You won! 🏆", "{partner} wins" or a tie, for any kind. */
@Composable
fun ResultHeadline(iWon: Boolean, iLost: Boolean, partner: String, tie: String = "It's a draw 🤝") {
    Text(
        when { iWon -> "You won! 🏆"; iLost -> "$partner wins this one"; else -> tie },
        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, textAlign = TextAlign.Center, modifier = modifier)
}

enum class ChoiceState { Idle, Picked, Right, Wrong, Dim }

/** A full-width answer row (trivia) or a big option (This or That). */
@Composable
fun ChoiceButton(text: String, state: ChoiceState, enabled: Boolean, modifier: Modifier = Modifier, minHeight: Int = 48, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (state) {
        ChoiceState.Idle -> colors.secondaryContainer to colors.onSecondaryContainer
        ChoiceState.Picked -> colors.primaryContainer to colors.onPrimaryContainer
        ChoiceState.Right -> Risaina.colors.success.copy(alpha = 0.22f) to colors.onSurface
        ChoiceState.Wrong -> Risaina.colors.danger.copy(alpha = 0.18f) to colors.onSurface
        ChoiceState.Dim -> colors.surfaceContainer to colors.onSurface.copy(alpha = 0.55f)
    }
    val mark = when (state) { ChoiceState.Right -> "  ✓"; ChoiceState.Wrong -> "  ✗"; else -> "" }
    Box(
        modifier.fillMaxWidth().heightIn(min = minHeight.dp).clip(MaterialTheme.shapes.large).background(bg)
            .border(1.dp, if (state == ChoiceState.Picked) colors.primary else Color.Transparent, MaterialTheme.shapes.large)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text + mark, style = MaterialTheme.typography.bodyMedium, color = fg, textAlign = TextAlign.Center)
    }
}

/** Small dots for progress: ✓ right, ✗ wrong, ● current, ○ to come. */
@Composable
fun ProgressDots(marks: List<String>, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        marks.forEach { m ->
            Text(
                m, style = MaterialTheme.typography.labelLarge,
                color = when (m) { "✓" -> Risaina.colors.success; "✗" -> Risaina.colors.danger; "●" -> MaterialTheme.colorScheme.primary; else -> Risaina.colors.textMuted },
            )
        }
    }
}

/**
 * The credit Open Trivia Database's licence (CC BY-SA 4.0) asks for, linking to the site. Opened
 * with LocalUriHandler: no resolveActivity() check, which returns null on Android 11+ without a
 * <queries> entry even though a browser would open the link fine.
 */
@Composable
fun AttributionLine(text: String?, url: String = GameRepository.OPENTDB_URL) {
    val uri = LocalUriHandler.current
    Text(
        text ?: GameRepository.OPENTDB_ATTRIBUTION,
        style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted, textAlign = TextAlign.Center,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button) { runCatching { uri.openUri(url) } }
            .padding(4.dp).semantics { contentDescription = "Open Trivia Database website. Questions are licensed CC BY-SA 4.0" },
    )
}
