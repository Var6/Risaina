package com.risaina.app.feature.ping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.core.designsystem.components.EmptyState
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.PillTone
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.SecondaryButton
import com.risaina.app.core.designsystem.components.StatusPill
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.model.Ping
import com.risaina.app.core.model.PingStatus
import com.risaina.app.core.model.PingType
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.Format

fun PingStatus.pill(): Pair<String, PillTone> = when (this) {
    PingStatus.PENDING -> "Sending" to PillTone.Neutral
    PingStatus.ACCEPTED -> "Sent" to PillTone.Info
    PingStatus.DELIVERED -> "Delivered" to PillTone.Success
    PingStatus.SEEN -> "Seen" to PillTone.Success
    PingStatus.FAILED -> "Failed" to PillTone.Danger
    PingStatus.EXPIRED -> "Expired" to PillTone.Warning
}

@Composable
fun PingHistoryScreen(onBack: () -> Unit, vm: PingViewModel = hiltViewModel()) {
    val pings by vm.history.collectAsStateWithLifecycle()
    val myUserId by vm.myId.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pings", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (pings.isEmpty()) {
            EmptyState(title = "No pings yet", body = "The first one is a tap away on the home screen.", modifier = Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(pings, key = { it.id }) { ping ->
                    PingRow(ping, mine = ping.senderId == myUserId, onRetry = { vm.retry(ping.localKey) }, onDiscard = { vm.discard(ping.localKey) })
                }
            }
        }
    }
}

@Composable
fun PingRow(ping: Ping, mine: Boolean, onRetry: () -> Unit, onDiscard: () -> Unit) {
    RisainaCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ping.emoji, style = RisainaTextStyles.emojiLarge.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (mine) "You · ${ping.type.label}" else ping.type.label, style = MaterialTheme.typography.titleSmall)
                if (!ping.message.isNullOrBlank()) Text(ping.message, style = MaterialTheme.typography.bodyMedium)
                Text(Format.relative(ping.createdAt), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
            }
            if (mine) {
                val (label, tone) = ping.status.pill()
                StatusPill(label, tone)
            }
        }
        if (mine && ping.status == PingStatus.FAILED) {
            Spacer(Modifier.height(8.dp))
            Row {
                SecondaryButton("Retry", onClick = onRetry)
                Spacer(Modifier.width(8.dp))
                GhostButton("Discard", onClick = onDiscard)
            }
        }
    }
}

/** Opened from a notification. Marks the ping seen and offers a one-tap reply. */
@Composable
fun PingDetailScreen(pingId: String, onBack: () -> Unit, onReplied: (String) -> Unit, vm: PingViewModel = hiltViewModel()) {
    val pings by vm.history.collectAsStateWithLifecycle()
    val partnerName by vm.partnerName.collectAsStateWithLifecycle()
    val ping = pings.firstOrNull { it.id == pingId }
    LaunchedEffect(pingId) { vm.markSeen(pingId) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (ping == null) {
                Text("Loading…", color = Risaina.colors.textMuted)
            } else {
                Text(ping.emoji, style = RisainaTextStyles.emojiLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(72f, androidx.compose.ui.unit.TextUnitType.Sp)))
                Spacer(Modifier.height(20.dp))
                Text("${partnerName ?: "Your partner"} · ${ping.type.label}", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(ping.message ?: ping.type.defaultMessage, style = RisainaTextStyles.noteSerif, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(Format.relative(ping.createdAt), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
                Spacer(Modifier.height(36.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(PingType.THINKING_OF_YOU, PingType.HUG, PingType.KISS).forEach { t ->
                        SecondaryButton("${t.defaultEmoji} ${t.label}", onClick = {
                            vm.replyWith(t) { r -> onReplied(if (r.isSuccess) "Sent ${t.defaultEmoji}" else r.errorOrNull()?.message ?: "Couldn't send") }
                        })
                    }
                }
            }
        }
    }
}
