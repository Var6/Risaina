package com.risaina.app.core.sync

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.network.EventDto
import com.risaina.app.core.network.EventsResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs at most one [block] at a time; callers that arrive while it runs share its result instead of
 * starting their own. The push handler, the expedited SyncWorker (which WorkManager starts in the
 * same process at once) and the periodic worker used to make the same /events requests side by
 * side. Joiners merge their options into the running one (e.g. "notify" turns on for the rest of
 * the run). The work runs in [scope], not in the caller: a caller that gives up (the push handler's
 * time budget) stops waiting, but the shared run finishes for everyone else.
 */
class SingleFlight<O, T>(private val scope: CoroutineScope, private val merge: (O, O) -> O) {
    /** One run. [options] may change while it runs, as callers join. */
    class Flight<O>(@Volatile var options: O)

    private class Running<O, T>(val flight: Flight<O>, val job: Deferred<T>)

    private val lock = Mutex()
    private var running: Running<O, T>? = null

    suspend fun run(options: O, block: suspend (Flight<O>) -> T): T {
        val job = lock.withLock {
            val current = running?.takeIf { it.job.isActive }
            if (current != null) {
                current.flight.options = merge(current.flight.options, options)
                current.job
            } else {
                val flight = Flight(options)
                scope.async { block(flight) }.also { running = Running(flight, it) }
            }
        }
        return job.await()
    }
}

/**
 * Runs blocks one at a time, in call order (the coroutine Mutex is fair). WidgetSnapshotUpdater uses
 * it so a slow recompute that read older data always finishes before a newer one reads, and so can
 * never write its older snapshot (and redraw the widgets and lock card) over a newer one.
 */
class SerialRunner {
    private val mutex = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

/** What one sync applied: the announceable items, and the failure that stopped it, if any. */
data class PagedSync(val items: List<com.risaina.app.core.sync.Incoming>, val failure: AppResult.Failure?)

/**
 * The page loop of a sync, with its I/O passed in so it is unit-tested (SyncConcurrencyTest).
 *
 * Applying an event and announcing it (posting its notification) is one non-cancellable step, and
 * the reducer moves the cursor inside apply. So a deadline or an error can stop the loop between
 * events, never between "applied" and "announced": no notification is lost because a later event
 * timed out or threw. (The push handler used to notify only after the whole sync; a timeout then
 * dropped everything after the cursor had already moved.)
 *
 * @param fetch page after `since`; `first` is true for the first page of this sync
 * @param bootstrapMark called with the first page when the sync started from cursor 0; returns the
 *   replay high-water mark to use (ReplayGuard)
 */
suspend fun runPagedSync(
    since0: Long,
    fetch: suspend (since: Long, first: Boolean) -> AppResult<EventsResponse>,
    bootstrapMark: suspend (latestSeq: Long) -> Long,
    currentMark: suspend () -> Long,
    apply: suspend (event: EventDto, replay: Boolean) -> List<Incoming>,
    announce: suspend (Incoming) -> Unit,
    maxPages: Int = 10,
): PagedSync {
    val collected = mutableListOf<Incoming>()
    var since = since0
    // At most [maxPages] pages per sync. (This used `repeat` with `return@repeat`, which only skips
    // to the next pass, so every sync made 10 requests even when it had already caught up.)
    for (pageNo in 0 until maxPages) {
        val page = when (val r = fetch(since, pageNo == 0)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return PagedSync(collected, r)
        }
        // Cursor 0 = a full replay (fresh sign-in or a new pairing): mark where history ends.
        val mark = if (since == 0L) bootstrapMark(page.latestSeq) else currentMark()
        if (page.items.isEmpty()) break
        for (e in page.items) {
            withContext(NonCancellable) {
                val replay = ReplayGuard.isReplay(e.seq, mark)
                val fresh = ReplayGuard.announceable(apply(e, replay), e.seq, mark)
                collected += fresh
                fresh.forEach { item -> runCatching { announce(item) } }
            }
        }
        since = page.items.maxOf { it.seq }
        if (since >= page.latestSeq) break
    }
    return PagedSync(collected, null)
}
