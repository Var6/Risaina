package com.risaina.app.core.sync

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.network.EventDto
import com.risaina.app.core.network.EventsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one [block] at a time and lets callers share a run instead of each starting their own. The
 * push handler, the expedited SyncWorker (which WorkManager starts in the same process at once) and
 * the periodic worker used to make the same /events requests side by side.
 *
 * A caller may join a run only if that run starts (or started) no earlier than the caller's
 * [run] `notBefore`: a sync that fetched before the push arrived would not contain the pushed event,
 * and the caller would wrongly believe it had synced. Otherwise a new run is queued behind the
 * current one; later callers join that queued run while it has not started, so a burst of pushes
 * costs at most one extra (usually empty) request, never parallel ones. Joiners merge their options
 * into the run (e.g. "notify" turns on); the merge happens before the run starts or right as it
 * starts, before any network answer, so it covers the whole run.
 *
 * The work runs in [scope], not in the caller: a caller that gives up (the push handler's time
 * budget) stops waiting, but the shared run finishes for everyone else.
 */
class SingleFlight<O, T>(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * A run that has gone on longer than this is taken as stuck (a dead connection waiting for its
     * read timeout): a new caller then starts its own run at once instead of queueing behind it, so
     * one hung sync cannot hold up every later push. The stuck run still ends at its own deadline.
     * Two runs side by side are safe (the reducer skips events already applied), only wasteful.
     */
    private val stallAfterMs: Long = Long.MAX_VALUE,
    private val merge: (O, O) -> O,
) {
    /** One run. [options] may change until it starts, as callers join. */
    class Flight<O>(@Volatile var options: O) {
        /** When the run began (clock time), or null while it waits for the previous one. */
        @Volatile var startedAt: Long? = null
            internal set
    }

    private class Run<O, T>(val flight: Flight<O>, val job: Deferred<T>)

    private val lock = Mutex()
    /** The newest run, running or queued. Runs are chained, so only this one can be joined. */
    private var tail: Run<O, T>? = null

    /**
     * @param notBefore the earliest start time of a run that can serve this caller, normally the time
     *   of the trigger (a push's arrival). Defaults to now.
     */
    suspend fun run(options: O, notBefore: Long = clock(), block: suspend (Flight<O>) -> T): T {
        val job = lock.withLock {
            val last = tail?.takeIf { it.job.isActive }
            val started = last?.flight?.startedAt
            if (last != null && (started == null || started >= notBefore)) {
                last.flight.options = merge(last.flight.options, options)
                last.job
            } else {
                val flight = Flight(options)
                val stuck = started != null && clock() - started > stallAfterMs
                val previous = last?.job?.takeUnless { stuck }
                scope.async {
                    // join() never throws, so a failed earlier run does not fail this one.
                    previous?.join()
                    flight.startedAt = clock()
                    block(flight)
                }.also { tail = Run(flight, it) }
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
data class PagedSync(val items: List<Incoming>, val failure: AppResult.Failure?)

/**
 * The page loop of a sync, with its I/O passed in so it is unit-tested (SyncConcurrencyTest).
 *
 * Applying an event and announcing it (posting its notification) is one non-cancellable step, and
 * the reducer moves the cursor inside apply. So a deadline or an error can stop the loop between
 * events, never between "applied" and "announced": no notification is lost because a later event
 * timed out or threw. (The push handler used to notify only after the whole sync; a timeout then
 * dropped everything after the cursor had already moved.) An event that cannot be applied stops the
 * loop as a failure instead of an exception, so the caller still redraws the widgets for what was
 * applied; that event is retried by the next sync (the cursor did not move past it).
 *
 * The whole loop has a deadline ([timeoutMs]): a sync is shared by every push that arrives while it
 * runs (SingleFlight), so one that hangs on a dead connection must end, as a Timeout failure that
 * keeps what was applied, instead of holding up the syncs queued behind it.
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
    timeoutMs: Long = Long.MAX_VALUE,
): PagedSync {
    val collected = mutableListOf<Incoming>()
    var failure: AppResult.Failure? = null
    val finished = withTimeoutOrNull(timeoutMs) {
        failure = pagedLoop(since0, fetch, bootstrapMark, currentMark, apply, announce, maxPages, collected)
        true
    }
    return PagedSync(collected, if (finished == null) AppResult.Failure(AppError.Timeout()) else failure)
}

/** The page loop of [runPagedSync]; returns the failure that stopped it, if any. */
private suspend fun pagedLoop(
    since0: Long,
    fetch: suspend (since: Long, first: Boolean) -> AppResult<EventsResponse>,
    bootstrapMark: suspend (latestSeq: Long) -> Long,
    currentMark: suspend () -> Long,
    apply: suspend (event: EventDto, replay: Boolean) -> List<Incoming>,
    announce: suspend (Incoming) -> Unit,
    maxPages: Int,
    collected: MutableList<Incoming>,
): AppResult.Failure? {
    var since = since0
    // At most [maxPages] pages per sync. (This used `repeat` with `return@repeat`, which only skips
    // to the next pass, so every sync made 10 requests even when it had already caught up.)
    for (pageNo in 0 until maxPages) {
        val page = when (val r = fetch(since, pageNo == 0)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        // Cursor 0 = a full replay (fresh sign-in or a new pairing): mark where history ends.
        val mark = if (since == 0L) bootstrapMark(page.latestSeq) else currentMark()
        if (page.items.isEmpty()) break
        for (e in page.items) {
            // The one place a deadline stops the loop: before an event, never inside one.
            currentCoroutineContext().ensureActive()
            val failure = withContext(NonCancellable) {
                val replay = ReplayGuard.isReplay(e.seq, mark)
                val applied = try {
                    apply(e, replay)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    return@withContext AppResult.Failure(t as? AppError ?: AppError.Unknown(t))
                }
                val fresh = ReplayGuard.announceable(applied, e.seq, mark)
                collected += fresh
                fresh.forEach { item ->
                    try { announce(item) } catch (c: CancellationException) { throw c } catch (_: Throwable) {}
                }
                null
            }
            if (failure != null) return failure
        }
        since = page.items.maxOf { it.seq }
        if (since >= page.latestSeq) break
    }
    return null
}

/**
 * One event from the foreground socket. Replayed history (seq at or below the bootstrap mark, see
 * ReplayGuard) is applied quietly: no buzz, no in-app reveal, so an old gift never pops up as new
 * after a sign-in or re-pairing. Returns whether the event produced anything (the caller then
 * redraws the widgets). Kept apart from the socket so it is unit-tested (SyncConcurrencyTest).
 */
suspend fun applySocketEvent(
    event: EventDto,
    mark: Long,
    apply: suspend (event: EventDto, replay: Boolean) -> List<Incoming>,
    reveal: (Incoming) -> Unit,
    buzz: () -> Unit,
): Boolean {
    val results = apply(event, ReplayGuard.isReplay(event.seq, mark))
    if (results.isEmpty()) return false
    val fresh = ReplayGuard.announceable(results, event.seq, mark)
    if (fresh.any { it is Incoming.NewPing }) buzz()
    fresh.forEach(reveal)
    return true
}
