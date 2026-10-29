package com.risaina.app.core.notifications

import com.risaina.app.testutil.contextWithPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detailed notification must not buzz again right after the quick one for the same item. */
class FastAlertsTest {
    private val now = 1_000_000L

    @Test fun theDetailedPostForTheSameItemIsSilent() {
        assertTrue(FastAlerts.follows(fastAt = now - 3_000, fastRef = "m1", ref = "m1", now = now))
    }

    @Test fun anotherItemFromTheSameSyncStillAlerts() {
        // A burst: the sync for m1's push also brought m2, which had no quick alert of its own.
        assertFalse(FastAlerts.follows(fastAt = now - 3_000, fastRef = "m1", ref = "m2", now = now))
    }

    @Test fun aQuickAlertFromAnOlderServerMatchesAnyItem() {
        assertTrue(FastAlerts.follows(fastAt = now - 3_000, fastRef = null, ref = "p9", now = now))
    }

    @Test fun anOldQuickAlertNeverMutesANewItem() {
        assertFalse(FastAlerts.follows(fastAt = now - FastAlerts.WINDOW_MS, fastRef = "m1", ref = "m1", now = now))
    }

    @Test fun noQuickAlertOrAClockJumpMeansAlert() {
        assertFalse(FastAlerts.follows(fastAt = 0, fastRef = null, ref = "m1", now = now))
        assertFalse(FastAlerts.follows(fastAt = now + 60_000, fastRef = "m1", ref = "m1", now = now))
    }

    // --- what the detailed post does ----------------------------------------------------------

    @Test fun afterAQuickAlertStillShowingTheDetailedOneUpdatesItInPlace() {
        assertEquals(DetailedAction.UPDATE, FastAlerts.detailedAction(followsFast = true, showing = true))
        assertTrue("an update in place must not alert again", DetailedAction.UPDATE.onlyAlertOnce)
    }

    @Test fun aQuickAlertTheyAlreadySwipedOrTappedIsNotBroughtBack() {
        assertEquals(DetailedAction.SKIP, FastAlerts.detailedAction(followsFast = true, showing = false))
    }

    @Test fun withoutAQuickAlertTheDetailedPostAlerts() {
        assertEquals(DetailedAction.ALERT, FastAlerts.detailedAction(followsFast = false, showing = true))
        assertEquals(DetailedAction.ALERT, FastAlerts.detailedAction(followsFast = false, showing = false))
        // A new item replacing an older one under the same id must still sound.
        assertFalse(DetailedAction.ALERT.onlyAlertOnce)
    }

    // --- every quick alert remembered per item ------------------------------------------------

    @Test fun inABurstEachItemKnowsItAlreadyAlerted() {
        // Two pings' pushes both post their quick alert before the sync brings either: the detailed
        // post of the first must not buzz a third time just because the last quick alert was the second's.
        val d = DeliveryDiagnostics(contextWithPrefs())
        d.recordQuickAlert(DeliveryDiagnostics.QUICK_PING, "p1", now - 2_000)
        d.recordQuickAlert(DeliveryDiagnostics.QUICK_PING, "p2", now - 1_000)
        assertEquals(setOf("p1", "p2"), d.quickAlerts(DeliveryDiagnostics.QUICK_PING, now).keys)
        d.forgetQuickAlert(DeliveryDiagnostics.QUICK_PING, "p1")
        assertEquals(setOf("p2"), d.quickAlerts(DeliveryDiagnostics.QUICK_PING, now).keys)
        // Kinds are kept apart.
        assertTrue(d.quickAlerts(DeliveryDiagnostics.QUICK_MESSAGE, now).isEmpty())
    }

    @Test fun quickRefsSurviveStorage() {
        val refs = mapOf("65a1f0c2e4b0a1b2c3d4e5f6" to 1_000L, "65a1f0c2e4b0a1b2c3d4e5f7" to 2_000L)
        assertEquals(refs, QuickAlertRefs.decode(QuickAlertRefs.encode(refs)))
        assertEquals(emptyMap<String, Long>(), QuickAlertRefs.decode(null))
        assertEquals(emptyMap<String, Long>(), QuickAlertRefs.decode("garbage,:5,x:"))
    }

