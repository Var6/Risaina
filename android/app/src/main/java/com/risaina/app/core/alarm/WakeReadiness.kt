package com.risaina.app.core.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
    /** The wake_alarm channel's importance, null when it is not there yet (it is made with HIGH). */
    val channelImportance: Int? = null,
    /** False when the person set the channel's sound to None. */
    val channelSound: Boolean = true,
) {
    /**
     * The alarm makes no sound: its sound is None, or its importance was lowered to Silent (LOW or
     * MIN), which plays nothing and cannot open over the lock screen either.
     */
    val channelSilent: Boolean
        get() = !channelSound || (channelImportance != null && channelImportance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_LOW)

    /** Default importance: it rings, but never pops up or opens full-screen (that needs HIGH). */
    val channelNoPopUp: Boolean get() = channelImportance == NotificationManager.IMPORTANCE_DEFAULT
}

/** What this phone tells the partner (POST /alarms/report). [reason] may be set while ready: a smaller problem. */
data class WakeReadyReport(val ready: Boolean, val reason: String?)

/**
 * "Can Wake together ring on this phone?" Pure (WakeReadinessTest). The ring is a notification, so
 * notifications and the alarm channel, with a sound, are required, unlike the SOS. Without exact
 * alarms it still rings, but can be minutes late, so the partner is told it is not ready. Without
 * full-screen alerts, or with the channel lowered to Default, it still rings out loud: ready, with
 * the reason noted.
 */
object WakeReadiness {
    const val NOTIFICATIONS_OFF = "notifications_off"
    const val CHANNEL_BLOCKED = "channel_blocked"
    /** The alarm channel's sound is None, or its importance is Silent: it rings without a sound. */
    const val ALARM_SILENT = "alarm_silent"
    const val EXACT_ALARMS_OFF = "exact_alarms_off"
    const val FULL_SCREEN_OFF = "full_screen_off"
    /** A mode this build cannot ring (a later server's). */
    const val UPDATE_NEEDED = "update_needed"
    /** Check keys the Wake screen fixes itself (no ReadinessFix of the SOS card fits them). */
    const val KEY_CHANNEL = "wake_channel"
    const val KEY_SOUND = "wake_sound"
    const val KEY_POP_UP = "wake_pop_up"
    const val KEY_VOLUME = "volume"

    private val OEM_AUTOSTART = setOf("realme", "oppo", "oneplus", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "samsung", "asus", "tecno", "infinix")

    fun report(s: WakeDeviceState): WakeReadyReport = when {
        !s.notificationsEnabled -> WakeReadyReport(false, NOTIFICATIONS_OFF)
        s.channelBlocked -> WakeReadyReport(false, CHANNEL_BLOCKED)
        s.channelSilent -> WakeReadyReport(false, ALARM_SILENT)
        !s.exactAlarms -> WakeReadyReport(false, EXACT_ALARMS_OFF)
        // It still rings out loud: ready, with the reason noted (as without full-screen alerts).
        s.channelNoPopUp -> WakeReadyReport(true, FULL_SCREEN_OFF)
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
                KEY_CHANNEL, CheckStatus.ACTION, "The Wake together alarm is turned off",
                "Turn on \"Wake together alarm\" in Risaina's notification settings.", ReadinessFix.APP_NOTIFICATIONS, "Open",
            ),
        )
        val channelOn = s.notificationsEnabled && !s.channelBlocked
        if (channelOn && s.channelSilent) add(
            ReadinessCheck(
                KEY_SOUND, CheckStatus.ACTION, "The Wake together alarm is silent",
                "Its sound is set to None or Silent, so it can't wake you. Pick a tone and let it make a sound.",
                ReadinessFix.APP_NOTIFICATIONS, "Open",
            ),
        )
        if (channelOn && !s.channelSilent && s.channelNoPopUp) add(
            ReadinessCheck(
                KEY_POP_UP, CheckStatus.ACTION, "The alarm won't pop up",
                "It rings, but stays in the status bar. Let it pop on screen so it opens like an alarm clock.",
                ReadinessFix.APP_NOTIFICATIONS, "Open",
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
            // No fix of the SOS card: the Wake screen shows the alarm volume slider (it never sets it).
            ReadinessCheck(KEY_VOLUME, CheckStatus.ACTION, "Your alarm volume is at zero", "Raise the alarm volume, or Wake together rings silently.", fixLabel = "Volume"),
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
        val channel = runCatching { nm.getNotificationChannel(NotificationChannels.WAKE_ALARM) }.getOrNull()
        return WakeDeviceState(
            sdk = Build.VERSION.SDK_INT,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelBlocked = channel?.importance == NotificationManager.IMPORTANCE_NONE,
            exactAlarms = canExact(context),
            canUseFullScreenIntent = if (Build.VERSION.SDK_INT >= 34) runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true) else true,
            alarmVolumePct = if (max > 0 && volume != null) volume * 100 / max else null,
            silentModeMutesAlarms = runCatching {
                (Settings.System.getInt(context.contentResolver, Settings.System.MODE_RINGER_STREAMS_AFFECTED) and (1 shl AudioManager.STREAM_ALARM)) != 0
            }.getOrDefault(false),
            dndBlocksAlarms = runCatching { SosDiagnostics.dndBlocksAlarms(context) }.getOrDefault(false),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            channelImportance = channel?.importance,
            channelSound = channel == null || channel.sound != null,
        )
    }

    /**
     * The alarm channel's own settings: its tone, vibration and the switch. On a phone without that
     * screen, Risaina's notification settings.
     */
    fun openAlarmChannel() {
        val channel = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationChannels.WAKE_ALARM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(channel) }.onFailure { runCatching { context.startActivity(app) } }
    }

    /** Shows the system's alarm volume slider. ADJUST_SAME: the volume is the person's to set, never changed here. */
    fun showAlarmVolume() {
        runCatching {
            context.getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        }
    }

    companion object {
        /** True below Android 12, and with "Alarms & reminders" or unrestricted battery use. */
        fun canExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < 31 || runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(true)
    }
}
