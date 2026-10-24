package com.risaina.app.feature.ping

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.theme.PillShape
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.domain.PingRules
import com.risaina.app.core.model.PingType

/** Pick a type, optionally a custom emoji and a short line, then send. */
@Composable
fun PingComposerSheet(
    initialType: PingType,
    sending: Boolean,
    onDismiss: () -> Unit,
    onSend: (type: PingType, emoji: String, message: String?) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var type by remember { mutableStateOf(initialType) }
    var emoji by remember { mutableStateOf(initialType.defaultEmoji) }
    var message by remember { mutableStateOf("") }
    val emojiError = PingRules.validateEmoji(emoji)
    val messageError = PingRules.validateMessage(message)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Send a ping", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text("One tap on their phone. Choose what it says.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PingType.entries.forEach { t ->
                    FilterChip(
                        selected = type == t,
                        onClick = { type = t; if (t != PingType.CUSTOM) emoji = t.defaultEmoji },
                        label = { Text(if (t == PingType.CUSTOM) "Custom" else "${t.defaultEmoji}  ${t.label}") },
                        shape = PillShape,
                        border = BorderStroke(1.dp, if (type == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RisainaTextField(
                    emoji, { emoji = it.take(8) }, "Emoji", modifier = Modifier.width(110.dp), error = emojiError,
                )
                Spacer(Modifier.width(12.dp))
                RisainaTextField(
                    message, { if (it.length <= PingRules.MAX_MESSAGE_LENGTH + 10) message = it }, "A few words (optional)",
                    modifier = Modifier.weight(1f), error = messageError,
                    supporting = "${message.length}/${PingRules.MAX_MESSAGE_LENGTH}",
                )
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "Send ping",
                loading = sending,
                enabled = emojiError == null && messageError == null,
                onClick = { onSend(type, emoji.trim(), message.trim().ifBlank { null }) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
