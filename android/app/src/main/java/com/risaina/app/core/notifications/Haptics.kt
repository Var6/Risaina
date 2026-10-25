package com.risaina.app.core.notifications

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Small, deliberate haptics. Uses predefined effects where the device supports them. */
@Singleton
class Haptics @Inject constructor(@ApplicationContext context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    /** Local confirmation that the tap registered (not that anything was delivered). */
    fun tap() = play(VibrationEffect.EFFECT_CLICK, fallbackMs = 12)

    /** The ping was accepted by the server. */
    fun confirm() = play(VibrationEffect.EFFECT_HEAVY_CLICK, fallbackMs = 25)

    /** A ping from the partner arrived while the app is open. */
    fun arrival() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(NotificationChannels.HEARTBEAT, -1))
    }

    fun error() = play(VibrationEffect.EFFECT_DOUBLE_CLICK, fallbackMs = 30)

    private fun play(effect: Int, fallbackMs: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(effect))
        } else {
            v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