    @Test fun quickRefsOutliveTheOldServerWindowButNotADay() {
        val refs = QuickAlertRefs.add(
            mapOf("expired" to now - QuickAlertRefs.KEEP_MS, "minutes" to now - 3 * 60_000, "recent" to now - 1_000),
            "new", now,
        )
        assertEquals(setOf("minutes", "recent", "new"), refs.keys)
        assertEquals(setOf("new"), QuickAlertRefs.fresh(refs, now + QuickAlertRefs.KEEP_MS - 500).keys)
        // A clock that jumped back never keeps one for ever.
        assertTrue(QuickAlertRefs.fresh(mapOf("future" to now + 60_000), now).isEmpty())
    }

    // --- a sync that comes minutes late (R8) --------------------------------------------------

    @Test fun aSyncMinutesLateStillUpdatesTheQuickAlertInPlace() {
        // The 4 s inline sync failed and the SyncWorker backed off: the ping syncs 3 minutes after its
        // quick alert, which is still in the shade. It must be updated without a second buzz.
        val d = DeliveryDiagnostics(contextWithPrefs())
        val quickAt = now - 3 * 60_000
        d.recordFastAlert(DeliveryDiagnostics.QUICK_PING, "p1", quickAt)
        d.recordQuickAlert(DeliveryDiagnostics.QUICK_PING, "p1", quickAt)
        val follows = FastAlerts.followsQuick(d.quickAlerts(DeliveryDiagnostics.QUICK_PING, now), quickAt, "p1", "p1", now)
        assertTrue(follows)
        assertEquals(DetailedAction.UPDATE, FastAlerts.detailedAction(follows, showing = true))
        // Another ping in the same sync, with no quick alert of its own, still alerts.
        assertFalse(FastAlerts.followsQuick(d.quickAlerts(DeliveryDiagnostics.QUICK_PING, now), quickAt, "p1", "p2", now))
    }

    @Test fun aLateMessageKeepsItsPlaceholderLineUntilItSyncs() {
        // Before, the "Sent you a message" line went after 2 minutes: a rebuild then dropped it (or the
        // whole notification), and the message buzzed again when it finally synced.
        val d = DeliveryDiagnostics(contextWithPrefs())
        d.recordQuickAlert(DeliveryDiagnostics.QUICK_MESSAGE, "m1", now - 3 * 60_000)
        val c = ConversationLines.build(emptyList(), d.quickAlerts(DeliveryDiagnostics.QUICK_MESSAGE, now), previews = true)
        assertEquals(listOf(ConversationLines.PLACEHOLDER), c.lines.map { it.text })
    }

    @Test fun aQuickAlertWithoutAnItemIdStillOnlyCoversTwoMinutes() {
        // An older server's ping push names no ping, so it matches any: it must not mute a new one later.
        assertTrue(FastAlerts.followsQuick(emptyMap(), now - 30_000, null, "p9", now))
        assertFalse(FastAlerts.followsQuick(emptyMap(), now - 3 * 60_000, null, "p9", now))
        assertFalse(FastAlerts.followsQuick(emptyMap(), 0, null, "p9", now))
    }

    @Test fun signOutForgetsEveryQuickAlert() {
        val d = DeliveryDiagnostics(contextWithPrefs())
        d.recordQuickAlert(DeliveryDiagnostics.QUICK_PING, "p1", now - 1_000)
        d.recordQuickAlert(DeliveryDiagnostics.QUICK_MESSAGE, "m1", now - 1_000)
        d.clearQuickAlerts()
        assertTrue(d.quickAlerts(DeliveryDiagnostics.QUICK_PING, now).isEmpty())
        assertTrue(d.quickAlerts(DeliveryDiagnostics.QUICK_MESSAGE, now).isEmpty())
    }

    @Test fun quickRefsAreBounded() {
        var refs = emptyMap<String, Long>()
        for (i in 1..40) refs = QuickAlertRefs.add(refs, "m$i", 1_000L + i)
        assertEquals(16, refs.size)
        assertTrue("m40" in refs && "m1" !in refs)
        // Malformed ids are not remembered.
        assertEquals(refs, QuickAlertRefs.add(refs, "a,b", 1_100L))
    }
}
