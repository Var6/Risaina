package com.risaina.app.feature.ping

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.theme.PillShape
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.domain.PingRules
import com.risaina.app.core.model.PingType

/** Three tabs: quick feelings, a full emoji palette, and gift stickers. One tap on a gift sends it. */
@Composable
fun PingComposerSheet(
    initialType: PingType,
    sending: Boolean,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onSend: (type: PingType, emoji: String, message: String?) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(initialTab) }
    var type by remember { mutableStateOf(initialType) }
    var emoji by remember { mutableStateOf(initialType.defaultEmoji) }
    var message by remember { mutableStateOf("") }
    var group by remember { mutableIntStateOf(0) }
    val emojiError = PingRules.validateEmoji(emoji)
    val messageError = PingRules.validateMessage(message)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Send something small", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf("Feelings", "Emoji", "Gifts").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Spacer(Modifier.height(14.dp))
            when (tab) {
                0 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PingType.entries.filter { it != PingType.CUSTOM }.forEach { t ->
                        FilterChip(
                            selected = type == t, onClick = { type = t; emoji = t.defaultEmoji },
                            label = { Text("${t.defaultEmoji}  ${t.label}") }, shape = PillShape,
                            border = BorderStroke(1.dp, if (type == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
                1 -> Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Expressions.groups.take(3).forEachIndexed { i, g -> GroupChip(g.name, group == i) { group = i } }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Expressions.groups.drop(3).forEachIndexed { i, g -> GroupChip(g.name, group == i + 3) { group = i + 3 } }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyVerticalGrid(columns = GridCells.Fixed(6), modifier = Modifier.heightIn(max = 180.dp)) {
                        items(Expressions.groups[group].emoji) { e ->
                            Box(
                                Modifier.padding(3.dp).size(46.dp).clip(MaterialTheme.shapes.small)
                                    .background(if (emoji == e) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { emoji = e; type = PingType.CUSTOM }
                                    .semantics { contentDescription = "Use $e" },
                                contentAlignment = Alignment.Center,
                            ) { Text(e, fontSize = 24.sp) }
                        }
                    }
                }
                else -> LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Expressions.gifts) { g ->
                        Column(
                            Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .clickable(enabled = !sending) { onSend(PingType.CUSTOM, g.emoji, Expressions.giftMessage(g)) }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                                .semantics { contentDescription = "Send gift: ${g.name}" },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(g.emoji, fontSize = 30.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(g.name, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
                        }
                    }
                }
            }
            if (tab != 2) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RisainaTextField(emoji, { emoji = it.take(8); type = PingType.CUSTOM }, "Emoji", modifier = Modifier.width(100.dp), error = emojiError)
                    Spacer(Modifier.width(12.dp))
                    RisainaTextField(
                        message, { if (it.length <= PingRules.MAX_MESSAGE_LENGTH + 10) message = it }, "A few words (optional)",
                        modifier = Modifier.weight(1f), error = messageError, supporting = "${message.length}/${PingRules.MAX_MESSAGE_LENGTH}",
                    )
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "Send", loading = sending, enabled = emojiError == null && messageError == null,
                    onClick = { onSend(type, emoji.trim(), message.trim().ifBlank { null }) }, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Tap a gift to send it.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, shape = PillShape)
}
