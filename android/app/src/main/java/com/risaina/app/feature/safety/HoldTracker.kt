package com.risaina.app.feature.safety

/**
 * Timing of a press-and-hold confirmation. Pure (unit-tested), so the rule "letting go early never
 * sends" does not depend on animation code: the hold fires from [tick] while the finger is still
 * down, exactly once, and only after the full duration. Lifting the finger ([release]) before that
 * cancels it; nothing fires on release.
 */
class HoldTracker(val durationMs: Long = 2_000L) {
    private var pressedAt = -1L
    private var fired = false

    val pressed: Boolean get() = pressedAt >= 0

    fun press(nowMs: Long) {
        pressedAt = nowMs
        fired = false
    }

    fun progress(nowMs: Long): Float =
        if (pressedAt < 0) 0f else ((nowMs - pressedAt).toFloat() / durationMs).coerceIn(0f, 1f)

    /** Call on every frame while pressed. True exactly once, when the hold completes. */
    fun tick(nowMs: Long): Boolean {
        if (pressedAt < 0 || fired) return false
        if (nowMs - pressedAt < durationMs) return false
        fired = true
        return true
    }

    /** Quarter steps (0 to 4) for haptic ticks at 25, 50 and 75 percent. */
    fun quarter(nowMs: Long): Int = (progress(nowMs) * 4).toInt().coerceIn(0, 4)

    /** The finger lifted or the gesture was cancelled. Returns whether the hold had already fired. */
    fun release(): Boolean {
        pressedAt = -1
        return fired
    }
}
