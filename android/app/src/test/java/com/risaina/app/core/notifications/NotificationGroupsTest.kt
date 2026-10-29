package com.risaina.app.core.notifications

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every kind of partner notification carries a group of its own, so Android 15+ and ColorOS never
 * bundle them, and none lands in AndroidX's "silent" group (what setSilent(true) used to do).
 */
class NotificationGroupsTest {
    @Test fun everyKindHasItsOwnGroup() {
        assertEquals(NotificationGroups.ALL.size, NotificationGroups.ALL.toSet().size)
        assertEquals(
            setOf(
                "risaina_chat", "risaina_ping", "risaina_game", "risaina_widget", "risaina_system", "risaina_safety",
                "risaina_reminder", "risaina_calendar",
                "risaina_wake_alarm", "risaina_wake_status", "risaina_wake_missed", "risaina_wake_set", "risaina_wake_partner",
            ),
            NotificationGroups.ALL.toSet(),
        )
    }

    @Test fun noGroupIsTheSilentOneOrBlank() {
        for (g in NotificationGroups.ALL) {
            assertFalse(g, g == NotificationCompat.GROUP_KEY_SILENT)
            assertTrue(g, g.isNotBlank())
        }
    }

    @Test fun theGroupsDoNotClashWithTheOtherOwnGroups() {
        // The lock card, the live location and the SOS alarm keep their own groups too.
        val others = setOf("risaina_lock_card", com.risaina.app.core.location.LiveLocationService.LOCATION_GROUP, SosAlert.SOS_GROUP)
        assertTrue(NotificationGroups.ALL.none { it in others })
    }

    @Test fun aMutedPostKeepsItsGroupAndOnlyChangesHowItAlerts() {
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, NotificationGroups.alertBehavior(muted = true))
        assertEquals(NotificationCompat.GROUP_ALERT_ALL, NotificationGroups.alertBehavior(muted = false))
    }

    @Test fun noNotificationCallsSetSilent() {
        // setSilent(true) puts a notification without a group into "silent": the partner's "is sharing"
        // and the SOS "tap to respond" were the last ones. Only this phone's own service notifications
        // keep it: the live location (alone in its group) and "Catching up" (a worker's, never a partner's).
        val root = File("src/main/java/com/risaina/app")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val own = setOf("LiveLocationService.kt", "SyncWorker.kt")
        val offenders = root.walk().filter { it.extension == "kt" && it.name !in own && ".setSilent(" in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
