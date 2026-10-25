package com.risaina.app.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.datastore.WidgetSnapshot
import com.risaina.app.core.domain.CountdownCalculator
import com.risaina.app.core.model.PingType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/*
 * Six home-screen widgets. Each reads the WidgetSnapshot (already privacy-filtered by the sync
 * layer), never the network, and taps through to the right screen. Updates are requested by
 * GlanceWidgetRefresher after relevant events; Android may coalesce them.
 */

private val SMALL = DpSize(110.dp, 110.dp)
private val WIDE = DpSize(250.dp, 110.dp)
private val TALL = DpSize(250.dp, 250.dp)

private fun relative(millis: Long?): String? {
    millis ?: return null
    val mins = (System.currentTimeMillis() - millis) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 60 * 24 -> "${mins / 60} h ago"
        else -> "${mins / (60 * 24)} d ago"
    }
}

// ---------------- Photo ----------------
class PhotoWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = context.widgetSnapshot()
        val bitmap = loadWidgetBitmap(snap.photoPath)
        provideContent {
            GlanceTheme(colors = WidgetPalette.colors) {
                Box(GlanceModifier.fillMaxSize().cornerRadius(22.dp).background(GlanceTheme.colors.widgetBackground).clickable(openApp(context, "risaina://app/memories"))) {
                    if (bitmap != null) {
                        Image(provider = ImageProvider(bitmap), contentDescription = snap.photoCaption ?: "Shared photo", contentScale = ContentScale.Crop, modifier = GlanceModifier.fillMaxSize())
                        if (!snap.photoCaption.isNullOrBlank()) {
                            Box(GlanceModifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomStart) {
                                Text(snap.photoCaption, style = TextStyle(color = androidx.glance.unit.ColorProvider(androidx.compose.ui.graphics.Color(0xFFFFF8F2)), fontSize = 13.sp, fontFamily = androidx.glance.text.FontFamily.Serif), maxLines = 2)
                            }
                        }
                    } else {
                        Box(GlanceModifier.fillMaxSize().padding(14.dp)) {
                            Placeholder(if (snap.paired) "Pin a memory" else "Pair to begin", if (snap.paired) "It will appear here" else "Open Risaina")
                        }
                    }
                }
            }
        }
    }
}
class PhotoWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = PhotoWidget() }

// ---------------- Love note ----------------
class LoveNoteWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = context.widgetSnapshot()
        provideContent {
            WidgetFrame(GlanceModifier.clickable(openApp(context, "risaina://app/messages"))) {
                Column {
                    when {
                        !snap.paired -> Placeholder("Pair to begin", "Open Risaina")
                        snap.latestNoteAtMillis == null -> Placeholder("No notes yet", "Tap to write one")
                        snap.latestNoteText == null -> { Text("A note arrived", style = WidgetStyles.title); Text("Previews are off · ${relative(snap.latestNoteAtMillis)}", style = WidgetStyles.caption) }
                        else -> {
                            Text("“${snap.latestNoteText}”", style = WidgetStyles.note, maxLines = if (LocalSize.current.height > 150.dp) 6 else 3)
                            Spacer(GlanceModifier.height(6.dp))
                            Text("${if (snap.latestNoteFromPartner) snap.partnerName ?: "Them" else "You"} · ${relative(snap.latestNoteAtMillis)}", style = WidgetStyles.caption)
                        }
                    }
                }
            }
        }
    }
}
class LoveNoteWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = LoveNoteWidget() }

// ---------------- Touch Ping ----------------
class TouchPingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = context.widgetSnapshot()
        val status = WidgetPingStatus.current()
        provideContent {
            WidgetFrame {
                Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        GlanceModifier.fillMaxWidth().cornerRadius(40.dp).background(GlanceTheme.colors.primary).padding(vertical = 10.dp)
                            .clickable(if (snap.paired) actionRunCallback<SendPingAction>() else openApp(context, "risaina://app/home")),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when { !snap.paired -> "Pair first"; status == "sending" -> "Sending…"; status == "sent" -> "Sent ✓"; status == "failed" -> "Try again"; status == "cooldown" -> "Wait a moment"; else -> "💭  Thinking of you" },
                            style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 14.sp, fontWeight = androidx.glance.text.FontWeight.Medium),
                        )
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    Text(snap.partnerName?.let { "to $it" } ?: "Touch Ping", style = WidgetStyles.caption)
                }
            }
        }
    }
}
class TouchPingWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = TouchPingWidget() }

