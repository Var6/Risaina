package com.risaina.app.core.notifications

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every kind of partner notification carries a group of its own, so Android 15+ and ColorOS never
 * bundle them, and none lands in AndroidX's "silent" group (what setSilent(true) used to do).
 */
class NotificationGroupsTest {
    @Test fun everyKindHasItsOwnGroup() {
        assertEquals(NotificationGroups.ALL.size, NotificationGroups.ALL.toSet().size)
        assertEquals(
            setOf("risaina_chat", "risaina_ping", "risaina_game", "risaina_widget", "risaina_system"),
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
        val others = setOf("risaina_lock_card", com.risaina.app.core.location.LiveLocationService.LOCATION_GROUP)
        assertTrue(NotificationGroups.ALL.none { it in others })
    }
}
