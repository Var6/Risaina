package com.risaina.app.core.designsystem.theme

import android.provider.Settings
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Theme root. Dark mode follows the system by default; the user can force a mode in settings.
 * Reduced motion honours both the OS animator scale (0 = animations off) and the in-app toggle.
 */
@Composable
fun RisainaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemReducedMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val reduced = reducedMotion || systemReducedMotion
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) DarkRisainaColors else LightRisainaColors
    val motion = remember(reduced) { motionTokens(reduced) }

    CompositionLocalProvider(
        LocalRisainaColors provides extended,
        LocalSpacing provides Spacing(),
        LocalMotion provides motion,
        LocalIndication provides ripple(color = colorScheme.primary),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RisainaTypography,
            shapes = RisainaShapes,
            content = content,
        )
    }
}

/** Convenience accessors so screens read `Risaina.colors.textMuted` instead of digging into locals. */
object Risaina {
    val colors: RisainaColors
        @Composable @ReadOnlyComposable get() = LocalRisainaColors.current
    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
    val motion: MotionTokens
        @Composable @ReadOnlyComposable get() = LocalMotion.current
}
