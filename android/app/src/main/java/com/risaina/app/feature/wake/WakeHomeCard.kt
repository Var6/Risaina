package com.risaina.app.feature.wake

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.theme.PillShape
import com.risaina.app.core.designsystem.theme.Risaina

/**
 * Home's "Wake together" card, shown once an alarm exists: "Tomorrow 6:30 am · both phones ✓", or
 * "Raina is up ☀ · you're snoozed" in the morning. Nothing otherwise.
 */
@Composable
fun WakeHomeCard(onOpen: () -> Unit, vm: WakeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val card = s.home ?: return
    RisainaCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(PillShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(AlarmIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium)
                Text(card.line, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
        }
    }
}
