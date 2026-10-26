package com.risaina.app.core.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosReadinessTest {
    private val ready = DeviceSafetyState(
        sdk = 35, notificationsEnabled = true, sosChannelBlocked = false, canUseFullScreenIntent = true, dndAccess = true,
        ignoringBatteryOptimizations = true, backgroundRestricted = false, silentModeMutesAlarms = false,
        alarmVolumePct = 60, fullVolumeOn = true, exactAlarms = true, manufacturer = "Google",
    )

    private fun keys(s: DeviceSafetyState, status: CheckStatus) = SosReadiness.evaluate(s).filter { it.status == status }.map { it.key }

    @Test fun `a ready phone has nothing to fix`() {
        assertEquals(emptyList<String>(), keys(ready, CheckStatus.ACTION))
        assertEquals("Ready: an SOS can reach this phone", SosReadiness.summary(SosReadiness.evaluate(ready)))
    }

    @Test fun `each problem has its own fix`() {
        val bad = ready.copy(notificationsEnabled = false, canUseFullScreenIntent = false, ignoringBatteryOptimizations = false, backgroundRestricted = true)
        val checks = SosReadiness.evaluate(bad).associateBy { it.key }
        assertEquals(ReadinessFix.APP_NOTIFICATIONS, checks.getValue("notifications").fix)
        assertEquals(ReadinessFix.FULL_SCREEN, checks.getValue("full_screen").fix)
        assertEquals(ReadinessFix.BATTERY, checks.getValue("battery").fix)
        assertEquals(ReadinessFix.APP_INFO, checks.getValue("background").fix)
        assertEquals("4 things to fix", SosReadiness.summary(checks.values.toList()))
    }

    @Test fun `alarms and reminders off is a fix on Android 12 and later`() {
        val c = SosReadiness.evaluate(ready.copy(exactAlarms = false)).single { it.key == "exact_alarms" }
        assertEquals(CheckStatus.ACTION, c.status)
        assertEquals(ReadinessFix.EXACT_ALARMS, c.fix)
        assertFalse(SosReadiness.evaluate(ready.copy(sdk = 30, exactAlarms = false)).any { it.key == "exact_alarms" })
    }

    @Test fun `a blocked SOS channel only matters while notifications are on`() {
        assertTrue("sos_channel" in keys(ready.copy(sosChannelBlocked = true), CheckStatus.ACTION))
        assertFalse("sos_channel" in keys(ready.copy(sosChannelBlocked = true, notificationsEnabled = false), CheckStatus.ACTION))
    }

    @Test fun `full-screen permission is checked only on Android 14 and later`() {
        assertFalse(SosReadiness.evaluate(ready.copy(sdk = 33, canUseFullScreenIntent = false)).any { it.key == "full_screen" })
    }

    @Test fun `silent mode muting alarms is explained honestly, with no fake fix`() {
        val c = SosReadiness.evaluate(ready.copy(silentModeMutesAlarms = true)).single { it.key == "silent_mutes_alarms" }
        assertEquals(CheckStatus.INFO, c.status)
        assertEquals(null, c.fix)
        assertTrue(c.detail!!.contains("only vibrate"))
    }

    @Test fun `zero alarm volume matters only when full volume is off`() {
        assertFalse(SosReadiness.evaluate(ready.copy(alarmVolumePct = 0)).any { it.key == "volume" })
        assertTrue("volume" in keys(ready.copy(alarmVolumePct = 0, fullVolumeOn = false), CheckStatus.ACTION))
    }

    @Test fun `realme gets the auto launch reminder, a Pixel does not`() {
        assertTrue(SosReadiness.evaluate(ready.copy(manufacturer = "realme")).any { it.key == "autostart" })
        assertFalse(SosReadiness.evaluate(ready).any { it.key == "autostart" })
    }

    @Test fun `no Do Not Disturb access is a suggestion, not a failure`() {
        val c = SosReadiness.evaluate(ready.copy(dndAccess = false)).single { it.key == "dnd" }
        assertEquals(CheckStatus.INFO, c.status)
        assertEquals(ReadinessFix.DND_ACCESS, c.fix)
    }
}
