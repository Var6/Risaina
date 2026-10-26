package com.risaina.app.core.sync

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.network.EventDto
import com.risaina.app.core.network.EventsResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three guarantees the push handler relies on (widget-delivery challenge): a deadline or an
 * error mid-sync never loses the notifications of events already applied; concurrent syncs share
 * one request; a slow widget recompute never overwrites a newer one.
 */
class SyncConcurrencyTest {
    private fun event(seq: Long) = EventDto("e$seq", "c1", seq, "ping.created", "her", JsonObject(emptyMap()), "2026-10-26T08:00:00Z")
    private fun page(vararg seqs: Long, latest: Long) = AppResult.Success(EventsResponse(seqs.map(::event), latest))
    private fun item(seq: Long) = Incoming.Game("g$seq", "moved")

    /** A fake event log: [pages] in order; apply returns one announceable item per event. */
    private class Harness(val pages: List<suspend () -> AppResult<EventsResponse>>) {
        val applied = mutableListOf<Long>()
        val announced = mutableListOf<Incoming>()
        var fetches = 0
        var failApplyAt: Long? = null
        var applyDelayMs = 0L

        suspend fun run(since0: Long = 5, mark: Long = 0) = runPagedSync(
            since0 = since0,
            fetch = { _, _ -> pages[fetches++].invoke() },
            bootstrapMark = { mark },
            currentMark = { mark },
            apply = { e, _ ->
                if (applyDelayMs > 0) delay(applyDelayMs)
                if (e.seq == failApplyAt) throw IllegalStateException("bad event")
                applied += e.seq
                listOf(Incoming.Game("g${e.seq}", "moved"))
            },
            announce = { announced += it },
        )
    }

    // --- (1) nothing applied is ever left un-announced ------------------------------------------

    @Test fun aFailingSecondPageKeepsTheFirstPagesNotifications() = runTest {
        val h = Harness(listOf({ page(6, 7, latest = 9) }, { AppResult.Failure(AppError.Offline()) }))
        val r = h.run()
        assertEquals(listOf(6L, 7L), h.applied)
        assertEquals(listOf(item(6), item(7)), h.announced)
        assertEquals(listOf(item(6), item(7)), r.items)
        assertTrue(r.failure?.error is AppError.Offline)
    }

    @Test fun anEventThatCannotBeAppliedStopsAsAFailureNotAnException() = runTest {
        val h = Harness(listOf({ page(6, 7, 8, latest = 8) })).apply { failApplyAt = 8 }
        val r = h.run()
        assertEquals(listOf(item(6), item(7)), h.announced)
        assertTrue(r.failure?.error is AppError.Unknown)
    }

    @Test fun theCallersDeadlineDuringALaterPageKeepsEarlierNotifications() = runTest {
        val h = Harness(listOf({ page(6, 7, latest = 9) }, { delay(10_000); page(8, 9, latest = 9) }))
        val r = withTimeoutOrNull(4_000) { h.run() }
        assertNull("the caller gave up", r)
        assertEquals(listOf(item(6), item(7)), h.announced)
    }

    @Test fun anEventBeingAppliedWhenTheDeadlineHitsIsStillAnnounced() = runTest {
        // Applying takes 3 s per event; the deadline lands in the middle of the second one.
        val h = Harness(listOf({ page(6, 7, 8, latest = 8) })).apply { applyDelayMs = 3_000 }
        withTimeoutOrNull(4_000) { h.run() }
        assertEquals("applied events are announced, never only half done", h.applied.map(::item), h.announced)
        assertEquals(listOf(6L, 7L), h.applied)
    }

    @Test fun replayedHistoryIsAppliedButNotAnnounced() = runTest {
        val h = Harness(listOf({ page(1, 2, 3, latest = 3) }))
        val r = h.run(since0 = 0, mark = 2)
        assertEquals(listOf(1L, 2L, 3L), h.applied)
        assertEquals(listOf(item(3)), h.announced)
        assertEquals(listOf(item(3)), r.items)
    }

    @Test fun stopsAfterOneRequestOnceCaughtUp() = runTest {
        val h = Harness(listOf({ page(6, latest = 6) }, { error("must not fetch again") }))
        assertNull(h.run().failure)
        assertEquals(1, h.fetches)
    }

    // --- (2) concurrent syncs share one request ----------------------------------------------------

    /** Like EventSyncManager's: a supervisor scope (a failed run stays in its Deferred), on test time. */
    private fun TestScope.flights() = SingleFlight<Set<String>, Int>(
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        clock = { testScheduler.currentTime },
    ) { a, b -> a + b }

