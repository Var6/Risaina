package com.risaina.app.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Personal look. Each person picks their own accent and background; it is stored on their phone
 * only. Rosewood is the original Risaina look and the default. Every accent's light-mode primary
 * keeps ≥ 4.5:1 contrast on the ivory background; dark-mode primaries are light tones on plum.
 */
enum class AccentTheme(
    val label: String,
    val lightPrimary: Color,
    val lightContainer: Color,
    val onLightContainer: Color,
    val darkPrimary: Color,
    val darkContainer: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val pingFill: Color,
) {
    ROSEWOOD("Rosewood", Color(0xFF6E3A54), Color(0xFFE6BFCB), Color(0xFF3A1A2C), Color(0xFFE6BFCB), Color(0xFF51263D), Color(0xFF51263D), Color(0xFF1B111B), Color(0xFF844A65)),
    OCEAN("Ocean", Color(0xFF2F5D7C), Color(0xFFCFE3EF), Color(0xFF0F2A3B), Color(0xFFA9D0EA), Color(0xFF1F4A66), Color(0xFF1F4A66), Color(0xFF0E1A24), Color(0xFF4A7DA0)),
    SAGE("Sage", Color(0xFF3F6B57), Color(0xFFD3E6DA), Color(0xFF14301F), Color(0xFFB3D9C4), Color(0xFF2E5444), Color(0xFF2E5444), Color(0xFF111B16), Color(0xFF5E8C76)),
    SUNSET("Sunset", Color(0xFF94442A), Color(0xFFF6D5C6), Color(0xFF3B160A), Color(0xFFF3B79C), Color(0xFF7A3420), Color(0xFF7A3420), Color(0xFF221210), Color(0xFFC06A48)),
    LAVENDER("Lavender", Color(0xFF5B4B8A), Color(0xFFDCD3F2), Color(0xFF221A3D), Color(0xFFC9BDF0), Color(0xFF43366E), Color(0xFF43366E), Color(0xFF15121F), Color(0xFF7A68B0)),
    MIDNIGHT("Midnight", Color(0xFF2E3A59), Color(0xFFD2D8E8), Color(0xFF111827), Color(0xFFB8C3E6), Color(0xFF222B45), Color(0xFF222B45), Color(0xFF0D1018), Color(0xFF4B5A85)),
}

enum class BackgroundStyle(val label: String) {
    PLAIN("Plain"),
    GLOW("Soft glow"),
    DUSK("Dusk"),
    PHOTO("Our photo"),
}

/**
 * Whole-app recolouring. Rosewood returns the hand-tuned original scheme. Other accents derive
 * every tinted role from the accent: primary + secondary families, the five surface-container
 * steps (cards, chips, tiles), background, outlines and the warm "overline" accent. Neutral text
 * colours stay neutral so contrast is preserved.
 */
internal fun ColorScheme.withAccent(a: AccentTheme, dark: Boolean): ColorScheme {
    if (a == AccentTheme.ROSEWOOD) return this
    val base = if (dark) Color(0xFF121014) else Color(0xFFFCFAF8)
    fun tint(amount: Float) = lerp(base, if (dark) a.heroStart else a.lightContainer, amount)
    return if (dark) copy(
        primary = a.darkPrimary, onPrimary = Color(0xFF101014),
        primaryContainer = a.darkContainer, onPrimaryContainer = a.darkPrimary,
        secondary = lerp(a.darkPrimary, Color.White, 0.25f), onSecondary = Color(0xFF101014),
        secondaryContainer = lerp(a.darkContainer, base, 0.35f), onSecondaryContainer = a.darkPrimary,
        tertiary = a.pingFill, onTertiary = Color.White,
        tertiaryContainer = tint(0.55f), onTertiaryContainer = a.darkPrimary,
        background = tint(0.10f), surface = tint(0.10f),
        surfaceVariant = tint(0.32f),
        surfaceContainerLowest = tint(0.06f), surfaceContainerLow = tint(0.16f), surfaceContainer = tint(0.24f),
        surfaceContainerHigh = tint(0.34f), surfaceContainerHighest = tint(0.44f),
        outline = lerp(a.darkPrimary, base, 0.55f), outlineVariant = tint(0.48f),
        inversePrimary = a.lightPrimary,
    ) else copy(
        primary = a.lightPrimary, onPrimary = Color.White,
        primaryContainer = a.lightContainer, onPrimaryContainer = a.onLightContainer,
        secondary = lerp(a.lightPrimary, Color.Black, 0.12f), onSecondary = Color.White,
        secondaryContainer = lerp(a.lightContainer, Color.White, 0.35f), onSecondaryContainer = a.onLightContainer,
        tertiary = a.heroStart, onTertiary = Color.White,
        tertiaryContainer = tint(0.55f), onTertiaryContainer = a.onLightContainer,
        background = tint(0.10f), surface = tint(0.10f),
        surfaceVariant = tint(0.35f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = tint(0.18f), surfaceContainer = tint(0.28f),
        surfaceContainerHigh = tint(0.42f), surfaceContainerHighest = tint(0.58f),
        outline = lerp(a.lightPrimary, base, 0.6f), outlineVariant = tint(0.7f),
        inversePrimary = a.lightContainer,
    )
}

internal fun RisainaColors.withAccent(a: AccentTheme, dark: Boolean): RisainaColors =
    if (a == AccentTheme.ROSEWOOD) this else copy(
        pingGlow = if (dark) a.darkPrimary else a.pingFill,
        heroGradientStart = a.heroStart,
        heroGradientEnd = a.heroEnd,
        pingFill = a.pingFill,
        accentWarm = if (dark) lerp(a.darkPrimary, Color.White, 0.2f) else a.lightPrimary,
        divider = lerp(if (dark) Color(0xFF121014) else Color(0xFFFCFAF8), if (dark) a.heroStart else a.lightContainer, 0.6f),
        shimmer = a.pingFill.copy(alpha = 0.2f),
        heroAccent = lerp(a.pingFill, Color.White, 0.55f),
        heroAccentSoft = lerp(a.pingFill, Color.White, 0.7f),
    )
