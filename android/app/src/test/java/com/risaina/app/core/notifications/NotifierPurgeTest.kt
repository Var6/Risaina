package com.risaina.app.core.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sign-out, session expiry, account deletion and unlink take every partner notification away, never an SOS. */
class NotifierPurgeTest {
    @Test fun unreadChatMessagesGoWithTheChat() {
        assertTrue(RisainaNotifier.clearedOnPurge(RisainaNotifier.MESSAGES_ID, NotificationChannels.MESSAGES))
        assertTrue(RisainaNotifier.clearedOnPurge(RisainaNotifier.MESSAGES_ID, NotificationChannels.MESSAGES_QUIET))
        // Known id: it goes even if the channel could not be read.
        assertTrue(RisainaNotifier.clearedOnPurge(RisainaNotifier.MESSAGES_ID, null))
        // One per message, from an older version (the message id's hash).
        assertTrue(RisainaNotifier.clearedOnPurge("m1".hashCode(), NotificationChannels.MESSAGES))
        assertTrue(RisainaNotifier.clearedOnPurge("m2".hashCode(), NotificationChannels.MESSAGES_QUIET))
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

    @Test fun thePartnersLiveLocationAndSosNoticesGo() {
        // "Raina is sharing their live location" has no timeout for "until I turn it off", and an
        // unlink purges the session without a location.stopped event.
        val loc = com.risaina.app.core.safety.SafetyNotifier.PARTNER_LOCATION_ID
        assertTrue(RisainaNotifier.clearedOnPurge(loc, NotificationChannels.PARTNER_LOCATION))
        assertTrue(RisainaNotifier.clearedOnPurge(loc, NotificationChannels.PARTNER_LOCATION_QUIET))
        assertTrue(RisainaNotifier.clearedOnPurge(loc, null))
        assertTrue(RisainaNotifier.clearedOnPurge(SosAlert.MISSED_ID, NotificationChannels.SOS_UPDATES))
        assertTrue(RisainaNotifier.clearedOnPurge(SosAlert.SAFE_ID, NotificationChannels.SOS_UPDATES))
        assertTrue(RisainaNotifier.clearedOnPurge(SosAlert.RESPOND_ID, NotificationChannels.SOS_UPDATES))
        // This person's own "couldn't be sent, please call" advice stays.
        assertFalse(RisainaNotifier.clearedOnPurge(SosAlert.NOT_SENT_ID, NotificationChannels.SOS_UPDATES))
        // So does their own "You're sharing your location" service notification.
        assertFalse(RisainaNotifier.clearedOnPurge(com.risaina.app.core.location.LiveLocationService.NOTIFICATION_ID, NotificationChannels.LOCATION_SHARING))
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
