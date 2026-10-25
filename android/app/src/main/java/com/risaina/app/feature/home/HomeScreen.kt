package com.risaina.app.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.risaina.app.core.domain.Occasions
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.Avatar
import com.risaina.app.core.designsystem.components.HeroPanel
import com.risaina.app.core.designsystem.components.PillTone
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.SectionHeader
import com.risaina.app.core.designsystem.components.StatusPill
import com.risaina.app.core.designsystem.theme.Palette
import com.risaina.app.core.designsystem.theme.PillShape
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.model.MessageType
import com.risaina.app.core.model.PingType
import com.risaina.app.feature.common.Format
import com.risaina.app.feature.common.HistoryIcon
import com.risaina.app.feature.common.ImageIcon
import com.risaina.app.feature.common.MicIcon
import com.risaina.app.feature.common.SettingsIcon
import com.risaina.app.feature.common.TimelineIcon
import com.risaina.app.feature.common.WallpaperIcon
import com.risaina.app.feature.common.WidgetsIcon
import com.risaina.app.feature.ping.IncomingPingReveal
import com.risaina.app.feature.ping.PingButtonState
import com.risaina.app.feature.ping.PingComposerSheet
import com.risaina.app.feature.ping.TouchPingButton
import com.risaina.app.feature.ping.pill

data class HomeNav(
    val settings: () -> Unit,
    val messages: () -> Unit,
    val voice: () -> Unit,
    val memories: () -> Unit,
    val memory: (String) -> Unit,
    val timeline: () -> Unit,
    val widgets: () -> Unit,
    val wallpaper: () -> Unit,
    val pingHistory: () -> Unit,
    val notificationPermission: () -> Unit,
    val verifyEmail: () -> Unit = {},
    val lists: () -> Unit = {},
    val gifts: () -> Unit = {},
)

@Composable
fun HomeScreen(nav: HomeNav, vm: HomeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(s.justPaired) { if (s.justPaired) { nav.notificationPermission(); vm.consumeJustPaired() } }

    var pickStart by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        HomeBackground(s.background, s.favouriteImage)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item { TopRow(greeting = Format.greetingFor(s.myName), onSettings = nav.settings) }
            if (!s.emailVerified) item {
                RisainaCard(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth(), onClick = nav.verifyEmail, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Confirm your email", style = MaterialTheme.typography.titleSmall)
                    Text("So you can recover your account. Takes 10 seconds.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                }
            }
            item { Hero(s) }
            if (s.askStartDate) item { StartDatePrompt(onPick = { pickStart = true }, onDismiss = vm::dismissStartDate) }
            item { PingArea(s, vm) }
            item { Shortcuts(s.selectedType, onPick = { vm.selectType(it); vm.tapPing() }, onMore = vm::openComposer) }
            if (s.latestMoment != null) item { LatestMoment(s, onOpen = nav.messages) }
            item { QuickRow(nav.copy(gifts = vm::openGifts)) }
            if (s.favouriteMemory != null) item { FavouriteMemory(s, onOpen = { nav.memory(s.favouriteMemory!!.id) }) }
            item { VoiceShortcut(s, onOpen = nav.voice) }
            if (s.recent.isNotEmpty()) {
                item { Spacer(Modifier.height(8.dp)); SectionHeader("Recently", modifier = Modifier.padding(horizontal = 20.dp), trailing = { IconButton(onClick = nav.pingHistory) { Icon(HistoryIcon, "Ping history") } }) }
                items(s.recent, key = { it.id }) { RecentRow(it) }
            }
        }
        IncomingPingReveal(ping = s.incoming, partnerName = s.partner?.displayName, onReply = vm::replyToIncoming, onDismiss = vm::dismissIncoming, modifier = Modifier.align(Alignment.TopCenter))
        if (pickStart) com.risaina.app.feature.timeline.DateDialog(null, { pickStart = false }) { vm.setStartDate(it) }
        if (s.showComposer) {
            PingComposerSheet(initialType = s.selectedType, initialTab = s.composerTab, sending = s.button is PingButtonState.Sending, onDismiss = vm::closeComposer, onSend = vm::sendCustom)
        }
    }
}

@Composable
private fun TopRow(greeting: String, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(greeting, style = MaterialTheme.typography.titleMedium, color = Risaina.colors.textMuted, modifier = Modifier.weight(1f))
        IconButton(onClick = onSettings) { Icon(SettingsIcon, contentDescription = "Settings") }
    }
}