/** Transient status shown on the ping widget for a few seconds after a tap. In-memory on purpose. */
object WidgetPingStatus {
    @Volatile private var status: String? = null
    @Volatile private var at: Long = 0
    fun set(s: String?) { status = s; at = System.currentTimeMillis() }
    fun current(): String? = status?.takeIf { System.currentTimeMillis() - at < 8_000 }
}

class SendPingAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val ep = context.widgetEntryPoint()
        WidgetPingStatus.set("sending"); TouchPingWidget().updateAll(context)
        ep.haptics().tap()
        val r = ep.pingRepository().send(PingType.THINKING_OF_YOU, PingType.THINKING_OF_YOU.defaultEmoji, null)
        WidgetPingStatus.set(
            when (r) {
                is AppResult.Success -> { ep.haptics().confirm(); "sent" }
                is AppResult.Failure -> if (r.error is com.risaina.app.core.common.AppError.RateLimited) "cooldown" else "failed"
            },
        )
        TouchPingWidget().updateAll(context)
        kotlinx.coroutines.delay(8_000)
        TouchPingWidget().updateAll(context)
    }
}

// ---------------- Emoji ----------------
class EmojiWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = context.widgetSnapshot()
        provideContent {
            WidgetFrame(GlanceModifier.clickable(openApp(context, "risaina://app/home"))) {
                Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    when {
                        !snap.paired -> Placeholder("Pair to begin")
                        snap.latestEmojiAtMillis == null -> Placeholder("No pings yet", "The first one is a tap away")
                        snap.latestEmoji == null -> { Text("•", style = WidgetStyles.big); Text("A ping arrived · ${relative(snap.latestEmojiAtMillis)}", style = WidgetStyles.caption) }
                        else -> {
                            Text(snap.latestEmoji, style = WidgetStyles.emoji)
                            Text("${snap.latestEmojiLabel ?: "Ping"} · ${relative(snap.latestEmojiAtMillis)}", style = WidgetStyles.caption)
                        }
                    }
                }
            }
        }
    }
}
class EmojiWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = EmojiWidget() }

// ---------------- Countdown ----------------
class CountdownWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = context.widgetSnapshot()
        val date = snap.countdownDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val countdown = date?.let { CountdownCalculator.until(it, LocalDate.now()) }
        provideContent {
            WidgetFrame(GlanceModifier.clickable(openApp(context, "risaina://app/home"))) {
                Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    if (countdown == null) {
                        Placeholder(if (snap.paired) "No date set" else "Pair to begin", if (snap.paired) "Add your next visit in Timeline" else null)
                    } else {
                        Text(countdown.headline, style = WidgetStyles.big)
                        Text(snap.countdownLabel ?: "Until we meet", style = WidgetStyles.title, maxLines = 1)
                        Text(date.format(DateTimeFormatter.ofPattern("d MMM yyyy")), style = WidgetStyles.caption)
                    }
                }
            }
        }
    }
}
class CountdownWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = CountdownWidget() }

// ---------------- Voice note ----------------
class VoiceNoteWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = context.widgetSnapshot()
        provideContent {
            WidgetFrame(GlanceModifier.clickable(openApp(context, "risaina://app/messages"))) {
                Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Text("🎙", style = WidgetStyles.emoji)
                    when {
                        !snap.paired -> Placeholder("Pair to begin")
                        snap.latestVoiceNoteId == null -> Placeholder("No voice notes yet", "Tap to record one")
                        else -> { Text("Latest voice note", style = WidgetStyles.title); Text(relative(snap.latestVoiceAtMillis) ?: "", style = WidgetStyles.caption) }
                    }
                }
            }
        }
    }
}
class VoiceNoteWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = VoiceNoteWidget() }


// ---------------- Gift (works on lock screens that allow widgets) ----------------
class GiftWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = context.widgetSnapshot()
        provideContent {
            WidgetFrame(GlanceModifier.clickable(openApp(context, "risaina://app/home"))) {
                Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    when {
                        !snap.paired -> Placeholder("Pair to begin")
                        snap.latestGiftAtMillis == null -> { Text("🎁", style = WidgetStyles.emoji); Text("No gifts yet", style = WidgetStyles.caption) }
                        else -> {
                            Text(snap.latestGiftEmoji ?: "🎁", style = WidgetStyles.emoji)
                            Text(snap.latestGiftLine ?: "A gift arrived", style = WidgetStyles.title, maxLines = 2)
                            Text("from ${snap.partnerName ?: "them"} · ${relative(snap.latestGiftAtMillis)}", style = WidgetStyles.caption)
                        }
                    }
                }
            }
        }
    }
}
class GiftWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = GiftWidget() }
