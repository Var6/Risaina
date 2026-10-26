package com.risaina.app.core.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sign-out, session expiry, account deletion and unlink take every partner notification away, never an SOS. */
class NotifierPurgeTest {
    @Test fun unreadChatMessagesGoWithTheChat() {
        assertTrue(RisainaNotifier.clearedOnPurge(RisainaNotifier.messageNotificationId("m1"), NotificationChannels.MESSAGES))
        assertTrue(RisainaNotifier.clearedOnPurge(RisainaNotifier.messageNotificationId("m2"), NotificationChannels.MESSAGES_QUIET))
    }

    @Test fun pingsGiftsGamesAndWidgetLinesGo() {
        assertTrue(RisainaNotifier.clearedOnPurge(RisainaNotifier.PING_NOTIFICATION_ID, NotificationChannels.PINGS))
        assertTrue(RisainaNotifier.clearedOnPurge(RisainaNotifier.PING_NOTIFICATION_ID, NotificationChannels.PINGS_QUIET))
        assertTrue(RisainaNotifier.clearedOnPurge(RisainaNotifier.GAME_NOTIFICATION_ID, NotificationChannels.MESSAGES))
        assertTrue(RisainaNotifier.clearedOnPurge(RisainaNotifier.WIDGET_NOTIFICATION_ID, RisainaNotifier.WIDGET_CHANNEL))
        assertTrue(RisainaNotifier.clearedOnPurge(RisainaNotifier.TEST_NOTIFICATION_ID, NotificationChannels.PINGS))
        // Known ids go even if the channel could not be read.
        assertTrue(RisainaNotifier.clearedOnPurge(RisainaNotifier.PING_NOTIFICATION_ID, null))
    }

    @Test fun aRingingSosStays() {
        assertFalse(RisainaNotifier.clearedOnPurge(SosAlert.ID, "sos"))
        assertFalse(RisainaNotifier.clearedOnPurge(SosAlert.ID, "sos_dnd"))
        // Even if it were ever posted on a partner channel.
        assertFalse(RisainaNotifier.clearedOnPurge(SosAlert.ID, NotificationChannels.PINGS))
    }

    @Test fun systemAndServiceNotificationsStay() {
        assertFalse(RisainaNotifier.clearedOnPurge(9001, NotificationChannels.SYSTEM))
        assertFalse(RisainaNotifier.clearedOnPurge(7272, "sync"))
        assertFalse(RisainaNotifier.clearedOnPurge(7171, "lock_card_v2"))
    }
}
