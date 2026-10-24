package com.risaina.app.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion vocabulary. Every animation in the app picks one of these rather than inventing a
 * duration. Values were tuned on-device (Pixel 6a class) so taps feel immediate and content
 * transitions never fight the finger. When the user asks for reduced motion the specs collapse
 * to short fades or snaps and springs become critically damped tweens.
 */
@Immutable
data class MotionTokens(
    val reduced: Boolean,
    /** Direct manipulation (press scale, toggles). */
    val instantMs: Int,
    /** Small button and state changes. */
    val fastMs: Int,
    /** Card and content transitions. */
    val standardMs: Int,
    /** Special moments: sending a ping, pairing success. */
    val expressiveMs: Int,
    /** Ambient drift; low frequency, large period. */
    val ambientMs: Int,
) {
    val easeStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val easeEmphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val easeExit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> instant(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(instantMs, easing = easeStandard)
    fun <T> fast(): FiniteAnimationSpec<T> = tween(if (reduced) 80 else fastMs, easing = easeStandard)
    fun <T> standard(): FiniteAnimationSpec<T> = tween(if (reduced) 120 else standardMs, easing = easeEmphasized)
    fun <T> expressive(): FiniteAnimationSpec<T> = tween(if (reduced) 150 else expressiveMs, easing = easeEmphasized)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(if (reduced) 80 else fastMs, easing = easeExit)

    /** Tactile press/release. Slight overshoot unless reduced. */
    fun <T> springTactile(): FiniteAnimationSpec<T> =
        if (reduced) tween(instantMs, easing = easeStandard)
        else spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)

    /** Settling content such as cards sliding into place. */
    fun <T> springGentle(): FiniteAnimationSpec<T> =
        if (reduced) tween(standardMs, easing = easeEmphasized)
        else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)

    /** The ping bloom: quick, airy, visibly alive. */
    fun <T> springBloom(): FiniteAnimationSpec<T> =
        if (reduced) tween(expressiveMs, easing = easeEmphasized)
        else spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)
}

fun motionTokens(reduced: Boolean) = MotionTokens(
    reduced = reduced,
    instantMs = 90,
    fastMs = 160,
    standardMs = 280,
    expressiveMs = 520,
    ambientMs = 6000,
)

val LocalMotion = staticCompositionLocalOf { motionTokens(reduced = false) }

object Motion {
    val tokens: MotionTokens
        @Composable get() = LocalMotion.current
}
