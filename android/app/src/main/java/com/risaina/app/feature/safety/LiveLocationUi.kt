package com.risaina.app.feature.safety

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.domain.LiveLocationRules
import com.risaina.app.core.domain.LiveLocationRules.Freshness
import com.risaina.app.core.location.FailReason
import com.risaina.app.core.location.ShareState
import com.risaina.app.core.network.LiveLocationDto
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private fun parseInstant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** "until 9:40 pm", or "until you turn it off" ("until they turn it off" for the partner). */
internal fun untilText(expiresAt: Instant?, who: String = "you"): String =
    expiresAt?.let { "until ${Format.time(it)}" } ?: "until $who turn it off"

/** Ticks every [periodMs] so "updated 2 min ago" stays true while the screen is open. */
@Composable
internal fun rememberNow(periodMs: Long = 15_000): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(periodMs)
            value = Instant.now()
        }
    }
    return now
}

/**
 * "Share my live location": the one switch, with 1 hour (default), 8 hours or "until I turn it off".
 * Used in Settings and on the Safety screen.
 */
@Composable
fun LiveLocationControl(modifier: Modifier = Modifier, vm: SafetyViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { m -> scope.launch { snackbar.showSnackbar(m) } }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleResumeEffect(Unit) {
        vm.refreshDevice()
        onPauseOrDispose { }
    }
    val start = rememberLocationAccessFlow(s.partnerName, requireNotifications = true, onReady = vm::startSharing, onBlocked = toast)
    val askPrecise = rememberPreciseLocationRequest { vm.refreshDevice() }
    val share = s.share
    val panicSession = share is ShareState.Sharing && share.panic
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Share my live location", style = MaterialTheme.typography.titleSmall)
                Text(shareSubtitle(share, s.partnerName, s.access.playServices), style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
            Switch(
                checked = s.sharingOn,
                onCheckedChange = { on -> if (on) start() else vm.stopSharing() },
                enabled = s.sharingOn || s.access.playServices,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveLocationRules.MANUAL_CHOICES.forEach { m ->
                FilterChip(
                    selected = s.prefs.liveMinutes == m,
                    onClick = { vm.setDuration(m) },
                    label = { Text(LiveLocationRules.minutesLabel(m)) },
                    enabled = !panicSession,
                )
            }
        }
        when {
            share is ShareState.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Resume", onClick = vm::resumeSharing)
                GhostButton("Stop", onClick = vm::stopSharing)
            }
            share is ShareState.Sharing && share.signInNeeded -> Text(
                "Sign in to Risaina again to keep sending your location.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        if (s.access.permitted && !s.access.precise) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Approximate location only (about ±2 km).", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, modifier = Modifier.weight(1f))
                GhostButton("Use precise", onClick = askPrecise)
            }
        }
    }
}

private fun shareSubtitle(share: ShareState, partner: String, playServices: Boolean): String = when (share) {
    ShareState.Off -> if (!playServices) "Needs Google Play services" else "$partner sees where you are while this is on. Nothing is kept after you stop."
    is ShareState.Starting -> "Starting…"
    is ShareState.Sharing -> {
        val until = untilText(share.expiresAt)
        val updated = share.lastUploadAt?.let { " · updated ${LiveLocationRules.ago(it, Instant.now())}" }.orEmpty()
        if (share.panic) "On for your panic alert · $until$updated" else "On · $until$updated"
    }
    is ShareState.Paused -> "Paused: your phone stopped updating. Resume, or stop sharing."
    is ShareState.Failed -> when (share.reason) {
        FailReason.NO_PERMISSION -> "Allow location to share it"
        FailReason.NO_PLAY_SERVICES -> "Needs Google Play services"
        FailReason.START_REFUSED -> "Open Risaina to start sharing"
    }
}

