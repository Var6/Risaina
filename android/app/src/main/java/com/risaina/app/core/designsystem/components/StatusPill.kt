package com.risaina.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.theme.PillShape
import com.risaina.app.core.designsystem.theme.Risaina

enum class PillTone { Neutral, Info, Success, Warning, Danger }

/** Compact status marker: a dot plus a label. Colour is never the only signal; the text names the state. */
@Composable
fun StatusPill(text: String, tone: PillTone, modifier: Modifier = Modifier) {
    val colors = Risaina.colors
    val (dot, container, label) = when (tone) {
        PillTone.Neutral -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        PillTone.Info -> Triple(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), MaterialTheme.colorScheme.onPrimaryContainer)
        PillTone.Success -> Triple(colors.success, colors.success.copy(alpha = 0.14f), colors.success)
        PillTone.Warning -> Triple(colors.warning, colors.warning.copy(alpha = 0.14f), colors.warning)
        PillTone.Danger -> Triple(colors.danger, colors.danger.copy(alpha = 0.14f), colors.danger)
    }
    Row(
        modifier.clip(PillShape).background(container).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(dot)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = label)
    }
}

@Composable
private fun Dot(color: Color) {
    androidx.compose.foundation.layout.Box(Modifier.size(6.dp).clip(CircleShape).background(color))
}
