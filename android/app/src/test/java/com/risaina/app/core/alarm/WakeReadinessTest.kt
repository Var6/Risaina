package com.risaina.app.core.alarm

import com.risaina.app.core.safety.CheckStatus
import com.risaina.app.core.safety.ReadinessFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What this phone tells the partner, and the checks with their fixes on the Wake screen. */
class WakeReadinessTest {
    private val ok = WakeDeviceState(
        sdk = 35, notificationsEnabled = true, channelBlocked = false, exactAlarms = true, canUseFullScreenIntent = true,
        alarmVolumePct = 70, silentModeMutesAlarms = false, dndBlocksAlarms = false, manufacturer = "Google",
    )

    @Test fun `ready when it can ring on time`() {
        assertEquals(WakeReadyReport(true, null), WakeReadiness.report(ok))
        assertTrue(WakeReadiness.evaluate(ok).none { it.status == CheckStatus.ACTION })
    }

    @Test fun `each missing condition gives its reason`() {
        assertEquals(WakeReadyReport(false, "notifications_off"), WakeReadiness.report(ok.copy(notificationsEnabled = false, exactAlarms = false)))
        assertEquals(WakeReadyReport(false, "channel_blocked"), WakeReadiness.report(ok.copy(channelBlocked = true)))
        assertEquals(WakeReadyReport(false, "exact_alarms_off"), WakeReadiness.report(ok.copy(exactAlarms = false)))
        // A banner still rings: ready, with the reason noted.
        assertEquals(WakeReadyReport(true, "full_screen_off"), WakeReadiness.report(ok.copy(canUseFullScreenIntent = false)))
        // Full-screen alerts need no permission before Android 14.
        assertEquals(WakeReadyReport(true, null), WakeReadiness.report(ok.copy(sdk = 33, canUseFullScreenIntent = false)))
    }

    @Test fun `every reason is a short snake case code the server accepts`() {
        val codes = listOf(WakeReadiness.NOTIFICATIONS_OFF, WakeReadiness.CHANNEL_BLOCKED, WakeReadiness.EXACT_ALARMS_OFF, WakeReadiness.FULL_SCREEN_OFF, WakeReadiness.UPDATE_NEEDED)
        for (c in codes) assertTrue(c, Regex("^[a-z][a-z0-9_]{0,31}$").matches(c))
    }

    @Test fun `the checks link to the fixes the SOS card already has`() {
        val checks = WakeReadiness.evaluate(ok.copy(notificationsEnabled = false, exactAlarms = false, canUseFullScreenIntent = false))
        assertEquals(ReadinessFix.APP_NOTIFICATIONS, checks.first { it.key == "notifications" }.fix)
        assertEquals(ReadinessFix.EXACT_ALARMS, checks.first { it.key == "exact_alarms" }.fix)
        assertEquals(ReadinessFix.FULL_SCREEN, checks.first { it.key == "full_screen" }.fix)
        // A blocked channel is only checked once notifications are on.
        assertFalse(checks.any { it.key == "wake_channel" })
        assertEquals(ReadinessFix.APP_NOTIFICATIONS, WakeReadiness.evaluate(ok.copy(channelBlocked = true)).first { it.key == "wake_channel" }.fix)
    }

    @Test fun `what no app can fix is explained`() {
        val checks = WakeReadiness.evaluate(ok.copy(alarmVolumePct = 0, silentModeMutesAlarms = true, dndBlocksAlarms = true, manufacturer = "OPPO"))
        assertEquals(CheckStatus.ACTION, checks.first { it.key == "volume" }.status)
        assertEquals(CheckStatus.INFO, checks.first { it.key == "silent_mutes_alarms" }.status)
        assertEquals(CheckStatus.INFO, checks.first { it.key == "dnd" }.status)
        assertEquals(ReadinessFix.APP_INFO, checks.first { it.key == "autostart" }.fix)
        assertTrue(checks.any { it.key == "reboot" })
        // Exact alarms are not a question before Android 12.
        assertFalse(WakeReadiness.evaluate(ok.copy(sdk = 30)).any { it.key == "exact_alarms" })
    }
}
