package com.risaina.app.feature.widgets

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.risaina.app.core.datastore.WidgetSnapshot
import com.risaina.app.core.domain.CountdownCalculator
import java.time.LocalDate

/**
 * "Us": one widget for everything. It always shows the newest thing the partner did (a ping, a
 * gift, a note or mood, a photo, a voice note), your countdown, and a button to reach them. It
 * glows rose and says "New" until this phone opens Risaina or replies, so the widget itself is the
 * "they're thinking of you" signal. Resizes from 2×2 up to a full 4×4 photo card.
 */
class UsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = context.widgetEntryPoint().snapshotStore()
        val first = store.current()
        provideContent {
            val snap = liveSnapshot(store, first)
            val status = WidgetPingStatus.state.collectAsState().value
            val size = LocalSize.current
            val roomy = size.height >= 200.dp
            val wide = size.width >= 200.dp
            val unseen = snap.paired && snap.latestUnseen
            val showPhoto = snap.latestKind == "photo" || (roomy && snap.latestKind == null)
            val bitmap = remember(snap.photoPath, showPhoto) { if (showPhoto) loadWidgetBitmap(snap.photoPath) else null }
            val countdownDate = snap.countdownDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val countdown = countdownDate?.let { CountdownCalculator.until(it, LocalDate.now()) }

            WidgetFrame(highlight = unseen) {
                if (!snap.paired) {
                    Column(GlanceModifier.fillMaxSize().clickable(openApp(context, "risaina://app/home")), verticalAlignment = Alignment.CenterVertically) {
                        Placeholder("Pair to begin", "Open Risaina")
                    }
                } else Column(GlanceModifier.fillMaxSize()) {
                    // Who, and whether there's something new from them.
                    Row(GlanceModifier.fillMaxWidth().clickable(openApp(context, "risaina://app/home")), verticalAlignment = Alignment.CenterVertically) {
                        Text(snap.partnerName ?: "Your person", style = WidgetStyles.title, maxLines = 1, modifier = GlanceModifier.defaultWeight())
                        Text(
                            if (unseen) "● New" else usRelative(snap.latestAtMillis) ?: "",
                            style = if (unseen) WidgetStyles.badge else WidgetStyles.caption, maxLines = 1,
                        )
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    // The newest thing they did.
                    Box(
                        GlanceModifier.fillMaxWidth().defaultWeight().clickable(openApp(context, deepLinkFor(snap.latestKind))),
                        contentAlignment = Alignment.CenterStart,
                    ) { Latest(snap, bitmap, roomy) }
                    if (countdown != null && (wide || roomy)) {
                        Spacer(GlanceModifier.height(4.dp))
                        Text("⏳ ${countdown.headline} · ${snap.countdownLabel ?: "until we meet"}", style = WidgetStyles.caption, maxLines = 1)
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    Box(
                        GlanceModifier.fillMaxWidth().cornerRadius(40.dp).background(GlanceTheme.colors.primary).padding(vertical = 8.dp)
                            .clickable(actionRunCallback<SendPingAction>()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (status) {
                                "sending" -> "Sending…"; "sent" -> "Sent ✓"; "failed" -> "Try again"; "cooldown" -> "Wait a moment"
                                else -> if (unseen && snap.latestKind in setOf("ping", "gift")) "💭  Send one back" else "💭  Thinking of you"
                            },
                            style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

class UsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = UsWidget() }

@androidx.compose.runtime.Composable
private fun Latest(snap: WidgetSnapshot, bitmap: android.graphics.Bitmap?, roomy: Boolean) {
    when (snap.latestKind) {
        "photo" -> if (bitmap != null) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                Image(ImageProvider(bitmap), contentDescription = snap.photoCaption ?: "Their latest photo", contentScale = ContentScale.Crop, modifier = GlanceModifier.fillMaxSize().cornerRadius(14.dp))
                if (roomy && !snap.photoCaption.isNullOrBlank()) {
                    Box(GlanceModifier.fillMaxWidth().background(androidx.glance.unit.ColorProvider(Color(0x99000000))).cornerRadius(14.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(snap.photoCaption, style = TextStyle(color = androidx.glance.unit.ColorProvider(Color(0xFFFFF8F2)), fontSize = 13.sp), maxLines = 2)
                    }
                }
            }
        } else EmojiLine("📷", "Sent you a photo")
        "note" -> if (snap.latestNoteText != null) {
            Text("“${snap.latestNoteText}”", style = WidgetStyles.note, maxLines = if (roomy) 6 else 2)
        } else EmojiLine("✉️", "A note arrived")
        "gift" -> EmojiLine(snap.latestGiftEmoji ?: "🎁", snap.latestGiftLine ?: "Sent you a gift")
        "ping" -> EmojiLine(snap.latestPingEmoji ?: "💗", snap.latestPingLine ?: "Thinking of you")
        "voice" -> EmojiLine("🎙", "Sent you a voice note")
        else -> Text("Nothing new yet. Send the first ping.", style = WidgetStyles.caption, maxLines = 2)
    }
}

@androidx.compose.runtime.Composable
private fun EmojiLine(emoji: String, line: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, style = TextStyle(fontSize = 34.sp))
        Spacer(GlanceModifier.width(10.dp))
        Text(line, style = WidgetStyles.title, maxLines = 2)
    }
}

private fun deepLinkFor(kind: String?) = when (kind) {
    "note", "photo", "voice" -> "risaina://app/messages"
    else -> "risaina://app/home"
}

private fun usRelative(millis: Long?): String? {
    millis ?: return null
    val mins = (System.currentTimeMillis() - millis) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}