@Composable
private fun Hero(s: HomeUiState) {
    HeroPanel(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = s.partner?.displayName, model = s.partnerAvatar, size = 60.dp, ring = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.partner?.displayName ?: "—", style = MaterialTheme.typography.displaySmall, color = Palette.WarmIvory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val clock = s.partnerClock
                    Text(
                        if (clock != null) "It's ${clock.first} for them" + (clock.second?.let { " · $it" } ?: "") else "Paired",
                        style = MaterialTheme.typography.bodySmall, color = Palette.MutedOnDark,
                    )
                }
            }
            s.anniversary?.let { a ->
                Spacer(Modifier.height(18.dp))
                Text("TOGETHER", style = RisainaTextStyles.overline, color = Palette.SoftChampagne)
                Text(a.together, style = MaterialTheme.typography.headlineSmall, color = Palette.WarmIvory)
                Text("%,d days".format(a.daysTogether), style = MaterialTheme.typography.bodySmall, color = Palette.MutedOnDark)
            }
            val chips = buildList {
                s.countdown?.let { add("${it.countdown.headline} · ${it.label.lowercase()}") }
                s.anniversary?.let { a -> add(if (a.daysLeft == 0L) "Happy ${Occasions.ordinal(a.yearsAtNext)} anniversary!" else "${a.daysLeft} days to your ${Occasions.ordinal(a.yearsAtNext)} anniversary") }
                s.partnerBirthday?.takeIf { it.daysLeft <= 60 }?.let { b -> add(if (b.daysLeft == 0L) "🎂 It's ${s.partner?.displayName}'s birthday!" else "🎂 ${s.partner?.displayName}'s birthday in ${b.daysLeft} days") }
                s.myBirthday?.takeIf { it.daysLeft == 0L }?.let { add("🎉 Happy birthday to you") }
            }
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                chips.forEach { c ->
                    Text(c, style = MaterialTheme.typography.labelMedium, color = Palette.WarmIvory,
                        modifier = Modifier.padding(top = 6.dp).clip(PillShape).background(Color.White.copy(alpha = 0.09f)).padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            if (s.pingsToday > 0) {
                Spacer(Modifier.height(12.dp))
                Text("You've reached for each other ${s.pingsToday} ${if (s.pingsToday == 1) "time" else "times"} today", style = MaterialTheme.typography.bodySmall, color = Palette.Blush)
            }
        }
    }
}

/** Asked once after pairing; either partner's answer appears for both. */
@Composable
private fun StartDatePrompt(onPick: () -> Unit, onDismiss: () -> Unit) {
    RisainaCard(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        Text("When did your story begin?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text("Add your anniversary to see how long you've been together and count down to the next one.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        Spacer(Modifier.height(12.dp))
        Row { com.risaina.app.core.designsystem.components.SecondaryButton("Choose the date", onClick = onPick); Spacer(Modifier.width(8.dp)); com.risaina.app.core.designsystem.components.GhostButton("Later", onClick = onDismiss) }
    }
}

/** Personal background behind Home: plain, a soft accent glow, a dusk wash, or your pinned photo. */
@Composable
private fun HomeBackground(style: String, photo: java.io.File?) {
    val bg = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primaryContainer
    when (style) {
        "PLAIN" -> Box(Modifier.fillMaxSize().background(bg))
        "DUSK" -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), bg, bg))))
        "PHOTO" -> Box(Modifier.fillMaxSize().background(bg)) {
            if (photo != null) {
                AsyncImage(model = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.55f), bg.copy(alpha = 0.92f), bg))))
            }
        }
        else -> Box(Modifier.fillMaxSize().background(bg).background(Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(200f, 0f), radius = 1400f)))
    }
}

@Composable
private fun PingArea(s: HomeUiState, vm: HomeViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TouchPingButton(
            state = s.button, emoji = s.selectedType.defaultEmoji, burstKey = s.burstKey,
            onTap = vm::tapPing, onLongPress = vm::openComposer,
        )
        val partner = s.partner?.displayName ?: "them"
        fun statusLine(p: com.risaina.app.core.model.Ping): String = when (p.status) {
            com.risaina.app.core.model.PingStatus.PENDING -> "Sending…"
            com.risaina.app.core.model.PingStatus.ACCEPTED -> "Sent. Waiting for $partner's phone…"
            com.risaina.app.core.model.PingStatus.DELIVERED -> "Delivered to $partner"
            com.risaina.app.core.model.PingStatus.SEEN -> "$partner saw it"
            com.risaina.app.core.model.PingStatus.FAILED -> "Didn't send"
            com.risaina.app.core.model.PingStatus.EXPIRED -> "Expired"
        }
        val label = when (val b = s.button) {
            PingButtonState.Sending -> "Sending…"
            is PingButtonState.Failed -> b.reason
            else -> s.lastSent?.let(::statusLine) ?: "Touch to send ${s.selectedType.label.lowercase()}"
        }
        val isError = s.button is PingButtonState.Failed
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (isError) MaterialTheme.colorScheme.error else Risaina.colors.textMuted,
            modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        val hint = when {
            isError -> "Tap to retry · hold to personalise"
            s.cooldownSeconds > 0 -> "Next ping in ${s.cooldownSeconds}s"
            else -> "Hold to personalise"
        }
        Text(hint, style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 4.dp).then(if (isError) Modifier.clickable { vm.retryLast() } else Modifier))
    }
}

