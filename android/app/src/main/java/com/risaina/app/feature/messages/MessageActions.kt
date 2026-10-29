package com.risaina.app.feature.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.theme.FrauncesText
import com.risaina.app.core.designsystem.theme.PillShape
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.domain.CalendarText
import com.risaina.app.core.domain.DateHit
import com.risaina.app.feature.common.CopyIcon
import com.risaina.app.feature.common.DeleteIcon

/**
 * Long-press on a sent text message, from either of you: put it in the calendar, make it a to-do,
 * copy it, or delete it (your own only, through the usual confirmation).
 */
@Composable
fun MessageActionsSheet(
    text: String,
    calendar: Boolean,
    canDelete: Boolean,
    onAddToCalendar: () -> Unit,
    onMakeTodo: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(
                "“${text.trim()}”", fontFamily = FrauncesText, style = MaterialTheme.typography.bodyLarge, color = Risaina.colors.textMuted,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp),
            )
            if (calendar) ActionRow("📅", "Add to calendar", "Pick up the date if there is one") { onDismiss(); onAddToCalendar() }
            ActionRow("✅", "Make it a to-do", "With a date it goes in the calendar, without one in To-dos") { onDismiss(); onMakeTodo() }
            ActionRow(CopyIcon, "Copy") { onDismiss(); onCopy() }
            if (canDelete) ActionRow(DeleteIcon, "Delete") { onDismiss(); onDelete() }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

/** The composer's "+": a game, a calendar plan or a to-do, from what is typed when there is something. */
@Composable
fun ComposerAddSheet(
    hasDraft: Boolean,
    calendar: Boolean,
    onGame: () -> Unit,
    onAddToCalendar: () -> Unit,
    onAddTodo: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Add", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp))
            ActionRow("🎲", "Play a game", "Rock paper scissors, trivia and more") { onDismiss(); onGame() }
            if (calendar) ActionRow("📅", "Add to calendar", if (hasDraft) "From what you've typed" else "A plan for the two of you") { onDismiss(); onAddToCalendar() }
            ActionRow("✅", "Add a to-do", if (hasDraft) "From what you've typed" else "Opens your To-dos") { onDismiss(); onAddTodo() }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun ActionRow(emoji: String, title: String, subtitle: String? = null, onClick: () -> Unit) =
    ActionRowFrame(title, subtitle, onClick) { Text(emoji, style = MaterialTheme.typography.titleLarge) }

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) =
    ActionRowFrame(title, subtitle, onClick) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }

@Composable
private fun ActionRowFrame(title: String, subtitle: String?, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
    }
}

/** Above the input when the draft names a date: "📅 Fri 14 Nov, 8:00 pm · Add to calendar". */
@Composable
fun DraftDateChip(hit: DateHit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "📅 ${CalendarText.dayTime(hit.date, hit.time)} · Add to calendar",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.minimumInteractiveComponentSize().clip(PillShape).background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClickLabel = "Add to calendar", onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Under a message that has an entry in the calendar: "📅 In your calendar · Fri 14 Nov". */
@Composable
fun InCalendarChip(day: java.time.LocalDate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "📅 In your calendar · ${CalendarText.day(day)}",
        style = RisainaTextStyles.timestamp, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.minimumInteractiveComponentSize().clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = "Open in the calendar", onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
