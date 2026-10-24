package com.risaina.app.feature.widgets

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.color.colorProviders
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.risaina.app.core.datastore.WidgetSnapshot
import com.risaina.app.core.datastore.WidgetSnapshotStore
import com.risaina.app.core.sync.WidgetRefresher
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Hilt entry point for Glance code, which is instantiated by the system, not by Hilt. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun snapshotStore(): WidgetSnapshotStore
    fun pingRepository(): com.risaina.app.core.data.PingRepository
    fun haptics(): com.risaina.app.core.notifications.Haptics
}

fun Context.widgetEntryPoint(): WidgetEntryPoint = EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)

suspend fun Context.widgetSnapshot(): WidgetSnapshot = widgetEntryPoint().snapshotStore().current()

/** Brand colours for widgets, light and dark. */
object WidgetPalette {
    val colors: ColorProviders = colorProviders(
        primary = ColorProvider(Color(0xFF6E3A54), Color(0xFFE6BFCB)),
        onPrimary = ColorProvider(Color(0xFFFFF8F2), Color(0xFF3A1A2C)),
        primaryContainer = ColorProvider(Color(0xFFE6BFCB), Color(0xFF51263D)),
        onPrimaryContainer = ColorProvider(Color(0xFF3A1A2C), Color(0xFFE6BFCB)),
        secondary = ColorProvider(Color(0xFF8A7148), Color(0xFFE6D7B8)),
        onSecondary = ColorProvider(Color(0xFFFFF8F2), Color(0xFF3E2F14)),
        secondaryContainer = ColorProvider(Color(0xFFF1E6CF), Color(0xFF4A3B24)),
        onSecondaryContainer = ColorProvider(Color(0xFF3E2F14), Color(0xFFE6D7B8)),
        tertiary = ColorProvider(Color(0xFF51263D), Color(0xFF844A65)),
        onTertiary = ColorProvider(Color(0xFFFFF8F2), Color(0xFFFFF8F2)),
        tertiaryContainer = ColorProvider(Color(0xFFF6E7EC), Color(0xFF3F2B45)),
        onTertiaryContainer = ColorProvider(Color(0xFF3A1A2C), Color(0xFFE6BFCB)),
        error = ColorProvider(Color(0xFFB4474B), Color(0xFFF3A6A8)),
        errorContainer = ColorProvider(Color(0xFFF9DDDD), Color(0xFF6B2A2D)),
        onError = ColorProvider(Color(0xFFFFF8F2), Color(0xFF4A1618)),
        onErrorContainer = ColorProvider(Color(0xFF5E1D20), Color(0xFFF9DDDD)),
        background = ColorProvider(Color(0xFFFFF8F2), Color(0xFF1B111B)),
        onBackground = ColorProvider(Color(0xFF3A1A2C), Color(0xFFFFF8F2)),
        surface = ColorProvider(Color(0xFFFBF1F4), Color(0xFF2B1C2B)),
        onSurface = ColorProvider(Color(0xFF3A1A2C), Color(0xFFFFF8F2)),
        surfaceVariant = ColorProvider(Color(0xFFF4EBE4), Color(0xFF34223A)),
        onSurfaceVariant = ColorProvider(Color(0xFF6F5F69), Color(0xFFBBA7B6)),
        outline = ColorProvider(Color(0xFFCDB9C3), Color(0xFF6F5A6B)),
        inverseOnSurface = ColorProvider(Color(0xFFFFF8F2), Color(0xFF3A1A2C)),
        inverseSurface = ColorProvider(Color(0xFF2B1C2B), Color(0xFFFFF8F2)),
        inversePrimary = ColorProvider(Color(0xFFE6BFCB), Color(0xFF6E3A54)),
        widgetBackground = ColorProvider(Color(0xFFFFF8F2), Color(0xFF1B111B)),
    )
}

object WidgetStyles {
    val title @Composable get() = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Serif)
    val note @Composable get() = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontFamily = FontFamily.Serif)
    val caption @Composable get() = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp)
    val big @Composable get() = TextStyle(color = GlanceTheme.colors.primary, fontSize = 30.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Serif)
    val emoji @Composable get() = TextStyle(fontSize = 40.sp)
}

/** Standard widget frame: themed surface with rounded corners and padding. */
@Composable
fun WidgetFrame(modifier: GlanceModifier = GlanceModifier, content: @Composable () -> Unit) {
    GlanceTheme(colors = WidgetPalette.colors) {
        Box(modifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(22.dp).padding(14.dp)) { content() }
    }
}

@Composable
fun Placeholder(line: String, sub: String? = null) {
    androidx.glance.layout.Column {
        Text(line, style = WidgetStyles.title)
        if (sub != null) Text(sub, style = WidgetStyles.caption)
    }
}

fun openApp(context: Context, deepLink: String): Action = actionStartActivity(
    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(deepLink)).apply { setPackage(context.packageName); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) },
)

/** Decode a cached photo small enough for RemoteViews (long edge ≤ 720 px). */
fun loadWidgetBitmap(path: String?): Bitmap? {
    val f = path?.let(::File)?.takeIf { it.exists() } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 720) sample *= 2
    return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Suppress("unused")
private val keepContentScale = ContentScale.Crop

/** Re-render every widget from the snapshot store. Called by the sync layer after relevant events. */
@Singleton
class GlanceWidgetRefresher @Inject constructor(@ApplicationContext private val context: Context) : WidgetRefresher {
    override suspend fun refreshAll() {
        PhotoWidget().updateAll(context)
        LoveNoteWidget().updateAll(context)
        TouchPingWidget().updateAll(context)
        EmojiWidget().updateAll(context)
        CountdownWidget().updateAll(context)
        VoiceNoteWidget().updateAll(context)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetRefresherModule {
    @Binds abstract fun bind(impl: GlanceWidgetRefresher): WidgetRefresher
}
