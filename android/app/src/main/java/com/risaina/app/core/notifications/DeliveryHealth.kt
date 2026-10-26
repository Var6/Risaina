package com.risaina.app.core.notifications

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.network.DeviceHealthResponse
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.TestPushRequest
import com.risaina.app.core.network.TestPushResponse
import com.risaina.app.core.network.apiCall
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Everything about this phone that decides whether pushes, notifications and widgets arrive on time. */
data class DeviceDeliveryState(
    val pushConfigured: Boolean = true,
    val notificationPermission: Boolean = true,
    val notificationsEnabled: Boolean = true,
    /** Channel id → importance, for the channels that matter (pings, messages, SOS). Absent = not created yet. */
    val channelImportance: Map<String, Int> = emptyMap(),
    val ignoringBatteryOptimizations: Boolean = true,
    /** "Restricted" battery use (Android 9+), set by the person or the system. */
    val backgroundRestricted: Boolean = false,
    /** UsageStatsManager bucket (Android 9+): 10 active … 40 rare, 45 restricted. Null when unknown. */
    val standbyBucket: Int? = null,
    /** Data Saver is on and Risaina is not exempt: no background network at all. */
    val dataSaverBlocksBackground: Boolean = false,
    val playServicesAvailable: Boolean = true,
    val manufacturer: String = "",
    /** The last time Android or the person stopped Risaina the hard way (Android 11+), if recent. */
    val lastKill: AppKill? = null,
    /** Android 15+: Risaina was force-stopped before its last start (no pushes while stopped). */
    val forceStoppedBeforeLastStart: Boolean = false,
    /**
     * When the person said they allowed auto launch and background activity (the app cannot read
     * those OEM switches). Null = not confirmed.
     */
    val oemStepsConfirmedAt: Long? = null,
)

/** How Risaina's process last ended, when it was not a normal exit. [kind]: swiped, stopped, frozen, other. */
data class AppKill(val kind: String, val atMillis: Long, val description: String? = null)

enum class HealthStatus { OK, WARNING, PROBLEM }

/** What a check's button does. The UI maps each to an Intent (OemBackgroundSettings). */
enum class HealthAction { NOTIFICATION_SETTINGS, BATTERY_EXEMPTION, APP_INFO, OEM_BACKGROUND, OEM_AUTO_LAUNCH, DATA_SAVER }

data class HealthCheck(
    val id: String,
    val status: HealthStatus,
    val title: String,
    val detail: String,
    val action: HealthAction? = null,
    val actionLabel: String? = null,
    /** A second route to the same fix, e.g. the phone maker's auto-launch list next to App info. */
    val secondaryAction: HealthAction? = null,
    val secondaryLabel: String? = null,
)

/**
 * Turns the phone's state into checks and short flags. Pure, so it is unit-tested
 * (DeliveryHealthRulesTest). Copy is short and plain; each problem says how to fix it.
 */
object DeliveryHealthRules {
    const val BUCKET_RARE = 40
    const val BUCKET_RESTRICTED = 45
    private const val IMPORTANCE_NONE = 0 // NotificationManager.IMPORTANCE_NONE

    /** Manufacturers known to stop background apps unless the person allows it. */
    fun aggressiveOem(manufacturer: String): Boolean =
        manufacturer.lowercase() in setOf("realme", "oppo", "oneplus", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "samsung", "meizu", "asus", "tecno", "infinix")

    /** How long a kill stays worth mentioning. */
    const val RECENT_KILL_MS = 3L * 24 * 3600 * 1000

