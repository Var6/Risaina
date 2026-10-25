package com.risaina.app.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Brand palette. These are the starting tokens from the brief, refined after composing real
 * screens: ivory surfaces get a faint warm tint, plum gets two lifted tiers for dark cards,
 * and every text colour is checked for WCAG AA against its intended surface.
 */
object Palette {
    val MidnightPlum = Color(0xFF1B111B)
    val Aubergine = Color(0xFF2B1C2B)
    val DeepWine = Color(0xFF51263D)
    val Rosewood = Color(0xFF844A65)
    val Blush = Color(0xFFE6BFCB)
    val WarmIvory = Color(0xFFFFF8F2)
    val SoftChampagne = Color(0xFFCDB38A)

    // Refinements
    val Plum2 = Color(0xFF34223A)        // dark elevated card
    val Plum3 = Color(0xFF3F2B45)        // dark higher surface / outlines
    val WineInk = Color(0xFF3A1A2C)      // dark text on light surfaces
    val RosewoodDeep = Color(0xFF6E3A54) // light-mode primary (AA on ivory)
    val BlushPale = Color(0xFFF6E7EC)    // light tinted surface
    val BlushMist = Color(0xFFFBF1F4)    // light surface container
    val IvoryDim = Color(0xFFF4EBE4)     // light surface variant
    val Champagne2 = Color(0xFFE6D7B8)   // dark-mode secondary text accent
    val ChampagneDeep = Color(0xFF8A7148) // light-mode secondary (AA)
    val Success = Color(0xFF3F7D5A)
    val SuccessOnDark = Color(0xFF9CD5B3)
    val Danger = Color(0xFFB4474B)
    val DangerOnDark = Color(0xFFF3A6A8)
    val Warning = Color(0xFF9B6B2E)
    val WarningOnDark = Color(0xFFE9C58F)

    val MutedOnLight = Color(0xFF6F5F69) // 5.1:1 on WarmIvory
    val MutedOnDark = Color(0xFFBBA7B6)  // 7.8:1 on MidnightPlum
}

/** Colours the Material scheme has no slot for. */
@Immutable
data class RisainaColors(
    val textMuted: Color,
    val accentWarm: Color,       // champagne accent for dates/metadata
    val success: Color,
    val danger: Color,
    val warning: Color,
    val pingGlow: Color,         // ring/glow colour for the Touch Ping
    val heroGradientStart: Color,
    val heroGradientEnd: Color,
    val divider: Color,
    val shimmer: Color,
    /** Inner colour of the Touch Ping button's gradient. */
    val pingFill: Color = Palette.Rosewood,
    /** Light accent for labels on the dark hero card. */
    val heroAccent: Color = Palette.SoftChampagne,
    val heroAccentSoft: Color = Palette.Blush,
)

val LocalRisainaColors = staticCompositionLocalOf<RisainaColors> {
    error("RisainaColors not provided")
}

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Palette.RosewoodDeep,
    onPrimary = Palette.WarmIvory,
    primaryContainer = Palette.Blush,
    onPrimaryContainer = Palette.WineInk,
    secondary = Palette.ChampagneDeep,
    onSecondary = Palette.WarmIvory,
    secondaryContainer = Color(0xFFF1E6CF),
    onSecondaryContainer = Color(0xFF3E2F14),
    tertiary = Palette.DeepWine,
    onTertiary = Palette.WarmIvory,
    tertiaryContainer = Palette.BlushPale,
    onTertiaryContainer = Palette.WineInk,
    background = Palette.WarmIvory,
    onBackground = Palette.WineInk,
    surface = Palette.WarmIvory,
    onSurface = Palette.WineInk,
    surfaceVariant = Palette.IvoryDim,
    onSurfaceVariant = Palette.MutedOnLight,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF4EE),
    surfaceContainer = Palette.BlushMist,
    surfaceContainerHigh = Palette.BlushPale,
    surfaceContainerHighest = Color(0xFFEFDDE3),
    outline = Color(0xFFCDB9C3),
    outlineVariant = Color(0xFFE9DCE2),
    error = Palette.Danger,
    onError = Palette.WarmIvory,
    errorContainer = Color(0xFFF9DDDD),
    onErrorContainer = Color(0xFF5E1D20),
    inverseSurface = Palette.Aubergine,
    inverseOnSurface = Palette.WarmIvory,
    inversePrimary = Palette.Blush,
    scrim = Color(0xCC1B111B),
)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Palette.Blush,
    onPrimary = Palette.WineInk,
    primaryContainer = Palette.DeepWine,
    onPrimaryContainer = Palette.Blush,
    secondary = Palette.Champagne2,
    onSecondary = Color(0xFF3E2F14),
    secondaryContainer = Color(0xFF4A3B24),
    onSecondaryContainer = Palette.Champagne2,
    tertiary = Palette.Rosewood,
    onTertiary = Palette.WarmIvory,
    tertiaryContainer = Palette.Plum3,
    onTertiaryContainer = Palette.Blush,
    background = Palette.MidnightPlum,
    onBackground = Palette.WarmIvory,
    surface = Palette.MidnightPlum,
    onSurface = Palette.WarmIvory,
    surfaceVariant = Palette.Plum2,
    onSurfaceVariant = Palette.MutedOnDark,
    surfaceContainerLowest = Color(0xFF140C14),
    surfaceContainerLow = Color(0xFF221622),
    surfaceContainer = Palette.Aubergine,
    surfaceContainerHigh = Palette.Plum2,
    surfaceContainerHighest = Palette.Plum3,
    outline = Color(0xFF6F5A6B),
    outlineVariant = Color(0xFF3F2E3E),
    error = Palette.DangerOnDark,
    onError = Color(0xFF4A1618),
    errorContainer = Color(0xFF6B2A2D),
    onErrorContainer = Color(0xFFF9DDDD),
    inverseSurface = Palette.WarmIvory,
    inverseOnSurface = Palette.WineInk,
    inversePrimary = Palette.RosewoodDeep,
    scrim = Color(0xCC000000),
)

internal val LightRisainaColors = RisainaColors(
    textMuted = Palette.MutedOnLight,
    accentWarm = Palette.ChampagneDeep,
    success = Palette.Success,
    danger = Palette.Danger,
    warning = Palette.Warning,
    pingGlow = Palette.Rosewood,
    heroGradientStart = Palette.DeepWine,
    heroGradientEnd = Palette.MidnightPlum,
    divider = Color(0xFFE9DCE2),
    shimmer = Color(0x33844A65),
)

internal val DarkRisainaColors = RisainaColors(
    textMuted = Palette.MutedOnDark,
    accentWarm = Palette.SoftChampagne,
    success = Palette.SuccessOnDark,
    danger = Palette.DangerOnDark,
    warning = Palette.WarningOnDark,
    pingGlow = Palette.Blush,
    heroGradientStart = Palette.DeepWine,
    heroGradientEnd = Palette.MidnightPlum,
    divider = Color(0xFF3F2E3E),
    shimmer = Color(0x33E6BFCB),
)
