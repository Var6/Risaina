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
import com.risaina.app.core.network.PushAckRequest
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
)

enum class HealthStatus { OK, WARNING, PROBLEM }

/** What a check's button does. The UI maps each to an Intent (OemBackgroundSettings). */
enum class HealthAction { NOTIFICATION_SETTINGS, BATTERY_EXEMPTION, APP_INFO, OEM_BACKGROUND, DATA_SAVER }

data class HealthCheck(val id: String, val status: HealthStatus, val title: String, val detail: String, val action: HealthAction? = null, val actionLabel: String? = null)

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

    fun checks(s: DeviceDeliveryState): List<HealthCheck> = buildList {
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
        if (aggressiveOem(s.manufacturer)) {
            add(HealthCheck("oem", HealthStatus.WARNING, "Check auto launch on this phone", "${brand(s.manufacturer)} phones stop apps in the background unless you allow it. Allow auto launch and background activity for Risaina.", HealthAction.OEM_BACKGROUND, "Open settings"))
        }
    }

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
        )
    }

    private companion object { const val PLAY_SERVICES = "com.google.android.gms" }
}

/** Talks to the server about delivery: push acks, the test push, and both phones' health. */
@Singleton
class DeliveryHealthReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: RisainaApi,
    private val sessionStore: SessionStore,
    private val stateReader: DeviceStateReader,
) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Tells the server this phone received a push, so either partner can see when it last did. At
     * most every few minutes (an extra request per push would cost battery for little), but always
     * for a downgraded or test push, which is exactly what the health view needs to show.
     */
    suspend fun ackPush(priority: String, originalPriority: String, force: Boolean) {
        val now = System.currentTimeMillis()
        val downgraded = originalPriority == DeliverySnapshot.PRIORITY_HIGH && priority == DeliverySnapshot.PRIORITY_NORMAL
        if (!force && !downgraded && now - prefs.getLong(K_LAST_ACK, 0L) in 0 until ACK_INTERVAL_MS) return
        val session = sessionStore.current() ?: return
        if (priority == DeliverySnapshot.PRIORITY_UNKNOWN || originalPriority == DeliverySnapshot.PRIORITY_UNKNOWN) return
        val flags = DeliveryHealthRules.flags(stateReader.read())
        val r = apiCall { api.pushAck(session.deviceId, PushAckRequest(priority, originalPriority, flags)) }
        if (r is AppResult.Success) prefs.edit().putLong(K_LAST_ACK, now).apply()
    }

    suspend fun sendTestPush(delaySeconds: Int): AppResult<TestPushResponse> {
        val deviceId = sessionStore.current()?.deviceId?.takeIf { it.isNotBlank() }
        return apiCall { api.testPush(TestPushRequest(deviceId = deviceId, delaySeconds = delaySeconds)) }
    }

    suspend fun health(): AppResult<DeviceHealthResponse> = apiCall { api.deviceHealth() }

    private companion object {
        const val PREFS = "delivery_health"
        const val K_LAST_ACK = "last_ack_at"
        const val ACK_INTERVAL_MS = 10 * 60_000L
    }
}