    fun checks(s: DeviceDeliveryState, now: Long = System.currentTimeMillis()): List<HealthCheck> = buildList {
        if (!s.pushConfigured) add(HealthCheck("push", HealthStatus.PROBLEM, "Push isn't set up in this build", "Nothing can reach this phone while Risaina is closed."))
        if (!s.playServicesAvailable) add(HealthCheck("play", HealthStatus.PROBLEM, "Google Play services missing", "Risaina's alerts travel through Google Play services, which this phone doesn't have."))

        val notificationsOn = s.notificationPermission && s.notificationsEnabled
        add(
            if (notificationsOn) HealthCheck("notifications", HealthStatus.OK, "Notifications allowed", "Risaina can alert you.")
            else HealthCheck("notifications", HealthStatus.PROBLEM, "Notifications are off", "You won't see pings, messages or an SOS.", HealthAction.NOTIFICATION_SETTINGS, "Turn on"),
        )
        val blocked = s.channelImportance.filterValues { it == IMPORTANCE_NONE }.keys
        if (notificationsOn && blocked.isNotEmpty()) {
            val what = blocked.map { channelName(it) }.distinct().joinToString(" and ")
            add(HealthCheck("channels", HealthStatus.PROBLEM, "$what turned off", "Turn them back on in Risaina's notification settings.", HealthAction.NOTIFICATION_SETTINGS, "Open settings"))
        }

        add(
            if (s.ignoringBatteryOptimizations) HealthCheck("battery", HealthStatus.OK, "Battery: unrestricted", "Android lets Risaina wake up for your partner.")
            else HealthCheck("battery", HealthStatus.WARNING, "Battery optimisation is on", "Android may hold alerts and widget updates until you open the app.", HealthAction.BATTERY_EXEMPTION, "Allow"),
        )
        if (s.backgroundRestricted) {
            add(HealthCheck("restricted", HealthStatus.PROBLEM, "Background use is restricted", "Risaina can't wake up when your partner reaches out. Set battery use to Unrestricted.", HealthAction.APP_INFO, "Open App info"))
        }
        when {
            (s.standbyBucket ?: 0) >= BUCKET_RESTRICTED ->
                add(HealthCheck("standby", HealthStatus.PROBLEM, "Android put Risaina to sleep", "It is in the restricted standby group, so alerts wait. Allow background activity.", HealthAction.OEM_BACKGROUND, "Fix"))
            (s.standbyBucket ?: 0) >= BUCKET_RARE ->
                add(HealthCheck("standby", HealthStatus.WARNING, "Android rarely wakes Risaina", "Opening the app now and then helps, and so does allowing background activity.", HealthAction.OEM_BACKGROUND, "Fix"))
        }
        if (s.dataSaverBlocksBackground) {
            add(HealthCheck("datasaver", HealthStatus.WARNING, "Data Saver blocks background data", "Updates wait until you open the app. Allow Risaina unrestricted data.", HealthAction.DATA_SAVER, "Allow"))
        }
        if (s.forceStoppedBeforeLastStart) {
            add(HealthCheck("forcestop", HealthStatus.WARNING, "Risaina was force-stopped", "While an app is stopped, no push can reach it. Swiping it away can do this on some phones. Allow auto launch.", HealthAction.OEM_BACKGROUND, "Fix"))
        }
        val kill = s.lastKill?.takeIf { now - it.atMillis in 0 until RECENT_KILL_MS }
        if (kill != null) {
            val how = when (kill.kind) {
                "swiped" -> "was swiped away"
                "frozen" -> "was frozen by the system"
                "stopped" -> "was stopped"
                else -> "was closed by the system"
            }
            add(HealthCheck("killed", HealthStatus.WARNING, "Risaina $how recently", "Pushes can wait until it's allowed to run again. Lock it in Recents and allow background activity.", HealthAction.OEM_BACKGROUND, "Fix"))
        }
        if (aggressiveOem(s.manufacturer)) add(oemCheck(s, kill))
    }

    /**
     * The phone maker's own background switches, which no API can read. It stays a warning until the
     * person says they turned them on, so the card can reach "All good" on these phones too. A later
     * kill (swipe, freeze, stop) shows the switches did not hold, or were reset by an OS update: the
     * warning comes back.
     */
    private fun oemCheck(s: DeviceDeliveryState, recentKill: AppKill?): HealthCheck {
        val confirmedAt = s.oemStepsConfirmedAt
        val undone = confirmedAt != null && recentKill != null && recentKill.atMillis > confirmedAt
        if (confirmedAt != null && !undone) {
            return HealthCheck("oem", HealthStatus.OK, "Auto launch allowed", "You allowed auto launch and background activity.", HealthAction.OEM_BACKGROUND, "Check")
        }
        val detail = if (undone) {
            "Risaina was stopped after you allowed it, so a setting may have been reset. Check auto launch and background activity again."
        } else {
            "${brand(s.manufacturer)} phones stop apps in the background unless you allow it. Allow auto launch and background activity for Risaina."
        }
        // ColorOS: App info (Battery usage) is the reliable route on current versions; the older
        // startup manager screens are offered next to it, for versions that only have them there.
        return if (colorOs(s.manufacturer)) {
            HealthCheck("oem", HealthStatus.WARNING, "Check auto launch on this phone", detail, HealthAction.OEM_BACKGROUND, "Open App info", HealthAction.OEM_AUTO_LAUNCH, "Auto launch list")
        } else {
            HealthCheck("oem", HealthStatus.WARNING, "Check auto launch on this phone", detail, HealthAction.OEM_BACKGROUND, "Open settings")
        }
    }

    /** True for realme, OPPO and OnePlus (ColorOS), where App info → Battery usage is the reliable route. */
    fun colorOs(manufacturer: String): Boolean = manufacturer.lowercase() in setOf("realme", "oppo", "oneplus")

    /** Short codes sent with push acks, so the partner's phone can say why alerts may be late here. */
    fun flags(s: DeviceDeliveryState): List<String> = buildList {
        if (!s.notificationPermission || !s.notificationsEnabled) add("notifications_off")
        if (s.channelImportance.values.any { it == IMPORTANCE_NONE }) add("channel_off")
        if (!s.ignoringBatteryOptimizations) add("battery_optimized")
        if (s.backgroundRestricted) add("background_restricted")
        when {
            (s.standbyBucket ?: 0) >= BUCKET_RESTRICTED -> add("standby_restricted")
            (s.standbyBucket ?: 0) >= BUCKET_RARE -> add("standby_rare")
        }
        if (s.dataSaverBlocksBackground) add("data_saver")
        if (!s.playServicesAvailable) add("no_play_services")
        if (s.forceStoppedBeforeLastStart) add("force_stopped")
    }

