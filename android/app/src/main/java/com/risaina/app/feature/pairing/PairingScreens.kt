package com.risaina.app.feature.pairing

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.core.data.PairingState
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.components.SecondaryButton
import com.risaina.app.core.designsystem.components.SectionHeader
import com.risaina.app.core.designsystem.components.StatusPill
import com.risaina.app.core.designsystem.components.PillTone
import com.risaina.app.core.designsystem.theme.FrauncesDisplay
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.feature.common.CopyIcon
import com.risaina.app.feature.common.Format
import com.risaina.app.feature.common.ShareIcon
import com.risaina.app.feature.onboarding.Mark
import kotlinx.coroutines.launch

@Composable
fun PairingIntroScreen(onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Mark(Modifier.size(120.dp))
        Spacer(Modifier.height(28.dp))
        Text("Just the two of you", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Risaina links exactly two accounts. One of you creates a private code, the other enters it. Codes expire in 24 hours and work once.",
            style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        PrimaryButton("Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun PairingScreen(onPrivacy: () -> Unit = {}, onAbout: () -> Unit = {}, vm: PairingViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Risaina.spacing.gutter),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pair with your person", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(6.dp))
                Text("Hi ${s.myName.ifBlank { "there" }}. Share a code, or enter theirs.", style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted)
            }
        }
        Spacer(Modifier.height(28.dp))

        // ---- Invite ----
        RisainaCard(Modifier.fillMaxWidth()) {
            SectionHeader(overline = "Invite", title = "Create a private code")
            Spacer(Modifier.height(12.dp))
            AnimatedContent(targetState = s.freshCode, label = "code") { fresh ->
                when {
                    fresh != null -> Column {
                        Text(fresh.code.chunked(5).joinToString("  "), fontFamily = FrauncesDisplay, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text("Expires ${Format.relative(fresh.expiresAt).replace("ago", "").trim()} · shown once", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                        Spacer(Modifier.height(14.dp))
                        Row {
                            SecondaryButton("Share", onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Join me on Risaina. Open the app, choose “Enter a code”, and use: ${fresh.code}")
                                }
                                context.startActivity(Intent.createChooser(send, "Share your code"))
                            }, leading = { Icon(ShareIcon, null, Modifier.size(18.dp)) })
                            Spacer(Modifier.width(10.dp))
                            IconButton(onClick = {
                                scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Risaina code", fresh.code))) }
                            }) { Icon(CopyIcon, contentDescription = "Copy code") }
                        }
                        Spacer(Modifier.height(8.dp))
                        GhostButton("Cancel this code", onClick = vm::revokeInvitation, enabled = !s.revoking)
                    }
                    s.pairing is PairingState.Waiting && (s.pairing as PairingState.Waiting).invitation != null -> Column {
                        StatusPill("Waiting for your partner", PillTone.Info)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "A code is active. It was shown once when created; if you've lost it, cancel it and create a new one.",
                            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row {
                            OutlineButton("Cancel code", onClick = vm::revokeInvitation, enabled = !s.revoking)
                            Spacer(Modifier.width(10.dp))
                            GhostButton("Check again", onClick = vm::refresh)
                        }
                    }
                    else -> Column {
                        Text("You'll get a 10-character code to send however you like.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton("Create code", onClick = vm::createInvitation, loading = s.creating)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Enter ----
        RisainaCard(Modifier.fillMaxWidth()) {
            SectionHeader(overline = "Join", title = "Enter their code")
            Spacer(Modifier.height(12.dp))
            RisainaTextField(
                vm.codeInput, vm::onCode, "Code", placeholder = "ABCDE FGHIJ",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Join", onClick = vm::join, loading = s.joining, enabled = vm.codeInput.length == 10, modifier = Modifier.fillMaxWidth())
        }

        if (s.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        GhostButton("Sign out", onClick = vm::signOut, modifier = Modifier.align(Alignment.CenterHorizontally))
        // Your data, the privacy policy and "Delete my account" are reachable without a partner.
        GhostButton("Privacy & account", onClick = onPrivacy, modifier = Modifier.align(Alignment.CenterHorizontally))
        GhostButton("About Risaina", onClick = onAbout, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
    }
}