@Composable
private fun Shortcuts(selected: PingType, onPick: (PingType) -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PingType.entries.filter { it != PingType.CUSTOM }.forEach { t ->
            val isSel = t == selected
            Row(
                Modifier.clip(PillShape)
                    .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onPick(t) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.defaultEmoji); Spacer(Modifier.width(6.dp))
                Text(t.label, style = MaterialTheme.typography.labelMedium, color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            }
        }
        Row(Modifier.clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onMore).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("✏️  Custom", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LatestMoment(s: HomeUiState, onOpen: () -> Unit) {
    val m = s.latestMoment ?: return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Spacer(Modifier.height(12.dp))
        SectionHeader(overline = "Latest from ${s.partner?.displayName ?: "them"}", title = when (m) { is Moment.FromPing -> "A ping"; is Moment.FromMessage -> "A note" })
        Spacer(Modifier.height(10.dp))
        RisainaCard(Modifier.fillMaxWidth(), onClick = onOpen) {
            when (m) {
                is Moment.FromPing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.ping.emoji, style = RisainaTextStyles.emojiLarge)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(m.ping.message ?: m.ping.type.defaultMessage, style = RisainaTextStyles.noteSerif)
                        Text(Format.relative(m.ping.createdAt), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
                    }
                }
                is Moment.FromMessage -> Column {
                    val msg = m.message
                    Text(
                        when (msg.type) { MessageType.PHOTO -> "Sent a photo"; MessageType.VOICE -> "Sent a voice note"; else -> msg.text ?: "" },
                        style = RisainaTextStyles.noteSerif, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Format.relative(msg.createdAt), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
                        if (s.unread > 0) { Spacer(Modifier.width(8.dp)); StatusPill("${s.unread} unread", PillTone.Info) }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickRow(nav: HomeNav) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickTile("Messages", com.risaina.app.feature.common.MailIcon, nav.messages, Modifier.weight(1f))
        QuickTile("Lists", com.risaina.app.feature.common.CheckIcon, nav.lists, Modifier.weight(1f))
        QuickTile("Memories", ImageIcon, nav.memories, Modifier.weight(1f))
        QuickTile("Timeline", TimelineIcon, nav.timeline, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickTile("Gifts", com.risaina.app.feature.common.HeartIcon, nav.gifts, Modifier.weight(1f))
        QuickTile("Widgets", WidgetsIcon, nav.widgets, Modifier.weight(1f))
        QuickTile("Wallpaper", WallpaperIcon, nav.wallpaper, Modifier.weight(1f))
        QuickTile("Settings", com.risaina.app.feature.common.SettingsIcon, nav.settings, Modifier.weight(1f))
    }
}

@Composable
private fun QuickTile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FavouriteMemory(s: HomeUiState, onOpen: () -> Unit) {
    val mem = s.favouriteMemory ?: return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        SectionHeader(overline = "Pinned", title = "A favourite")
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onOpen)) {
            if (s.favouriteImage != null) {
                AsyncImage(model = s.favouriteImage, contentDescription = mem.caption ?: "Pinned memory", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), startY = 200f)))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                if (!mem.caption.isNullOrBlank()) Text(mem.caption, style = RisainaTextStyles.noteSerif, color = Palette.WarmIvory, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(Format.relative(mem.takenAt ?: mem.createdAt), style = RisainaTextStyles.timestamp, color = Palette.Blush)
            }
        }
    }
}

@Composable
private fun VoiceShortcut(s: HomeUiState, onOpen: () -> Unit) {
    RisainaCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(PillShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(MicIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (s.latestVoice != null) "Hear their voice" else "Leave a voice note", style = MaterialTheme.typography.titleMedium)
                Text(
                    s.latestVoice?.let { "Latest voice note · ${Format.relative(it.createdAt)}" } ?: "Thirty seconds can say a lot.",
                    style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun RecentRow(item: ActivityItem) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.emoji, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            if (!item.subtitle.isNullOrBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Format.relative(item.at), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
            if (item.status != null) { val (l, t) = item.status.pill(); StatusPill(l, t, Modifier.padding(top = 4.dp)) }
        }
    }
}
