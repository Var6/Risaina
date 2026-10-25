package com.risaina.app.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

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

internal fun ColorScheme.withAccent(a: AccentTheme, dark: Boolean): ColorScheme =
    if (a == AccentTheme.ROSEWOOD) this else if (dark) copy(
        primary = a.darkPrimary,
        primaryContainer = a.darkContainer,
        onPrimaryContainer = a.darkPrimary,
        inversePrimary = a.lightPrimary,
        tertiary = a.pingFill,
    ) else copy(
        primary = a.lightPrimary,
        primaryContainer = a.lightContainer,
        onPrimaryContainer = a.onLightContainer,
        inversePrimary = a.lightContainer,
        tertiary = a.heroStart,
    )

internal fun RisainaColors.withAccent(a: AccentTheme, dark: Boolean): RisainaColors = copy(
    pingGlow = if (dark) a.darkPrimary else a.pingFill,
    heroGradientStart = a.heroStart,
    heroGradientEnd = a.heroEnd,
    pingFill = a.pingFill,
)
