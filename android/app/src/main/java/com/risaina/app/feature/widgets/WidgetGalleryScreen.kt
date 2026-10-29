package com.risaina.app.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.SecondaryButton
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.feature.common.BackIcon

private data class WidgetInfo(val title: String, val body: String, val emoji: String, val receiver: Class<*>)

@Composable
fun WidgetGalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AppWidgetManager::class.java) }
    val canPin = remember { manager?.isRequestPinAppWidgetSupported == true }
    // Two widgets cover everything; the single-purpose ones stay for people who like them.
    val recommended = remember {
        listOf(
            WidgetInfo("Us · all in one", "Their latest ping, gift, note or photo, your countdown, and a button to reach them. Glows when they reach out. Resize it from small to a full photo card.", "💞", UsWidgetReceiver::class.java),
            WidgetInfo("Touch Ping", "A small one-tap button. Shows their ping when it arrives, with \u201cSend one back\u201d.", "💭", TouchPingWidgetReceiver::class.java),
        )
    }
    val more = remember {
        listOf(
            WidgetInfo("Gift", "The latest gift from your partner.", "🎁", GiftWidgetReceiver::class.java),
            WidgetInfo("Photo", "The latest photo your partner sent.", "🖼", PhotoWidgetReceiver::class.java),
            WidgetInfo("Love note", "The latest note or mood, with when.", "✉️", LoveNoteWidgetReceiver::class.java),
            WidgetInfo("Emoji", "The latest symbol you received.", "✨", EmojiWidgetReceiver::class.java),
            WidgetInfo("Countdown", "Days until you're together, or the next milestone.", "⏳", CountdownWidgetReceiver::class.java),
            WidgetInfo("Voice note", "A shortcut to the latest voice note.", "🎙", VoiceNoteWidgetReceiver::class.java),
        )
    }
    var showMore by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Widgets", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(
                if (canPin) "One widget is enough: \u201cUs\u201d shows whatever your partner did last and lights up when they reach out. Add Touch Ping too if you want a small button."
                else "Your launcher doesn't support adding widgets from inside apps. Long-press your home screen, choose Widgets, and look for Risaina.",
                style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted,
            )
            Spacer(Modifier.height(16.dp))
            recommended.forEach { w -> WidgetRow(w, canPin) { requestPin(context, manager, w.receiver) } }
            Spacer(Modifier.height(16.dp))
            LockScreenSection()
            Spacer(Modifier.height(16.dp))
            com.risaina.app.feature.delivery.DeliveryHealthCard()
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.TextButton(onClick = { showMore = !showMore }) {
                Text(if (showMore) "Hide single-purpose widgets" else "More widgets (${more.size})", style = MaterialTheme.typography.titleSmall)
            }
            if (showMore) more.forEach { w -> WidgetRow(w, canPin) { requestPin(context, manager, w.receiver) } }
            Spacer(Modifier.height(16.dp))
            Text("What widgets may show is controlled in Settings → Privacy. Photos and note text can be hidden there.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun WidgetRow(w: WidgetInfo, canPin: Boolean, onAdd: () -> Unit) {
    RisainaCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.emoji, style = RisainaTextStyles.emojiLarge.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(w.title, style = MaterialTheme.typography.titleMedium)
                Text(w.body, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
        }
        if (canPin) {
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Add to home screen", onClick = onAdd)
        }
    }
}

private fun requestPin(context: Context, manager: AppWidgetManager?, receiver: Class<*>) {
    manager ?: return
    runCatching { manager.requestPinAppWidget(ComponentName(context, receiver), null, null) }
}


/**
 * Lock screen: real lock-screen widgets exist only on some Android versions and launchers, so the
 * widgets declare the "keyguard" category for those, and every phone gets the opt-in card.
 */
@Composable
private fun LockScreenSection(vm: com.risaina.app.feature.settings.SettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    RisainaCard(Modifier.fillMaxWidth()) {
        Text("On your lock screen", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Turn on the lock-screen card to keep your partner's mood or note on your lock screen. A new gift or ping shows there when its own notification is gone. It's silent, never repeats a notification you already have, and stays away if you swipe it until there's something new.",
            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("Lock-screen card", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = s.prefs.lockScreenCard, onCheckedChange = { v -> vm.update { it.copy(lockScreenCard = v) }; vm.refreshLockCard() })
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Lock-screen widgets: if your phone lets you add widgets to the lock screen (newer Android versions and some Samsung phones: long-press the lock screen → Widgets), Risaina's widgets appear there too.",
            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
        )
    }
}
