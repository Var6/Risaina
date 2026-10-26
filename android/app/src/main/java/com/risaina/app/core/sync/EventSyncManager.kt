package com.risaina.app.core.sync

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.network.EventSocket
import com.risaina.app.core.network.EventsResponse
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SocketMessage
import com.risaina.app.core.network.SocketState
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the foreground WebSocket and the catch-up sync. The socket runs only while [start] is
 * called from a lifecycle that is at least STARTED; [stop] closes it. Background catch-up uses
 * [syncOnce] (invoked by the FCM service and SyncWorker after a push, or on a periodic schedule).
 */
@Singleton
class EventSyncManager @Inject constructor(
    private val socket: EventSocket,
    private val api: RisainaApi,
    private val reducer: EventReducer,
    private val sessionStore: SessionStore,
    private val coupleRepository: CoupleRepository,
    private val widgetSnapshotUpdater: WidgetSnapshotUpdater,
    private val haptics: com.risaina.app.core.notifications.Haptics,
    private val deferred: DeferredEffects,
) {
    private val applyMutex = Mutex()
    private var socketJob: Job? = null

    val socketState: StateFlow<SocketState> = socket.state

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 16)
    /** Live incoming items while the socket is connected (for the in-app reveal). */
    val incoming: SharedFlow<Incoming> = _incoming

    fun start(scope: CoroutineScope) {
        if (socketJob?.isActive == true) return
        socketJob = scope.launch {
            socket.connect(sinceProvider = { sessionStore.lastEventSeq.first() }).collect { msg ->
                when (msg) {
                    // Sent before the replay starts: when the cursor is 0, everything up to latestSeq
                    // is history (ReplayGuard).
                    is SocketMessage.Ready -> sessionStore.setBootstrapUntilSeqIfFresh(msg.latestSeq)
                    is SocketMessage.Event -> {
                        val mark = sessionStore.bootstrapUntilSeq.first()
                        val results = applyMutex.withLock { reducer.apply(msg.event, ReplayGuard.isReplay(msg.event.seq, mark)) }
                        if (results.isNotEmpty()) {
                            // A replayed old gift must not buzz or pop the reveal as if it just arrived.
                            val fresh = ReplayGuard.announceable(results, msg.event.seq, mark)
                            if (fresh.any { it is Incoming.NewPing }) haptics.arrival()
                            fresh.forEach { _incoming.tryEmit(it) }
                            widgetSnapshotUpdater.recompute()
                        }
                        deferred.drain()
                    }
                    SocketMessage.Synced -> widgetSnapshotUpdater.recompute()
                    SocketMessage.Revoked -> coupleRepository.refreshMe()
                }
            }
        }
    }

    fun stop() { socketJob?.cancel(); socketJob = null }

    /**
     * Fetch and apply everything after the cursor. Returns the new incoming items.
     *
     * [onIncoming] is called for each new item as soon as its event is applied, so a caller with a
     * deadline (the push handler) never loses a notification: applying an event and announcing it
     * run as one non-cancellable step, and the cursor only moves past events that were announced.
     * (The push handler used to collect everything and notify at the end; its 8 s timeout then
     * dropped the list after the cursor had already moved, and no later sync posted it.)
     *
     * [fetchMedia] = false leaves a new photo's download to the caller
     * (WidgetSnapshotUpdater.completeMedia), so text, pings and gifts reach the widgets first.
     */
    suspend fun syncOnce(
        fetchMedia: Boolean = true,
        onIncoming: (suspend (Incoming) -> Unit)? = null,
    ): AppResult<List<Incoming>> {
        if (sessionStore.current() == null) return AppResult.Success(emptyList())
        val collected = mutableListOf<Incoming>()
        var since = sessionStore.lastEventSeq.first()
        var failure: AppResult.Failure? = null
        // At most 10 pages per sync. (This used `repeat` with `return@repeat`, which only skips to the
        // next pass, so every sync made 10 requests even when it had already caught up.)
        for (pageNo in 0 until 10) {
            val page = when (val r = fetchPage(since, retry = pageNo == 0)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> { failure = r; break }
            }
            // Cursor 0 = a full replay (fresh sign-in or a new pairing): mark where history ends.
            if (since == 0L) sessionStore.setBootstrapUntilSeqIfFresh(page.latestSeq)
            if (page.items.isEmpty()) break
            val mark = sessionStore.bootstrapUntilSeq.first()
            for (e in page.items) {
                withContext(NonCancellable) {
                    val replay = ReplayGuard.isReplay(e.seq, mark)
                    val fresh = ReplayGuard.announceable(applyMutex.withLock { reducer.apply(e, replay) }, e.seq, mark)
                    collected += fresh
                    if (onIncoming != null) fresh.forEach { runCatching { onIncoming(it) } }
                }
            }
            since = page.items.maxOf { it.seq }
            if (since >= page.latestSeq) break
        }
        // Recompute even when nothing new arrived, or a page failed after others were applied: the
        // countdown's "days left" moves at midnight, and what was applied must reach the widgets.
        widgetSnapshotUpdater.recompute(fetchMedia = fetchMedia)
        failure?.let { return it }
        // Open on screen but the event came by push or catch-up sync (not the socket): the
        // notification is suppressed in the foreground, so buzz and reveal it in the app instead.
        if (androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            val freshPing = collected.filterIsInstance<Incoming.NewPing>().any { java.time.Duration.between(it.ping.createdAt, java.time.Instant.now()).seconds < 60 }
            if (freshPing) haptics.arrival()
            collected.forEach { _incoming.tryEmit(it) }
        }
        return AppResult.Success(collected)
    }

    /** Run the network follow-ups the reducer queued (receipts, game refresh). See [DeferredEffects]. */
    suspend fun runDeferred() = deferred.drain()

    /**
     * Right after a Doze wake the network can lag for a moment: retry the first page once after a
     * second instead of giving up on the whole sync.
     */
    private suspend fun fetchPage(since: Long, retry: Boolean): AppResult<EventsResponse> {
        val first = apiCall { api.events(since = since, limit = 200) }
        if (first !is AppResult.Failure || !retry || !first.error.isRetryable) return first
        delay(1_000)
        return apiCall { api.events(since = since, limit = 200) }
    }
}
