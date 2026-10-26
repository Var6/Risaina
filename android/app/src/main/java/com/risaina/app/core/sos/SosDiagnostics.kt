package com.risaina.app.core.sos

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.risaina.app.core.network.SosDeliveredRequest

/**
 * Device state at ring time, sent with the delivered receipt. The phones run a sideloaded build with
 * no adb attached, so this is the only way to learn afterwards why an SOS was quiet (alarm stream
 * muted by silent mode, Do Not Disturb, notifications off). State only, never content.
 */
object SosDiagnostics {
    fun capture(
        context: Context,
        fgsStarted: Boolean,
        pushPriority: String? = null,
        pushOriginalPriority: String? = null,
        latencyMs: Long? = null,
    ): SosDeliveredRequest {
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) }.getOrDefault(0)
        val volume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrDefault(0)
        return SosDeliveredRequest(
            priority = pushPriority,
            originalPriority = pushOriginalPriority,
            latencyMs = latencyMs?.coerceIn(0, 86_400_000),
            alarmMuted = runCatching { audio.isStreamMute(AudioManager.STREAM_ALARM) }.getOrNull(),
            alarmVolume = if (max > 0) (volume * 100 / max) else null,
            ringerMode = when (runCatching { audio.ringerMode }.getOrNull()) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                else -> null
            },
            interruptionFilter = when (runCatching { nm.currentInterruptionFilter }.getOrNull()) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
                else -> null
            },
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            canUseFullScreenIntent = if (Build.VERSION.SDK_INT >= 34) runCatching { nm.canUseFullScreenIntent() }.getOrNull() else true,
            fgsStarted = fgsStarted,
        )
    }

    /**
     * Whether Do Not Disturb currently blocks alarms (priority mode without the alarms category).
     * The alarm stream itself is muted then, so only a DND-bypassing ringtone notification can sound.
     * Uses the consolidated policy: with targetSdk 35+ getNotificationPolicy() returns the app's own rule.
     */
    fun dndBlocksAlarms(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_PRIORITY) return false
        if (Build.VERSION.SDK_INT < 28) return false
        val policy = if (Build.VERSION.SDK_INT >= 30) nm.consolidatedNotificationPolicy else nm.notificationPolicy
        return (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0
    }
}
