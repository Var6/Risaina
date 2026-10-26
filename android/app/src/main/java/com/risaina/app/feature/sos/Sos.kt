package com.risaina.app.feature.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.theme.PillShape
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.network.SosDto
import com.risaina.app.core.safety.SosSender
import com.risaina.app.feature.common.Format
import com.risaina.app.feature.safety.PartnerLiveLocationSection
import com.risaina.app.feature.safety.SafetyUi
import com.risaina.app.feature.safety.SafetyViewModel
import com.risaina.app.feature.safety.SosStatusPanel
import com.risaina.app.feature.safety.dial
import com.risaina.app.feature.safety.rememberLocationAccessFlow
import com.risaina.app.feature.safety.shareText
import kotlinx.coroutines.launch
import java.time.Instant

private val SosRed = Color(0xFFC62828)

/**
 * Emergency section at the bottom of the partner's profile. Deliberately out of the way: one tap
 * opens a confirmation that explains it will ring even on silent, with an optional message and
 * "also share my live location". The sender then sees whether it reached the partner's phone.
 */
@Composable
fun SosSection(onOpenSafety: (() -> Unit)? = null, vm: SafetyViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        vm.refreshDevice()
        onPauseOrDispose { }
    }
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(SosRed.copy(alpha = 0.06f))
            .border(1.dp, SosRed.copy(alpha = 0.25f), MaterialTheme.shapes.large).padding(16.dp),
    ) {
        Text("EMERGENCY", style = RisainaTextStyles.overline, color = SosRed)
        Spacer(Modifier.height(4.dp))
        Text("Need ${s.partnerName} right now?", style = MaterialTheme.typography.titleMedium)
        Text(
            "An SOS rings loudly on their phone, even on silent, until they tap \"I'm on it\".",
            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
        )
        s.myActive?.takeIf { it.resolvedAt == null }?.let { active ->
            Spacer(Modifier.height(10.dp))
            SosStatusPanel(active, s, vm)
        }
        when (val a = s.attempt) {
            is SosSender.Attempt.NotSent -> {
                Spacer(Modifier.height(10.dp))
                Text(a.reason, style = MaterialTheme.typography.bodySmall, color = SosRed)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton("Share location", onClick = { shareText(context, a.shareText) }, modifier = Modifier.weight(1f))
                    OutlineButton("Call", onClick = { dial(context, s.prefs.partnerPhone) }, modifier = Modifier.weight(1f))
                }
                GhostButton("OK", onClick = vm::resetAttempt)
            }
            else -> Unit
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { confirm = true },
            colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White),
            shape = PillShape,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Send SOS", fontWeight = FontWeight.SemiBold) }
        if (onOpenSafety != null) {
            GhostButton("Panic button and SOS checks", onClick = onOpenSafety, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
    if (confirm) {
        SosConfirmDialog(
            s = s,
            onSend = { msg, share -> vm.sendSos(msg, share); confirm = false },
            onShareChange = vm::setShareWithSos,
            onRefresh = vm::refreshDevice,
            onCancel = { confirm = false },
        )
    }
}

@Composable
private fun SosConfirmDialog(s: SafetyUi, onSend: (String, Boolean) -> Unit, onShareChange: (Boolean) -> Unit, onRefresh: () -> Unit, onCancel: () -> Unit) {
    var msg by remember { mutableStateOf("") }
    val sending = s.attempt is SosSender.Attempt.Sending
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val allowLocation = rememberLocationAccessFlow(
        s.partnerName, requireNotifications = false, onReady = onRefresh,
        onBlocked = { m -> scope.launch { snackbar.showSnackbar(m) } },
    )
    val share = s.prefs.shareLocationWithSos && s.access.permitted
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Text("🆘", style = MaterialTheme.typography.headlineMedium) },
        title = { Text("Send an SOS to ${s.partnerName}?", textAlign = TextAlign.Center) },
        text = {
            Column {
                Text(
                    "This rings loudly on ${s.partnerName}'s phone even if it's on silent, and keeps ringing until they tap \"I'm on it\". Use it only when you really need them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                RisainaTextField(msg, { msg = it.take(140) }, "What's happening? (optional)", singleLine = false, minLines = 2)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Also share my live location for 2 hours", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (s.access.permitted) Switch(checked = s.prefs.shareLocationWithSos, onCheckedChange = onShareChange)
                    else TextButton(onClick = allowLocation) { Text("Allow location") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(msg, share) }, enabled = !sending,
                colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White),
            ) { Text(if (sending) "Sending…" else "Yes, send SOS") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Opened from an SOS notification or the alarm screen. Opening it never counts as "seen": only the
 * "I'm on it" button tells the sender, and it also stops the alarm. For the sender's own SOS it
 * shows the delivery status and "I'm safe now".
 */
@Composable
fun SosScreen(id: String, onBack: () -> Unit, onOpenChat: () -> Unit, vm: SafetyViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val sos: SosDto? = s.sos.firstOrNull { it.id == id } ?: s.sos.firstOrNull { it.senderId != s.myId }
    Column(
        Modifier.fillMaxSize().background(SosRed.copy(alpha = 0.08f)).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (sos != null && sos.senderId == s.myId) {
            SosStatusPanel(sos, s, vm)
            Spacer(Modifier.height(16.dp))
            GhostButton("Close", onClick = onBack)
            return@Column
        }
        Text("🆘", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            if (sos?.panic == true) "${s.partnerName} is in trouble" else "${s.partnerName} needs you",
            style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(sos?.message ?: "They sent an SOS. Please check on them now.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        sos?.let { runCatching { Instant.parse(it.createdAt) }.getOrNull() }?.let {
            Text(Format.relative(it), style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
        Spacer(Modifier.height(16.dp))
        when {
            sos == null -> Unit
            sos.resolvedAt != null -> Text("${s.partnerName} says they're safe now.", style = MaterialTheme.typography.titleSmall, color = Risaina.colors.success)
            sos.acknowledgedAt != null -> Text("${s.partnerName} knows you're on it.", style = MaterialTheme.typography.titleSmall, color = Risaina.colors.success)
            else -> Button(
                onClick = { vm.acknowledge(sos.id) },
                colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White),
                shape = PillShape,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("I'm on it", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
        }
        Spacer(Modifier.height(16.dp))
        PartnerLiveLocationSection()
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Message them", onClick = onOpenChat, modifier = Modifier.fillMaxWidth())
        if (s.prefs.partnerPhone.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            OutlineButton("Call ${s.partnerName}", onClick = { dial(context, s.prefs.partnerPhone) }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        GhostButton("Close", onClick = onBack)
    }
}
