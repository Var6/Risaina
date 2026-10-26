package com.risaina.app.core.safety

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.risaina.app.core.notifications.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The phone settings that decide whether an SOS can ring here. Read by [SosReadinessProbe]. */
data class DeviceSafetyState(
    val sdk: Int,
    val notificationsEnabled: Boolean,
    val sosChannelBlocked: Boolean,
    val canUseFullScreenIntent: Boolean,
    val dndAccess: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    /** OEM silent mode also mutes the alarm stream (MODE_RINGER_STREAMS_AFFECTED has the alarm bit). */
    val silentModeMutesAlarms: Boolean,
    /** Alarm volume in percent, or null when unknown. */
    val alarmVolumePct: Int?,
    val fullVolumeOn: Boolean,
    val exactAlarms: Boolean,
    val manufacturer: String,
)

enum class CheckStatus { OK, ACTION, INFO }

/** Where a "fix" button sends the user. */
enum class ReadinessFix { APP_NOTIFICATIONS, SOS_CHANNEL, FULL_SCREEN, DND_ACCESS, BATTERY, APP_INFO, EXACT_ALARMS }

data class ReadinessCheck(
    val key: String,
    val status: CheckStatus,
    val title: String,
    val detail: String? = null,
    val fix: ReadinessFix? = null,
    val fixLabel: String? = null,
)

/**
 * "Can an SOS reach you?": one honest line per setting, with a button to the exact system screen
 * that fixes it. Pure (unit-tested). Some things cannot be checked or fixed by any app (Total
 * silence, an OEM auto-launch switch, silent mode muting alarms): those are explained, not hidden.
 */
object SosReadiness {
    private val OEM_AUTOSTART = setOf("realme", "oppo", "oneplus", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "samsung", "asus", "tecno", "infinix")

    fun evaluate(s: DeviceSafetyState): List<ReadinessCheck> = buildList {
        add(
            if (s.notificationsEnabled) ReadinessCheck("notifications", CheckStatus.OK, "Notifications are on")
            else ReadinessCheck(
                "notifications", CheckStatus.ACTION, "Notifications are off for Risaina",
                "An SOS still rings, but the alarm screen can't pop up and you won't see who sent it.",
                ReadinessFix.APP_NOTIFICATIONS, "Turn on",
            ),
        )
        if (s.notificationsEnabled && s.sosChannelBlocked) add(
            ReadinessCheck(
                "sos_channel", CheckStatus.ACTION, "SOS notifications are blocked",
                "The alarm still rings, but its screen can't appear.", ReadinessFix.SOS_CHANNEL, "Open",
            ),
        )
        if (s.sdk >= 34) add(
            if (s.canUseFullScreenIntent) ReadinessCheck("full_screen", CheckStatus.OK, "The alarm screen can open over the lock screen")
            else ReadinessCheck(
                "full_screen", CheckStatus.ACTION, "The alarm screen can't open over the lock screen",
                "Allow full-screen alerts so an SOS opens like an alarm clock.", ReadinessFix.FULL_SCREEN, "Allow",
            ),
        )
        add(
            if (s.ignoringBatteryOptimizations) ReadinessCheck("battery", CheckStatus.OK, "Battery use is unrestricted")
            else ReadinessCheck(
                "battery", CheckStatus.ACTION, "Battery saving can delay an SOS",
                "Let Risaina run without battery limits so an SOS reaches you straight away. In the list, switch to All apps, pick Risaina and choose Don't optimise.", ReadinessFix.BATTERY, "Open",
            ),
        )
        if (s.backgroundRestricted) add(
            ReadinessCheck(
                "background", CheckStatus.ACTION, "Background use is restricted",
                "Android may stop Risaina from receiving an SOS. Remove the restriction in App info.", ReadinessFix.APP_INFO, "Open",
            ),
        )
        add(
            if (s.dndAccess) ReadinessCheck("dnd", CheckStatus.OK, "Can ring through Do Not Disturb")
            else ReadinessCheck(
                "dnd", CheckStatus.INFO, "Do Not Disturb may block an SOS",
                "Let Risaina override Do Not Disturb so an SOS rings even when alarms are blocked. Total silence still mutes everything.",
                ReadinessFix.DND_ACCESS, "Allow",
            ),
        )
        if (s.silentModeMutesAlarms) add(
            ReadinessCheck(
                "silent_mutes_alarms", CheckStatus.INFO, "On this phone, silent mode also mutes alarms",
                "In silent mode an SOS can only vibrate. Use vibrate mode instead of silent if you want to hear it.",
            ),
        )
        if (!s.fullVolumeOn && s.alarmVolumePct == 0) add(
            ReadinessCheck(
                "volume", CheckStatus.ACTION, "Your alarm volume is at zero",
                "Turn on \"Ring SOS at full volume\" below, or raise the alarm volume.",
            ),
        )
        // Off by default on Android 14+ for an app installed from Google Play, so it is a fix, not a tip.
        if (!s.exactAlarms && s.sdk >= 31) add(
            ReadinessCheck(
                "exact_alarms", CheckStatus.ACTION, "The backup start is off",
                "If Android blocks the alarm from starting, Risaina tries again through an alarm clock. Allow alarms and reminders.",
                ReadinessFix.EXACT_ALARMS, "Allow",
            ),
        )
        val brand = s.manufacturer.lowercase()
        if (brand in OEM_AUTOSTART) add(
            ReadinessCheck(
                "autostart", CheckStatus.INFO, "Check auto launch by hand",
                "On ${s.manufacturer.replaceFirstChar { it.uppercase() }} phones, turn on Auto launch and Allow background activity for Risaina, and lock it in Recents. Android can't tell us whether this is on.",
                ReadinessFix.APP_INFO, "App info",
            ),
        )
    }

