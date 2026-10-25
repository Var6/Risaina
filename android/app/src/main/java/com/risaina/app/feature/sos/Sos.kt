package com.risaina.app.feature.sos

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.SosRepository
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.network.SosDto
import com.risaina.app.core.notifications.Haptics
import com.risaina.app.core.notifications.SosAlert
import com.risaina.app.feature.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

private val SosRed = Color(0xFFC62828)

data class SosUi(val myId: String? = null, val partnerName: String = "your partner", val items: List<SosDto> = emptyList(), val sending: Boolean = false, val error: String? = null) {
    /** My most recent SOS from the last hour, so I can see whether they've responded. */
    val myActive: SosDto? get() = items.firstOrNull { it.senderId == myId }?.takeIf { runCatching { Instant.parse(it.createdAt).isAfter(Instant.now().minusSeconds(3600)) }.getOrDefault(false) }
}

@HiltViewModel
class SosViewModel @Inject constructor(
    private val repo: SosRepository,
    private val haptics: Haptics,
    private val alert: SosAlert,
    sessionStore: SessionStore,
    couple: CoupleRepository,
) : ViewModel() {
    private val local = MutableStateFlow(false to (null as String?))
    val state: StateFlow<SosUi> = combine(repo.items, sessionStore.session.map { it?.userId }, couple.partner, local) { items, me, p, l ->
        SosUi(me, p?.displayName ?: "your partner", items, l.first, l.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SosUi())

    init { viewModelScope.launch { repo.refresh() } }

    fun send(message: String?, onSent: () -> Unit) {
        viewModelScope.launch {
            local.value = true to null
            when (val r = repo.send(message)) {
                is AppResult.Success -> { haptics.confirm(); local.value = false to null; onSent() }
                is AppResult.Failure -> local.value = false to when (val e = r.error) {
                    is AppError.RateLimited -> "You've sent several SOS alerts. Try calling them, or wait ${e.retryAfterSeconds ?: 60}s."
                    is AppError.Offline -> "You're offline. The SOS couldn't be sent."
                    else -> e.message ?: "Couldn't send the SOS"
                }
            }
        }
    }

    fun acknowledge(id: String) { viewModelScope.launch { alert.clear(); repo.acknowledge(id) } }
}

/**
 * Emergency section at the bottom of the partner's profile. Deliberately out of the way: one tap
 * opens a confirmation that explains it will ring even on silent, with an optional message.
 */
@Composable
fun SosSection(vm: SosViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(SosRed.copy(alpha = 0.06f))
            .border(1.dp, SosRed.copy(alpha = 0.25f), MaterialTheme.shapes.large).padding(16.dp),
    ) {
        Text("EMERGENCY", style = com.risaina.app.core.designsystem.theme.RisainaTextStyles.overline, color = SosRed)
        Spacer(Modifier.height(4.dp))
        Text("Need ${s.partnerName} right now?", style = MaterialTheme.typography.titleMedium)
        Text("An SOS rings loudly on their phone, even on silent, until they open it.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        s.myActive?.let { a ->
            Spacer(Modifier.height(8.dp))
            Text(
                if (a.acknowledgedAt != null) "✓ ${s.partnerName} saw your SOS" else "SOS sent ${Format.relative(Instant.parse(a.createdAt))} · waiting for ${s.partnerName}",
                style = MaterialTheme.typography.labelMedium, color = if (a.acknowledgedAt != null) Risaina.colors.success else SosRed,
            )
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.Button(
            onClick = { confirm = true },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White),
            shape = com.risaina.app.core.designsystem.theme.PillShape,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Send SOS", fontWeight = FontWeight.SemiBold) }
    }
    if (confirm) SosConfirmDialog(partner = s.partnerName, sending = s.sending, error = s.error, onSend = { msg -> vm.send(msg) { confirm = false } }, onCancel = { confirm = false })
}

@Composable
private fun SosConfirmDialog(partner: String, sending: Boolean, error: String?, onSend: (String?) -> Unit, onCancel: () -> Unit) {
    var msg by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        icon = { Text("🆘", style = MaterialTheme.typography.headlineMedium) },
        title = { Text("Send an SOS to $partner?", textAlign = TextAlign.Center) },
        text = {
            Column {
                Text(
                    "This will ring loudly on $partner's phone even if it's on silent, and keep ringing until they open it. Use it only when you really need them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                RisainaTextField(msg, { msg = it.take(140) }, "What's happening? (optional)", singleLine = false, minLines = 2)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = { onSend(msg) }, enabled = !sending,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White)) {
                Text(if (sending) "Sending…" else "Yes, send SOS")
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Opened from the SOS alert. Stops the ringing and tells the sender you've seen it. */
@Composable
fun SosScreen(id: String, onBack: () -> Unit, onOpenChat: () -> Unit, vm: SosViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val sos = s.items.firstOrNull { it.id == id } ?: s.items.firstOrNull { it.senderId != s.myId }
    LaunchedEffect(sos?.id) { sos?.takeIf { it.senderId != s.myId && it.acknowledgedAt == null }?.let { vm.acknowledge(it.id) } }
    Column(Modifier.fillMaxSize().background(SosRed.copy(alpha = 0.08f)).safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🆘", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text("${s.partnerName} needs you", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(sos?.message ?: "They sent an SOS. Please check on them now.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        sos?.let { Text(Format.relative(Instant.parse(it.createdAt)), style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted) }
        Spacer(Modifier.height(10.dp))
        Text("They've been told you saw it.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.success)
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Message them", onClick = onOpenChat, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        GhostButton("Close", onClick = onBack)
    }
}