    /** One line for the top of the card. */
    fun summary(checks: List<HealthCheck>): String {
        val problems = checks.count { it.status == HealthStatus.PROBLEM }
        val warnings = checks.count { it.status == HealthStatus.WARNING }
        return when {
            problems > 0 -> if (problems == 1) "1 thing is stopping alerts" else "$problems things are stopping alerts"
            warnings > 0 -> if (warnings == 1) "1 thing may delay alerts" else "$warnings things may delay alerts"
            else -> "All good: alerts and widgets should arrive right away"
        }
    }

    private fun channelName(id: String): String = when {
        id.startsWith("sos") -> "SOS alerts"
        id.startsWith("pings") -> "Touch Pings"
        id.startsWith("messages") -> "Messages"
        else -> "Some alerts"
    }

    fun brand(manufacturer: String): String = when (manufacturer.lowercase()) {
        "realme" -> "Realme"; "oppo" -> "OPPO"; "oneplus" -> "OnePlus"; "xiaomi", "redmi", "poco" -> "Xiaomi"
        "vivo", "iqoo" -> "Vivo"; "huawei" -> "Huawei"; "honor" -> "Honor"; "samsung" -> "Samsung"
        else -> manufacturer.replaceFirstChar { it.uppercase() }.ifBlank { "Some" }
    }
}

/** Reads [DeviceDeliveryState] from the system. Cheap; safe on any thread. */
@Singleton
class DeviceStateReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pushAvailability: PushAvailability,
    private val diagnostics: DeliveryDiagnostics,
) {
    fun read(): DeviceDeliveryState {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channels = runCatching {
            nm.notificationChannels
                .filter { it.id == NotificationChannels.PINGS || it.id == NotificationChannels.MESSAGES || it.id.startsWith("sos") }
                .associate { it.id to it.importance }
        }.getOrDefault(emptyMap())
        return DeviceDeliveryState(
            pushConfigured = runCatching { pushAvailability.configured }.getOrDefault(false),
            notificationPermission = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelImportance = channels,
            ignoringBatteryOptimizations = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
            backgroundRestricted = Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true,
            standbyBucket = if (Build.VERSION.SDK_INT >= 28) runCatching { context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket }.getOrNull() else null,
            dataSaverBlocksBackground = runCatching {
                context.getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            }.getOrDefault(false),
            playServicesAvailable = runCatching { context.packageManager.getApplicationInfo(PLAY_SERVICES, 0).enabled }.getOrDefault(false),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            lastKill = if (Build.VERSION.SDK_INT >= 30) lastKill() else null,
            forceStoppedBeforeLastStart = Build.VERSION.SDK_INT >= 35 && wasForceStopped(),
            oemStepsConfirmedAt = diagnostics.oemStepsConfirmedAt(),
        )
    }

    /**
     * Swipe-kills, force-stops and freezes, after the fact (Android 11+). ColorOS often reports its
     * own cleanups as "other" with a description, which is shown as is.
     */
    // REASON_FREEZER is an Android 13 constant, inlined at compile time; older versions never report it.
    @android.annotation.SuppressLint("InlinedApi")
    @androidx.annotation.RequiresApi(30)
    private fun lastKill(): AppKill? = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        am.getHistoricalProcessExitReasons(context.packageName, 0, 10).firstNotNullOfOrNull { e ->
            val kind = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "swiped"
                android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "stopped"
                android.app.ApplicationExitInfo.REASON_FREEZER -> "frozen"
                android.app.ApplicationExitInfo.REASON_OTHER -> "other"
                else -> null
            }
            kind?.let { AppKill(it, e.timestamp, e.description) }
        }
    }.getOrNull()

    @androidx.annotation.RequiresApi(35)
    private fun wasForceStopped(): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessStartReasons(1)?.firstOrNull()?.wasForceStopped() == true
    }.getOrDefault(false)

    private companion object {
        const val PLAY_SERVICES = "com.google.android.gms"
    }
}

/**
 * Talks to the server about delivery: the test push and both phones' health. (Push acks ride on the
 * sync's GET /events instead; see EventSyncManager.)
 */
@Singleton
class DeliveryHealthReporter @Inject constructor(
    private val api: RisainaApi,
    private val sessionStore: SessionStore,
) {
    suspend fun sendTestPush(delaySeconds: Int): AppResult<TestPushResponse> {
        val deviceId = sessionStore.current()?.deviceId?.takeIf { it.isNotBlank() }
        return apiCall { api.testPush(TestPushRequest(deviceId = deviceId, delaySeconds = delaySeconds)) }
    }

    suspend fun health(): AppResult<DeviceHealthResponse> = apiCall { api.deviceHealth() }
}