    /** Short summary for the card header. */
    fun summary(checks: List<ReadinessCheck>): String {
        val todo = checks.count { it.status == CheckStatus.ACTION }
        return when (todo) {
            0 -> "Ready: an SOS can reach this phone"
            1 -> "1 thing to fix"
            else -> "$todo things to fix"
        }
    }
}

/** Reads [DeviceSafetyState] from the phone and opens the matching system screen for each fix. */
@Singleton
class SosReadinessProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SafetySettings,
) {
    fun read(): DeviceSafetyState {
        val nm = context.getSystemService(NotificationManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) }.getOrDefault(0)
        val volume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrNull()
        return DeviceSafetyState(
            sdk = Build.VERSION.SDK_INT,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            sosChannelBlocked = runCatching { nm.getNotificationChannel(NotificationChannels.SOS_ALARM)?.importance == NotificationManager.IMPORTANCE_NONE }.getOrDefault(false),
            canUseFullScreenIntent = if (Build.VERSION.SDK_INT >= 34) runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true) else true,
            dndAccess = runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false),
            ignoringBatteryOptimizations = runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false),
            backgroundRestricted = Build.VERSION.SDK_INT >= 28 && runCatching { context.getSystemService(ActivityManager::class.java).isBackgroundRestricted }.getOrDefault(false),
            silentModeMutesAlarms = runCatching {
                (Settings.System.getInt(context.contentResolver, Settings.System.MODE_RINGER_STREAMS_AFFECTED) and (1 shl AudioManager.STREAM_ALARM)) != 0
            }.getOrDefault(false),
            alarmVolumePct = if (max > 0 && volume != null) volume * 100 / max else null,
            fullVolumeOn = settings.current.fullVolume,
            exactAlarms = Build.VERSION.SDK_INT < 31 || runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(true),
            manufacturer = Build.MANUFACTURER.orEmpty(),
        )
    }

    /** The system screen for a fix, falling back to App info where a screen does not exist. */
    fun intentFor(fix: ReadinessFix): Intent {
        val pkg = context.packageName
        val packageUri = Uri.parse("package:$pkg")
        val intent = when (fix) {
            ReadinessFix.APP_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            ReadinessFix.SOS_CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg).putExtra(Settings.EXTRA_CHANNEL_ID, NotificationChannels.SOS_ALARM)
            ReadinessFix.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri) else appInfo()
            ReadinessFix.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            // Google Play does not allow asking for the exemption directly: the list of apps instead.
            ReadinessFix.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            ReadinessFix.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri) else appInfo()
            ReadinessFix.APP_INFO -> appInfo()
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun appInfo(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the screen for a fix; if this phone does not have it, App info instead. */
    fun open(fix: ReadinessFix) {
        try {
            context.startActivity(intentFor(fix))
        } catch (_: Exception) {
            runCatching { context.startActivity(appInfo()) }
        }
    }
}
