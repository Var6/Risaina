package com.risaina.app.core.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.risaina.app.core.notifications.NotificationChannels
import com.risaina.app.core.safety.CheckStatus
import com.risaina.app.core.safety.ReadinessCheck
import com.risaina.app.core.safety.ReadinessFix
import com.risaina.app.core.sos.SosDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The phone settings that decide whether Wake together can ring here. Read by [WakeReadinessProbe]. */
data class WakeDeviceState(
    val sdk: Int,
    val notificationsEnabled: Boolean,
    /** The wake_alarm channel is turned off. */
    val channelBlocked: Boolean,
    /** "Alarms & reminders" (or unrestricted battery use, which also allows exact alarms). */
    val exactAlarms: Boolean,
    val canUseFullScreenIntent: Boolean,
    /** Alarm volume in percent, or null when unknown. */
    val alarmVolumePct: Int?,
    val silentModeMutesAlarms: Boolean,
    val dndBlocksAlarms: Boolean,
    val manufacturer: String,
)

/** What this phone tells the partner (POST /alarms/report). [reason] may be set while ready: a smaller problem. */
data class WakeReadyReport(val ready: Boolean, val reason: String?)

/**
 * "Can Wake together ring on this phone?" Pure (WakeReadinessTest). The ring is a notification, so
 * notifications and the alarm channel are required, unlike the SOS. Without exact alarms it still
 * rings, but can be minutes late, so the partner is told it is not ready. Without full-screen alerts
 * it rings as a banner: ready, with the reason noted.
 */
object WakeReadiness {
    const val NOTIFICATIONS_OFF = "notifications_off"
    const val CHANNEL_BLOCKED = "channel_blocked"
    const val EXACT_ALARMS_OFF = "exact_alarms_off"
    const val FULL_SCREEN_OFF = "full_screen_off"
    /** A mode this build cannot ring (a later server's). */
    const val UPDATE_NEEDED = "update_needed"

    private val OEM_AUTOSTART = setOf("realme", "oppo", "oneplus", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "samsung", "asus", "tecno", "infinix")

    fun report(s: WakeDeviceState): WakeReadyReport = when {
        !s.notificationsEnabled -> WakeReadyReport(false, NOTIFICATIONS_OFF)
        s.channelBlocked -> WakeReadyReport(false, CHANNEL_BLOCKED)
        !s.exactAlarms -> WakeReadyReport(false, EXACT_ALARMS_OFF)
        s.sdk >= 34 && !s.canUseFullScreenIntent -> WakeReadyReport(true, FULL_SCREEN_OFF)
        else -> WakeReadyReport(true, null)
    }

    /** One honest line per setting, with the system screen that fixes it (the SOS card's fixes). */
    fun evaluate(s: WakeDeviceState): List<ReadinessCheck> = buildList {
        add(
            if (s.notificationsEnabled) ReadinessCheck("notifications", CheckStatus.OK, "Notifications are on")
            else ReadinessCheck(
                "notifications", CheckStatus.ACTION, "Notifications are off for Risaina",
                "Wake together rings through a notification, so it can't ring until they're on.",
                ReadinessFix.APP_NOTIFICATIONS, "Turn on",
            ),
        )
        if (s.notificationsEnabled && s.channelBlocked) add(
            ReadinessCheck(
                "wake_channel", CheckStatus.ACTION, "The Wake together alarm is turned off",
                "Turn on \"Wake together alarm\" in Risaina's notification settings.", ReadinessFix.APP_NOTIFICATIONS, "Open",
            ),
        )
        if (s.sdk >= 31) add(
            if (s.exactAlarms) ReadinessCheck("exact_alarms", CheckStatus.OK, "Alarms & reminders is allowed")
            else ReadinessCheck(
                "exact_alarms", CheckStatus.ACTION, "Alarms & reminders is off",
                "Without it the alarm can ring several minutes late.", ReadinessFix.EXACT_ALARMS, "Allow",
            ),
        )
        if (s.sdk >= 34) add(
            if (s.canUseFullScreenIntent) ReadinessCheck("full_screen", CheckStatus.OK, "The alarm can open over the lock screen")
            else ReadinessCheck(
                "full_screen", CheckStatus.ACTION, "The alarm can't open over the lock screen",
                "It still rings, as a banner. Allow full-screen alerts so it opens like an alarm clock.", ReadinessFix.FULL_SCREEN, "Allow",
            ),
        )
        if (s.alarmVolumePct == 0) add(
            ReadinessCheck("volume", CheckStatus.ACTION, "Your alarm volume is at zero", "Raise the alarm volume, or Wake together rings silently."),
        )
        if (s.silentModeMutesAlarms) add(
            ReadinessCheck(
                "silent_mutes_alarms", CheckStatus.INFO, "On this phone, silent mode also mutes alarms",
                "Use vibrate instead of silent at night if you want to hear it.",
            ),
        )
        if (s.dndBlocksAlarms) add(
            ReadinessCheck(
                "dnd", CheckStatus.INFO, "Do Not Disturb is blocking alarms right now",
                "Wake together stays quiet while it does, like any alarm. Allow alarms in Do Not Disturb.",
            ),
        )
        if (s.manufacturer.lowercase() in OEM_AUTOSTART) add(
            ReadinessCheck(
                "autostart", CheckStatus.INFO, "Keep Risaina allowed in the background",
                "On ${s.manufacturer.replaceFirstChar { it.uppercase() }} phones, turn on Auto launch for Risaina and don't swipe it away, or the phone can drop its alarms.",
                ReadinessFix.APP_INFO, "App info",
            ),
        )
        add(
            ReadinessCheck(
                "reboot", CheckStatus.INFO, "After a restart, unlock once",
                "If your phone restarts overnight and stays locked, the alarm can't ring until it's unlocked.",
            ),
        )
    }
}

/** Reads [WakeDeviceState] from the phone. */
@Singleton
class WakeReadinessProbe @Inject constructor(@ApplicationContext private val context: Context) {
    fun read(): WakeDeviceState {
        val nm = context.getSystemService(NotificationManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) }.getOrDefault(0)
        val volume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrNull()
        return WakeDeviceState(
            sdk = Build.VERSION.SDK_INT,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelBlocked = runCatching { nm.getNotificationChannel(NotificationChannels.WAKE_ALARM)?.importance == NotificationManager.IMPORTANCE_NONE }.getOrDefault(false),
            exactAlarms = canExact(context),
            canUseFullScreenIntent = if (Build.VERSION.SDK_INT >= 34) runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true) else true,
            alarmVolumePct = if (max > 0 && volume != null) volume * 100 / max else null,
            silentModeMutesAlarms = runCatching {
                (Settings.System.getInt(context.contentResolver, Settings.System.MODE_RINGER_STREAMS_AFFECTED) and (1 shl AudioManager.STREAM_ALARM)) != 0
            }.getOrDefault(false),
            dndBlocksAlarms = runCatching { SosDiagnostics.dndBlocksAlarms(context) }.getOrDefault(false),
            manufacturer = Build.MANUFACTURER.orEmpty(),
        )
    }

    companion object {
        /** True below Android 12, and with "Alarms & reminders" or unrestricted battery use. */
        fun canExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < 31 || runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(true)
    }
}
