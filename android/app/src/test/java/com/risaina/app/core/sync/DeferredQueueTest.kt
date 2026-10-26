package com.risaina.app.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The queue of network follow-ups (receipts, game refreshes) that runs after notifications and widgets. */
class DeferredQueueTest {
    private class MemoryStorage : DeferredQueue.Storage {
        var items = setOf<String>()
        val fails = mutableMapOf<String, Int>()
        override fun items() = items
        override fun saveItems(items: Set<String>) { this.items = items }
        override fun failures(item: String) = fails[item] ?: 0
        override fun saveFailures(item: String, count: Int) { if (count <= 0) fails.remove(item) else fails[item] = count }
    }

    @Test fun doneItemsLeaveTheQueue() = runTest {
        val q = DeferredQueue(MemoryStorage())
        q.add("ack:1"); q.add("game:g")
        val ran = mutableListOf<String>()
        q.drain { ran += it; EffectOutcome.DONE }
        assertEquals(setOf("ack:1", "game:g"), ran.toSet())
        assertTrue(q.pending().isEmpty())
    }

    @Test fun anItemQueuedAgainWhileItRunsRunsAgain() = runTest {
        // Two quick game moves: the refresh for move 1 is in flight (it fetched the state before move
        // 2) when move 2's event queues the same game again. That newer refresh must not be lost.
        val q = DeferredQueue(MemoryStorage())
        q.add("game:x")
        val firstRunStarted = CompletableDeferred<Unit>()
        val releaseFirstRun = CompletableDeferred<Unit>()
        var runs = 0
        val drain = async {
            q.drain {
                runs++
                if (runs == 1) { firstRunStarted.complete(Unit); releaseFirstRun.await() }
                EffectOutcome.DONE
            }
        }
        firstRunStarted.await()
        q.add("game:x") // move 2
        releaseFirstRun.complete(Unit)
        drain.await()
        assertEquals(2, runs)
        assertTrue(q.pending().isEmpty())
    }

    @Test fun anItemAddedDuringADrainIsPickedUpByIt() = runTest {
        val q = DeferredQueue(MemoryStorage())
        q.add("ack:1")
        val ran = mutableListOf<String>()
        q.drain { item -> ran += item; if (item == "ack:1") q.add("ack:2"); EffectOutcome.DONE }
        assertEquals(listOf("ack:1", "ack:2"), ran)
    }

    @Test fun anItemQueuedAgainAfterThisDrainRanItRunsAgain() = runTest {
        // The drain refreshed the stale games, then sends a receipt; meanwhile a new move marks a
        // game stale and queues "games:stale" again. A drain started for it returns at once (this
        // one holds the lock), so this one must not skip it as already tried.
        val q = DeferredQueue(MemoryStorage())
        q.add("games:stale"); q.add("ack:1")
        val ran = mutableListOf<String>()
        q.drain { item ->
            ran += item
            if (item == "ack:1" && ran.count { it == "games:stale" } == 1) q.add("games:stale")
            EffectOutcome.DONE
        }
        assertEquals(2, ran.count { it == "games:stale" })
        assertTrue(q.pending().isEmpty())
    }

    @Test fun aFailingItemRunsOncePerDrainAndIsDroppedAfterMaxFailures() = runTest {
        val storage = MemoryStorage()
        val q = DeferredQueue(storage)
        q.add("ack:bad")
        var runs = 0
        repeat(DeferredQueue.MAX_FAILURES - 1) { q.drain { runs++; EffectOutcome.FAILED } }
        assertEquals("once per drain, no spinning", DeferredQueue.MAX_FAILURES - 1, runs)
        assertEquals(setOf("ack:bad"), q.pending())
        q.drain { runs++; EffectOutcome.FAILED }
        assertTrue("a receipt the server keeps refusing is given up", q.pending().isEmpty())
        assertTrue(storage.fails.isEmpty())
    }

    @Test fun offlineNeverCountsAgainstAnItem() = runTest {
        val q = DeferredQueue(MemoryStorage())
        q.add("ack:1")
        repeat(DeferredQueue.MAX_FAILURES * 2) { q.drain { EffectOutcome.OFFLINE } }
        assertEquals(setOf("ack:1"), q.pending())
    }

    @Test fun aCancelledRunKeepsTheItem() = runTest {
        val q = DeferredQueue(MemoryStorage())
        q.add("ack:1")
        val r = withTimeoutOrNull(1_000) { q.drain { CompletableDeferred<Unit>().await(); EffectOutcome.DONE } }
        assertEquals(null, r)
        assertEquals(setOf("ack:1"), q.pending())
        // And the queue is usable again (the drain lock was released).
        q.drain { EffectOutcome.DONE }
        assertTrue(q.pending().isEmpty())
    }

    @Test fun aNonWaitingDrainSkipsWhileAnotherRuns() = runTest {
        val q = DeferredQueue(MemoryStorage())
        q.add("ack:1")
        val release = CompletableDeferred<Unit>()
        val first = async { q.drain { release.await(); EffectOutcome.DONE } }
        runCurrent()
        var secondRan = false
        q.drain(wait = false) { secondRan = true; EffectOutcome.DONE }
        assertFalse("the socket does not queue behind the worker's network calls", secondRan)
        release.complete(Unit)
        first.await()
        assertTrue(q.pending().isEmpty())
    }
}
