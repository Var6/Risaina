package com.risaina.app.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryHealthRulesTest {
    private val healthy = DeviceDeliveryState(
        pushConfigured = true, notificationPermission = true, notificationsEnabled = true,
        channelImportance = mapOf("pings_v2" to 4, "messages" to 3, "sos" to 4),
        ignoringBatteryOptimizations = true, backgroundRestricted = false, standbyBucket = 10,
        dataSaverBlocksBackground = false, playServicesAvailable = true, manufacturer = "Google",
    )

    private fun ids(s: DeviceDeliveryState) = DeliveryHealthRules.checks(s).filter { it.status != HealthStatus.OK }.map { it.id }

    @Test fun healthyPixelHasNothingToFix() {
        assertEquals(emptyList<String>(), ids(healthy))
        assertEquals(emptyList<String>(), DeliveryHealthRules.flags(healthy))
        assertTrue(DeliveryHealthRules.summary(DeliveryHealthRules.checks(healthy)).startsWith("All good"))
    }

    @Test fun realmeAlwaysGetsTheAutoLaunchCheck() {
        val checks = DeliveryHealthRules.checks(healthy.copy(manufacturer = "realme"))
        val oem = checks.single { it.id == "oem" }
        assertEquals(HealthStatus.WARNING, oem.status)
        assertEquals(HealthAction.OEM_BACKGROUND, oem.action)
        assertTrue(oem.detail.startsWith("Realme"))
    }

    @Test fun everyProblemHasAFixButton() {
        val broken = healthy.copy(
            notificationsEnabled = false, ignoringBatteryOptimizations = false, backgroundRestricted = true,
            standbyBucket = DeliveryHealthRules.BUCKET_RESTRICTED, dataSaverBlocksBackground = true,
        )
        val checks = DeliveryHealthRules.checks(broken).filter { it.status != HealthStatus.OK }
        assertEquals(listOf("notifications", "battery", "restricted", "standby", "datasaver"), checks.map { it.id })
        assertTrue(checks.all { it.action != null && it.actionLabel != null })
        assertEquals(
            listOf("notifications_off", "battery_optimized", "background_restricted", "standby_restricted", "data_saver"),
            DeliveryHealthRules.flags(broken),
        )
    }

    @Test fun aBlockedChannelIsNamed() {
        val c = DeliveryHealthRules.checks(healthy.copy(channelImportance = mapOf("pings_v2" to 0, "sos_dnd" to 0, "messages" to 3)))
            .single { it.id == "channels" }
        assertEquals(HealthStatus.PROBLEM, c.status)
        assertTrue(c.title, c.title.contains("Touch Pings") && c.title.contains("SOS alerts"))
        assertTrue("channel_off" in DeliveryHealthRules.flags(healthy.copy(channelImportance = mapOf("messages" to 0))))
    }

    @Test fun rareStandbyIsAWarningRestrictedIsAProblem() {
        assertEquals(HealthStatus.WARNING, DeliveryHealthRules.checks(healthy.copy(standbyBucket = 40)).single { it.id == "standby" }.status)
        assertEquals(HealthStatus.PROBLEM, DeliveryHealthRules.checks(healthy.copy(standbyBucket = 45)).single { it.id == "standby" }.status)
        assertFalse(DeliveryHealthRules.checks(healthy.copy(standbyBucket = 30)).any { it.id == "standby" })
        assertFalse(DeliveryHealthRules.checks(healthy.copy(standbyBucket = null)).any { it.id == "standby" })
    }

    @Test fun summaryCountsTheWorstKind() {
        val c = DeliveryHealthRules.checks(healthy.copy(ignoringBatteryOptimizations = false, manufacturer = "xiaomi"))
        assertEquals("2 things may delay alerts", DeliveryHealthRules.summary(c))
        val p = DeliveryHealthRules.checks(healthy.copy(notificationsEnabled = false, ignoringBatteryOptimizations = false))
        assertEquals("1 thing is stopping alerts", DeliveryHealthRules.summary(p))
    }

    @Test fun pushNotConfiguredAndNoPlayServicesAreProblems() {
        val ids = ids(healthy.copy(pushConfigured = false, playServicesAvailable = false))
        assertEquals(listOf("push", "play"), ids)
    }

    // --- diagnostics ------------------------------------------------------------------------

    @Test fun aHighPushThatArrivedNormalIsADowngrade() {
        assertTrue(DeliverySnapshot(lastPushPriority = "normal", lastPushOriginalPriority = "high").downgraded)
        assertFalse(DeliverySnapshot(lastPushPriority = "normal", lastPushOriginalPriority = "normal").downgraded)
        assertFalse(DeliverySnapshot(lastPushPriority = "high", lastPushOriginalPriority = "high").downgraded)
        assertEquals("high", DeliverySnapshot.priorityName(1))
        assertEquals("normal", DeliverySnapshot.priorityName(2))
        assertEquals("unknown", DeliverySnapshot.priorityName(0))
    }

    @Test fun latencyIsNullWhenClocksDisagree() {
        assertEquals(1_500L, DeliverySnapshot(lastPushAt = 11_500, lastPushSentAt = 10_000).lastPushLatencyMillis)
        assertNull(DeliverySnapshot(lastPushAt = 9_000, lastPushSentAt = 10_000).lastPushLatencyMillis)
        assertNull(DeliverySnapshot(lastPushAt = 9_000, lastPushSentAt = 0).lastPushLatencyMillis)
    }

    @Test fun latencyReadsNaturally() {
        assertEquals("0.8 s", RisainaNotifier.formatLatency(800))
        assertEquals("12 s", RisainaNotifier.formatLatency(12_400))
        assertEquals("3 min", RisainaNotifier.formatLatency(185_000))
    }
}
