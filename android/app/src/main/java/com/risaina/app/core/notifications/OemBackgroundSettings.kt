package com.risaina.app.core.notifications

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens the screens where a person lets Risaina run in the background. Many phones (realme, OPPO,
 * OnePlus on ColorOS, Xiaomi, Vivo, Huawei, Samsung) stop apps that were swiped away or not opened
 * for a while, and then no push reaches them. The app can never switch these on itself; it can only
 * open the right screen. Screen names differ between OS versions and may be missing or not exported,
 * so each phone gets a list of candidates tried in order, ending at App info, then Settings.
 *
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is fine for this sideloaded app. Google Play restricts it, so
 * use ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS instead if Risaina is ever listed there.
 */
object OemBackgroundSettings {
    data class Screen(val pkg: String, val cls: String, val extras: Map<String, String> = emptyMap())

    /** Known auto-launch / background-activity screens for [manufacturer], most specific first. Pure. */
    fun candidates(manufacturer: String, packageName: String, appLabel: String): List<Screen> = when (manufacturer.lowercase()) {
        "realme", "oppo", "oneplus" -> listOf(
            Screen("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            Screen("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Screen("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            Screen("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
            Screen("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            // OnePlus on OxygenOS 11 and older.
            Screen("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        "xiaomi", "redmi", "poco" -> listOf(
            Screen("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Screen("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity", mapOf("package_name" to packageName, "package_label" to appLabel)),
        )
        "vivo", "iqoo" -> listOf(
            Screen("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            Screen("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            Screen("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        )
        "huawei", "honor" -> listOf(
            Screen("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Screen("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        "samsung" -> listOf(
            Screen("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        )
        else -> emptyList()
    }

    /** Step-by-step words for the phones that need them most. Null when there is nothing specific. */
    fun steps(manufacturer: String): String? = when (manufacturer.lowercase()) {
        "realme", "oppo", "oneplus" ->
            "On this phone: open App info, then Battery usage, and turn on Allow background activity and Allow auto launch. " +
                "Lock Risaina in Recents (pull its card down or tap the lock). Under Battery, More settings, turn off Sleep standby optimisation."
        "xiaomi", "redmi", "poco" ->
            "On this phone: turn on Autostart for Risaina, and set Battery saver to No restrictions."
        "vivo", "iqoo" ->
            "On this phone: allow Risaina to run in the background (High background power usage) and turn on Auto-start."
        "samsung" ->
            "On this phone: in Battery, set Risaina to Unrestricted and make sure it isn't in Sleeping apps."
        "huawei", "honor" ->
            "On this phone: in App launch, set Risaina to Manage manually and allow Auto-launch and Run in background."
        else -> null
    }

    /**
     * The main "let Risaina run in the background" button. On realme / OPPO / OnePlus (ColorOS) this is
     * App info, where Battery usage holds Allow background activity and Allow auto launch: the
     * safecenter screens are often missing or not exported on current versions. Elsewhere the phone
     * maker's auto-start screen comes first.
     */
    fun openBackgroundSettings(context: Context): Boolean =
        if (DeliveryHealthRules.colorOs(android.os.Build.MANUFACTURER.orEmpty())) openAppInfo(context) else openAutoLaunchList(context)

    /** Opens the first OEM auto-launch / background screen that exists, else App info, else Settings. */
    fun openAutoLaunchList(context: Context): Boolean {
        val label = runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrDefault("Risaina")
        for (screen in candidates(android.os.Build.MANUFACTURER.orEmpty(), context.packageName, label)) {
            val intent = Intent().setComponent(ComponentName(screen.pkg, screen.cls))
            screen.extras.forEach { (k, v) -> intent.putExtra(k, v) }
            if (tryStart(context, intent)) return true
        }
        return openAppInfo(context)
    }

    /**
     * Asks Android to stop optimising Risaina's battery use (a system dialog with Allow / Deny). Falls
     * back to the list of optimised apps, then App info.
     */
    @SuppressLint("BatteryLife") // Sideloaded app for two people; see the class comment.
    fun requestBatteryExemption(context: Context): Boolean =
        tryStart(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) ||
            tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            openAppInfo(context)

    fun openNotificationSettings(context: Context): Boolean =
        tryStart(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) ||
            openAppInfo(context)

    fun openDataSaverSettings(context: Context): Boolean =
        tryStart(context, Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${context.packageName}"))) ||
            openAppInfo(context)

    fun openAppInfo(context: Context): Boolean =
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) ||
            tryStart(context, Intent(Settings.ACTION_SETTINGS))

    fun open(context: Context, action: HealthAction): Boolean = when (action) {
        HealthAction.NOTIFICATION_SETTINGS -> openNotificationSettings(context)
        HealthAction.BATTERY_EXEMPTION -> requestBatteryExemption(context)
        HealthAction.APP_INFO -> openAppInfo(context)
        HealthAction.OEM_BACKGROUND -> openBackgroundSettings(context)
        HealthAction.DATA_SAVER -> openDataSaverSettings(context)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false // not exported on this OS version
    } catch (_: RuntimeException) {
        false
    }
}
