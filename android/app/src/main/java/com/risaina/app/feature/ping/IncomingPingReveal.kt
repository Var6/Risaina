package com.risaina.app.feature.ping

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.SecondaryButton
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.model.Ping
import kotlinx.coroutines.delay

/** In-app reveal for a ping that arrives while the app is visible: small symbol, restrained card, one-tap reply. */
@Composable
fun IncomingPingReveal(
    ping: Ping?,
    partnerName: String?,
    onReply: (Ping) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = Risaina.motion
    LaunchedEffect(ping?.id) { if (ping != null) { delay(9_000); onDismiss() } }
    AnimatedVisibility(
        visible = ping != null,
        enter = fadeIn(tween(motion.standardMs)) + slideInVertically(motion.springGentle<androidx.compose.ui.unit.IntOffset>()) { -it / 2 },
        exit = fadeOut(tween(motion.fastMs)) + slideOutVertically(tween(motion.fastMs, easing = motion.easeExit)) { -it / 2 },
        modifier = modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val p = ping ?: return@AnimatedVisibility
        RisainaCard(Modifier.fillMaxWidth(), elevated = true, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.emoji, style = RisainaTextStyles.emojiLarge.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("${partnerName ?: "Your partner"} · ${p.type.label}", style = MaterialTheme.typography.titleMedium)
                    Text(p.message ?: p.type.defaultMessage, style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted, maxLines = 2)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                SecondaryButton("Send one back", onClick = { onReply(p) })
                Spacer(Modifier.width(6.dp))
                GhostButton("Later", onClick = onDismiss)
            }
        }
    }
}