    @Test fun callersThatArriveTogetherShareOneRun() = runTest {
        val flights = flights()
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        var seenOptions: Set<String> = emptySet()
        val block: suspend (SingleFlight.Flight<Set<String>>) -> Int = { f -> runs++; gate.await(); seenOptions = f.options; runs }
        val a = async { flights.run(setOf("notify"), notBefore = 0, block = block) }
        val b = async { flights.run(setOf("media"), notBefore = 0, block = block) }
        runCurrent()
        gate.complete(Unit)
        assertEquals(1, a.await())
        assertEquals(1, b.await())
        assertEquals(1, runs)
        assertEquals("the joiner's options apply to the shared run", setOf("notify", "media"), seenOptions)
    }

    @Test fun aCallerNeverSettlesForARunThatStartedBeforeItsPush() = runTest {
        val flights = flights()
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val block: suspend (SingleFlight.Flight<Set<String>>) -> Int = { runs++; val n = runs; gate.await(); n }
        // A periodic sync starts at t=0 and is still fetching.
        val periodic = async { flights.run(emptySet(), notBefore = 0, block = block) }
        runCurrent()
        // A push arrives at t=100: the running sync may not contain its event, so a second run is
        // queued behind it; a third caller (the worker of the same push) joins that queued run.
        delay(100)
        val push = async { flights.run(setOf("notify"), notBefore = 100, block = block) }
        val worker = async { flights.run(setOf("notify"), notBefore = 100, block = block) }
        runCurrent()
        assertEquals("never two runs at once", 1, runs)
        gate.complete(Unit)
        assertEquals(1, periodic.await())
        assertEquals(2, push.await())
        assertEquals(2, worker.await())
        assertEquals(2, runs)
    }

    @Test fun aRunThatStartedAfterThePushServesIt() = runTest {
        val flights = flights()
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val block: suspend (SingleFlight.Flight<Set<String>>) -> Int = { runs++; gate.await(); runs }
        delay(50)
        // The worker of a push received at t=10 started a sync at t=50: the handler may share it.
        val worker = async { flights.run(emptySet(), notBefore = 50, block = block) }
        runCurrent()
        val handler = async { flights.run(setOf("notify"), notBefore = 10, block = block) }
        runCurrent()
        gate.complete(Unit)
        assertEquals(1, worker.await())
        assertEquals(1, handler.await())
        assertEquals(1, runs)
    }

    @Test fun aCallerThatGivesUpDoesNotStopTheSharedRun() = runTest {
        val flights = flights()
        var finished = false
        val handler = async { withTimeoutOrNull(4_000) { flights.run(emptySet(), notBefore = 0) { delay(10_000); finished = true; 1 } } }
        assertNull(handler.await())
        advanceUntilIdle()
        assertTrue("the sync goes on for the worker and the widgets", finished)
    }

    @Test fun aFailedRunDoesNotFailTheOneQueuedBehindIt() = runTest {
        val flights = flights()
        val gate = CompletableDeferred<Unit>()
        val first = async { runCatching { flights.run(emptySet(), notBefore = 0) { gate.await(); error("boom") } } }
        runCurrent()
        delay(10)
        val second = async { flights.run(emptySet(), notBefore = 10) { 7 } }
        runCurrent()
        gate.complete(Unit)
        assertTrue(first.await().isFailure)
        assertEquals(7, second.await())
    }

    // --- (3) a slow recompute never overwrites a newer one -----------------------------------------

    @Test fun aSlowOlderRecomputeCannotOverwriteANewerSnapshot() = runTest {
        val serial = SerialRunner()
        var db = "old"
        var store: String? = null
        // Same shape as WidgetSnapshotUpdater: read the data, do slow work (a photo), write the store.
        suspend fun recompute(workMs: Long) = serial.run { val read = db; delay(workMs); store = read }
        val slow = launch { recompute(5_000) }
        runCurrent()
        db = "new" // a newer event is applied while the slow recompute is still working
        val fast = launch { recompute(10) }
        slow.join(); fast.join()
        assertEquals("new", store)
    }

    @Test fun recomputesRunInCallOrder() = runTest {
        val serial = SerialRunner()
        val order = mutableListOf<Int>()
        val jobs = (1..5).map { n -> launch { serial.run { delay((6 - n) * 100L); order += n } } }
        jobs.forEach { it.join() }
        assertEquals(listOf(1, 2, 3, 4, 5), order)
    }
}
