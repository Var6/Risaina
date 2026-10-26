package com.risaina.app.core.sync

import com.risaina.app.core.common.AppError
import com.risaina.app.core.data.GameRepository
import com.risaina.app.core.location.LiveLocationRepository
import com.risaina.app.core.network.LiveLocationDto
import com.risaina.app.core.network.LiveLocationStateDto
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.safety.SafetyNotifier
import com.risaina.app.testutil.contextWithPrefs
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the queued network follow-ups do with their answers (the queue itself: DeferredQueueTest). */
class DeferredEffectsTest {
    private val api = mockk<RisainaApi>()
    private val games = mockk<GameRepository>(relaxed = true)
    private val safetyNotifier = mockk<SafetyNotifier>(relaxed = true)
    private val effects = DeferredEffects(contextWithPrefs(), mockk(relaxed = true), games, { mockk(relaxed = true) }, LiveLocationRepository(api), safetyNotifier)

    private fun session(id: String) = LiveLocationDto(sessionId = id, userId = "her", startedAt = "2026-10-26T10:00:00Z", updatedAt = "2026-10-26T10:00:00Z")

    @Test fun aShareTheServerNoLongerHasLosesItsNotification() = runTest {
        // Its stop is still on the way, or it was purged without an event (12 h with no fix).
        coEvery { api.locationSharing() } returns LiveLocationStateDto(partner = null)
        effects.checkPartnerLocation()
        effects.drain()
        verify(exactly = 1) { safetyNotifier.cancelPartnerSharing() }
        assertTrue(effects.pending().isEmpty())
    }

    @Test fun aLiveShareKeepsItsNotification() = runTest {
        coEvery { api.locationSharing() } returns LiveLocationStateDto(partner = session("loc1"))
        effects.checkPartnerLocation()
        effects.drain()
        verify(exactly = 0) { safetyNotifier.cancelPartnerSharing() }
        assertTrue(effects.pending().isEmpty())
    }

    @Test fun staleGamesThatCouldNotBeFetchedStayQueued() = runTest {
        // The card would otherwise keep the old board ("Raina's turn") until another game event.
        coEvery { games.flushStale(any()) } returns AppError.Timeout()
        effects.refreshStaleGames(full = false)
        effects.drain()
        assertEquals(setOf("games:stale"), effects.pending())

        coEvery { games.flushStale(any()) } returns null
        effects.drain()
        assertTrue(effects.pending().isEmpty())
    }

    @Test fun offlineStaleGamesStayQueuedWithoutCounting() = runTest {
        coEvery { games.flushStale(any()) } returns AppError.Offline()
        effects.refreshStaleGames(full = true)
        repeat(DeferredQueue.MAX_FAILURES + 1) { effects.drain() }
        assertEquals(setOf("games:all"), effects.pending())
    }
}
