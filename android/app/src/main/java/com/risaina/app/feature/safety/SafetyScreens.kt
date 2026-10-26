package com.risaina.app.feature.safety

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.components.SectionHeader
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.domain.LiveLocationRules
import com.risaina.app.core.location.ShareState
import com.risaina.app.core.network.SosDto
import com.risaina.app.core.safety.CheckStatus
import com.risaina.app.core.safety.ReadinessCheck
import com.risaina.app.core.safety.SosReadiness
import com.risaina.app.core.safety.SosSender
import com.risaina.app.core.sos.SosSenderStatus
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.Format
import kotlinx.coroutines.launch
import java.time.Instant

// ---- intents (no permission needed: the user finishes the call or message in their own app) ----

internal fun dial(context: Context, phone: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone.trim()))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun textMessage(context: Context, phone: String, body: String) {
    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone.trim()))).putExtra("sms_body", body).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(i) }
}

internal fun shareText(context: Context, body: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
    runCatching { context.startActivity(Intent.createChooser(send, "Send your location").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafetyFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            content()
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * Settings → Safety (risaina://app/safety): live location, the panic button, "Can an SOS reach
 * you?" with tests, the partner's number for Call and Text, and what is (not) kept.
 */
@Composable
fun SafetyScreen(onBack: () -> Unit, vm: SafetyViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleResumeEffect(Unit) {
        vm.refreshDevice()
        onPauseOrDispose { }
    }
    SafetyFrame("Safety", onBack) {
        Spacer(Modifier.height(8.dp))
        SectionHeader("Live location")
        LiveLocationControl(vm = vm)
        Text(
            "${s.partnerName} sees your position with an Open in Maps link, how accurate it is, your battery level and when it last updated.",
            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
        )
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        SectionHeader("Panic button")
        Spacer(Modifier.height(8.dp))
        PanicPanel(s, vm)
        GhostButton("Add \"I'm in trouble\" to your home screen", onClick = vm::pinPanicShortcut)
        Text(
            "You can also long-press the Risaina icon and choose \"I'm in trouble\". Nothing is sent until you hold the button.",
            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
        )
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        ReadinessCard(s, vm)
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        SectionHeader("If an SOS isn't getting through")
        Spacer(Modifier.height(8.dp))
        var phone by remember(s.prefs.partnerPhone) { mutableStateOf(s.prefs.partnerPhone) }
        RisainaTextField(
            phone, { phone = it; vm.setPartnerPhone(it) }, "${s.partnerName}'s phone number",
            supporting = "Kept only on this phone. Used for Call and Text when an SOS isn't reaching them.",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        SectionHeader("Privacy")
        Spacer(Modifier.height(8.dp))
        Text(
            "Only you can turn sharing on, and a notification shows the whole time. ${s.partnerName} can't turn it on or ask for it. " +
                "Risaina keeps only your latest position while you share and deletes it as soon as you stop, when the time runs out, " +
                "when you sign out, or if you unlink. There is no location history.",
            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
        )
    }
}

/**
 * The launcher shortcut "I'm in trouble" (risaina://app/panic): only the hold button, a message and
 * Cancel. Opening it sends nothing; the 2-second hold does.
 */
@Composable
fun PanicScreen(onClose: () -> Unit, vm: SafetyViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleResumeEffect(Unit) {
        vm.refreshDevice()
        onPauseOrDispose { }
    }
    Column(
        Modifier.fillMaxSize().background(SafetyRed.copy(alpha = 0.08f)).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Text("🆘", style = MaterialTheme.typography.displayMedium)
        Text("I'm in trouble", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        PanicPanel(s, vm)
        Spacer(Modifier.height(8.dp))
        GhostButton(if (s.attempt is SosSender.Attempt.Idle) "Cancel" else "Close", onClick = onClose)
    }
}

/** The panic form, or what happened to the alert once it was sent. */
@Composable
internal fun PanicPanel(s: SafetyUi, vm: SafetyViewModel) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val allowLocation = rememberLocationAccessFlow(
        s.partnerName, requireNotifications = false,
        onReady = { vm.refreshDevice() },
        onBlocked = { m -> scope.launch { snackbar.showSnackbar(m) } },
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when (val a = s.attempt) {
            is SosSender.Attempt.Sent -> {
                val sos = s.sos.firstOrNull { it.id == a.sosId }
                if (sos != null) SosStatusPanel(sos, s, vm) else Text("Alert sent", style = MaterialTheme.typography.titleMedium)
                GhostButton("Done", onClick = vm::resetAttempt)
            }
            is SosSender.Attempt.NotSent -> NotSentPanel(a, s, onBack = vm::resetAttempt, context = context)
            else -> {
                val sending = a is SosSender.Attempt.Sending
                s.myActive?.takeIf { it.resolvedAt == null }?.let { active ->
                    SosStatusPanel(active, s, vm)
                    Spacer(Modifier.height(16.dp))
                }
                var message by rememberSaveable { mutableStateOf("") }
                val share = s.prefs.shareLocationWithSos
                Text(
                    "Hold for 2 seconds. ${s.partnerName}'s phone rings like an alarm, even on silent, until they tap \"I'm on it\"" +
                        if (share && s.access.permitted) ", and they see where you are for 2 hours." else ".",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                RisainaTextField(message, { message = it.take(140) }, "Message (optional)", placeholder = SosSender.DEFAULT_PANIC_MESSAGE, enabled = !sending)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Also share my live location for 2 hours", style = MaterialTheme.typography.titleSmall)
                        if (!s.access.permitted) Text("Location isn't allowed yet", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                    }
                    if (s.access.permitted || !share) {
                        Switch(checked = share, onCheckedChange = vm::setShareWithSos, enabled = !sending)
                    } else {
                        GhostButton("Allow", onClick = allowLocation)
                    }
                }
                Spacer(Modifier.height(12.dp))
                HoldToConfirmButton(
                    label = "Hold for 2 seconds: I'm in trouble",
                    onConfirm = { vm.panic(message, share) },
                    confirmTitle = "Send a panic alert to ${s.partnerName}?",
                    confirmText = "Their phone will ring like an alarm until they respond.",
                    enabled = !sending,
                    busyLabel = if (sending) "SENDING" else null,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** The sender's view of their SOS: delivered, seen, or "call them", plus "I'm safe now". */
@Composable
internal fun SosStatusPanel(sos: SosDto, s: SafetyUi, vm: SafetyViewModel) {
    val context = LocalContext.current
    val now = rememberNow(5_000)
    val view = SosSenderStatus.of(sos.createdAt, sos.deliveredAt, sos.acknowledgedAt, sos.resolvedAt, now)
    val color = when (view.kind) {
        SosSenderStatus.Kind.SEEN, SosSenderStatus.Kind.RESOLVED -> Risaina.colors.success
        SosSenderStatus.Kind.RINGING -> MaterialTheme.colorScheme.onSurface
        else -> SafetyRed
    }
    RisainaCard(Modifier.fillMaxWidth()) {
        val created = runCatching { Instant.parse(sos.createdAt) }.getOrNull()
        Text(if (sos.panic) "Panic alert sent" else "SOS sent", style = MaterialTheme.typography.titleMedium)
        created?.let { Text(Format.relative(it, now), style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted) }
        Spacer(Modifier.height(6.dp))
        StatusLine(SosSenderStatus.line(view.kind, s.partnerName), color)
        val share = s.share
        if (share is ShareState.Sharing && share.panic) {
            Spacer(Modifier.height(4.dp))
            Text("Sharing your location ${untilText(share.expiresAt)}", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
        if (SosSenderStatus.canSaySafe(sos.createdAt, sos.resolvedAt, now)) {
            Spacer(Modifier.height(10.dp))
            PrimaryButton("I'm safe now", onClick = { vm.safeNow(sos.id) }, modifier = Modifier.fillMaxWidth())
        }
        if (view.offerCall) {
            Spacer(Modifier.height(8.dp))
            val mine = s.live.mine?.fix
            val body = LiveLocationRules.troubleText(sos.message, mine?.lat, mine?.lng)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Call ${s.partnerName}", onClick = { dial(context, s.prefs.partnerPhone) }, modifier = Modifier.weight(1f))
                OutlineButton("Text", onClick = { textMessage(context, s.prefs.partnerPhone, body) }, modifier = Modifier.weight(1f))
            }
            if (s.prefs.partnerPhone.isBlank()) {
                Text("Add ${s.partnerName}'s number in Settings → Safety to call in one tap.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
        }
        if (share is ShareState.Sharing && share.panic) {
            GhostButton("Stop sharing my location", onClick = vm::stopSharing)
        }
    }
}

@Composable
private fun NotSentPanel(a: SosSender.Attempt.NotSent, s: SafetyUi, onBack: () -> Unit, context: Context) {
    RisainaCard(Modifier.fillMaxWidth()) {
        Text("Not sent yet", style = MaterialTheme.typography.titleMedium, color = SafetyRed)
        Text(a.reason, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Send my location by SMS or WhatsApp", onClick = { shareText(context, a.shareText) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("Call ${s.partnerName}", onClick = { dial(context, s.prefs.partnerPhone) }, modifier = Modifier.weight(1f))
            OutlineButton("Text", onClick = { textMessage(context, s.prefs.partnerPhone, a.shareText) }, modifier = Modifier.weight(1f))
        }
        if (a.sharing) {
            Spacer(Modifier.height(6.dp))
            Text("Your live location is being shared as soon as the internet is back.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
        GhostButton("Back", onClick = onBack)
    }
}

/** "Can an SOS reach you?": the checklist, the full-volume switch and the two tests. */
@Composable
internal fun ReadinessCard(s: SafetyUi, vm: SafetyViewModel) {
    RisainaCard(Modifier.fillMaxWidth()) {
        Text("Can an SOS reach you?", style = MaterialTheme.typography.titleMedium)
        val todo = s.readiness.count { it.status == CheckStatus.ACTION }
        Text(
            SosReadiness.summary(s.readiness),
            style = MaterialTheme.typography.labelLarge,
            color = if (todo == 0) Risaina.colors.success else Risaina.colors.warning,
        )
        Spacer(Modifier.height(8.dp))
        s.readiness.forEach { check -> CheckRow(check, onFix = { check.fix?.let(vm::openFix) }) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ring SOS at full volume", style = MaterialTheme.typography.titleSmall)
                Text("Raises the alarm volume while an SOS rings, then puts yours back", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
            Switch(checked = s.prefs.fullVolume, onCheckedChange = vm::setFullVolume)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("Test here", onClick = vm::testOnThisPhone, modifier = Modifier.weight(1f))
            OutlineButton("Test in 15 s", onClick = vm::testThroughServer, modifier = Modifier.weight(1f))
        }
        Text(
            "\"Test here\" rings this phone for 5 seconds. \"Test in 15 s\" goes through the server: lock your phone or close Risaina after tapping it.",
            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
        )
    }
}

@Composable
private fun CheckRow(check: ReadinessCheck, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        val (mark, color) = when (check.status) {
            CheckStatus.OK -> "✓" to Risaina.colors.success
            CheckStatus.ACTION -> "!" to Risaina.colors.warning
            CheckStatus.INFO -> "i" to Risaina.colors.textMuted
        }
        Text(mark, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(check.title, style = MaterialTheme.typography.bodyMedium)
            check.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted) }
        }
        if (check.fix != null && check.status != CheckStatus.OK) GhostButton(check.fixLabel ?: "Fix", onClick = onFix)
    }
}