/** Opens the position in a maps app, or in the browser when no app handles geo: links. */
internal fun openMaps(context: Context, lat: Double, lng: Double, label: String) {
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse(LiveLocationRules.geoUri(lat, lng, label))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(geo)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LiveLocationRules.mapsWebUrl(lat, lng))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** The partner's live location: how fresh, how accurate, until when, battery, and Open in Maps. */
@Composable
fun PartnerLiveLocationCard(partnerName: String, live: LiveLocationDto, now: Instant, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val panic = live.reason == "panic"
    val fix = live.fix
    val freshness = LiveLocationRules.freshness(parseInstant(live.lastSeenAt ?: live.updatedAt), fix != null, panic, now)
    val (dot, title) = when (freshness) {
        Freshness.WAITING -> Risaina.colors.textMuted to "Sharing started"
        Freshness.LIVE -> Risaina.colors.success to "Live location"
        Freshness.STALE -> Risaina.colors.warning to "Live location"
        Freshness.OLD -> Risaina.colors.textMuted to "Last known location"
    }
    val fixAgo = fix?.let { f -> parseInstant(f.at)?.let { LiveLocationRules.ago(it, now) } }
    val detail = when (freshness) {
        Freshness.WAITING -> "Waiting for their first location…"
        Freshness.LIVE -> "Updated ${fixAgo ?: "just now"}"
        Freshness.STALE -> "Updated ${fixAgo ?: "a while ago"}. Their phone may be offline or saving battery."
        Freshness.OLD -> "From ${fixAgo ?: "a while ago"}"
    }
    RisainaCard(
        modifier.fillMaxWidth().then(if (panic) Modifier.border(1.5.dp, SafetyRed, MaterialTheme.shapes.large) else Modifier),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(
                if (panic) "${partnerName.uppercase()} IS IN TROUBLE" else "${partnerName.uppercase()} IS SHARING",
                style = RisainaTextStyles.overline, color = if (panic) SafetyRed else Risaina.colors.textMuted,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        val facts = buildList {
            fix?.let { add(if (LiveLocationRules.isApproximate(it.accuracyM)) "Approximate area (${LiveLocationRules.accuracyLabel(it.accuracyM)})" else LiveLocationRules.accuracyLabel(it.accuracyM)) }
            add(untilText(parseInstant(live.expiresAt), who = "they").replaceFirstChar { it.uppercase() })
            fix?.batteryPct?.let { add("🔋 $it%") }
        }
        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        if (fix != null) {
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Open in Maps", onClick = { openMaps(context, fix.lat, fix.lng, partnerName) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The partner's live location wherever it is shown (profile sheet, SOS screen, location screen).
 * Keeps it fresh only while visible, and says when they stopped, for ten minutes.
 */
@Composable
fun PartnerLiveLocationSection(modifier: Modifier = Modifier, showWhenOff: Boolean = false, vm: SafetyViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.keepPartnerFresh() } }
    val now = rememberNow()
    val live = s.partnerLive
    val stoppedAt = s.live.partnerStoppedAt?.takeIf { Duration.between(it, now) < Duration.ofMinutes(10) }
    when {
        live != null -> PartnerLiveLocationCard(s.partnerName, live, now, modifier)
        stoppedAt != null -> Text(
            "${s.partnerName} stopped sharing their location at ${Format.time(stoppedAt)}.",
            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, modifier = modifier,
        )
        showWhenOff -> Text(
            "${s.partnerName} isn't sharing their location right now. Only they can turn it on.",
            style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted, modifier = modifier,
        )
    }
}

/** Full screen for risaina://app/location (the "is sharing" notification and the SOS alarm). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartnerLocationScreen(onBack: () -> Unit, onOpenChat: () -> Unit, vm: SafetyViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Where ${s.partnerName} is", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            PartnerLiveLocationSection(showWhenOff = true, vm = vm)
            Spacer(Modifier.height(16.dp))
            OutlineButton("Message ${s.partnerName}", onClick = onOpenChat, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(
                "Only the latest position is shown, and only while ${s.partnerName} shares it. Nothing is kept on this phone.",
                style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
            )
        }
    }
}

/**
 * On Home: "Raina can see your location" while I share (with Stop, or Resume when Android paused
 * it), and "Raina is sharing their live location" while they do. Nothing at all otherwise.
 */
@Composable
fun SafetyHomeBanner(onOpenSafety: () -> Unit, onOpenLocation: () -> Unit, vm: SafetyViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val share = s.share
    val partnerLive = s.partnerLive
    if (share !is ShareState.Sharing && share !is ShareState.Paused && share !is ShareState.Starting && partnerLive == null) return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (share) {
            is ShareState.Sharing -> Banner(
                title = "📍 ${s.partnerName} can see your location",
                subtitle = if (share.panic) "Panic alert · ${untilText(share.expiresAt)}" else untilText(share.expiresAt).replaceFirstChar { it.uppercase() },
                onClick = onOpenSafety,
            ) { GhostButton("Stop", onClick = vm::stopSharing) }
            is ShareState.Starting -> Banner("📍 Starting to share your location", "Waiting for your first position", onOpenSafety) {}
            is ShareState.Paused -> Banner("📍 Location sharing is paused", "Your phone stopped updating", onOpenSafety) {
                GhostButton("Resume", onClick = vm::resumeSharing)
                GhostButton("Stop", onClick = vm::stopSharing)
            }
            else -> Unit
        }
        if (partnerLive != null) {
            val panic = partnerLive.reason == "panic"
            Banner(
                title = if (panic) "🆘 ${s.partnerName} is in trouble" else "📍 ${s.partnerName} is sharing their location",
                subtitle = "Tap to see where they are",
                onClick = onOpenLocation,
            ) {}
        }
    }
}

@Composable
private fun Banner(title: String, subtitle: String, onClick: () -> Unit, actions: @Composable () -> Unit) {
    RisainaCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
            actions()
        }
    }
}

/** A plain coloured status line (green done, red needs attention). */
@Composable
internal fun StatusLine(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = color)
}
