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
import com.risaina.app.core.notifications.DeliveryDiagnostics
import com.risaina.app.core.notifications.DeliveryHealthRules
import com.risaina.app.core.notifications.DeviceStateReader
import com.risaina.app.core.notifications.RisainaNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    private val notifier: RisainaNotifier,
    private val diagnostics: DeliveryDiagnostics,
    private val deviceState: DeviceStateReader,
    private val lists: dagger.Lazy<com.risaina.app.core.data.ListsRepository>,
    private val listSync: dagger.Lazy<com.risaina.app.core.data.lists.ListSyncEngine>,
) {
    private val applyMutex = Mutex()
    private var socketJob: Job? = null

    /** Shared syncs run here, not in a caller that may give up (see [SingleFlight]). */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flights = SingleFlight<SyncOptions, AppResult<List<Incoming>>>(syncScope, stallAfterMs = FLIGHT_STALL_MS) { a, b ->
        SyncOptions(notify = a.notify || b.notify)
    }

    /** [notify]: post notifications for new items. */
    data class SyncOptions(val notify: Boolean)

    val socketState: StateFlow<SocketState> = socket.state

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 16)
    /** Live incoming items while the socket is connected (for the in-app reveal). */
    val incoming: SharedFlow<Incoming> = _incoming

    fun start(scope: CoroutineScope) {
        if (socketJob?.isActive == true) return
        socketJob = scope.launch {
            val socketScope = this
            // True once the replay after (re)connecting is done: from then on events are live.
            var live = false
            // Network follow-ups and the photo run beside the socket, never inside the collector:
            // each one is a network call, and new messages must keep appearing meanwhile. A drain
            // already under way (the SyncWorker's) is not waited for; it picks up what was queued.
            fun launchFollowUps() = socketScope.launch {
                try {
                    widgetSnapshotUpdater.completeMedia()
                    deferred.drain(wait = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            }
            socket.connect(sinceProvider = { sessionStore.lastEventSeq.first() }).collect { msg ->
                when (msg) {
                    // Sent before the replay starts: when the cursor is 0, everything up to latestSeq
                    // is history (ReplayGuard).
                    is SocketMessage.Ready -> { live = false; sessionStore.setBootstrapUntilSeqIfFresh(msg.latestSeq) }
                    is SocketMessage.Event -> {
                        val changed = applySocketEvent(
                            msg.event, sessionStore.bootstrapUntilSeq.first(),
                            apply = { e, replay -> applyMutex.withLock { reducer.apply(e, replay) } },
                            reveal = { _incoming.tryEmit(it) },
                            buzz = haptics::arrival,
                        )
                        if (changed) widgetSnapshotUpdater.recompute(fetchMedia = false)
                        // During the replay, follow-ups wait for Synced: once, not once per event.
                        if (live) launchFollowUps()
                    }
                    SocketMessage.Synced -> {
                        live = true
                        widgetSnapshotUpdater.recompute(fetchMedia = false)
                        launchFollowUps()
                        // Connected again (or the app came to the foreground): list edits kept on this
                        // phone go now, and lists catch up on events that were never stored (at most
                        // once a minute). Beside the socket, never inside the event collector.
                        socketScope.launch {
                            try {
                                listSync.get().resumePending()
                                lists.get().refreshIfStale()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    SocketMessage.Revoked -> coupleRepository.refreshMe()
                }
            }
        }
    }

    fun stop() { socketJob?.cancel(); socketJob = null }

    /**
     * Fetch and apply everything after the cursor. Returns the new incoming items.
     *
     * One sync at a time: the push handler, the expedited SyncWorker and the periodic worker share a
     * sync (their options merge), instead of fetching the same events side by side. A caller only
     * shares a sync that started at or after [notBefore] (the push's arrival), so it never settles
     * for one that fetched before its event existed (see [SingleFlight]).
     * With [notify], each new item's notification is posted as soon as its event is applied, before
     * the widgets are recomputed (see [runPagedSync]), so a caller's deadline or a later error never
     * loses one. With [fetchMedia], a new widget photo is downloaded after the sync, by this caller
     * and outside the shared run, so a slow photo never holds up the syncs of later pushes; the push
     * handler passes false and fetches it in [finishAfterPush] with what is left of its budget.
     */
    suspend fun syncOnce(
        notify: Boolean = false,
        fetchMedia: Boolean = true,
        notBefore: Long = System.currentTimeMillis(),
    ): AppResult<List<Incoming>> {
        if (sessionStore.current() == null) return AppResult.Success(emptyList())
        val result = flights.run(SyncOptions(notify), notBefore) { flight -> runSync(flight) }
        if (fetchMedia) {
            try {
                widgetSnapshotUpdater.completeMedia()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
        return result
    }

    private suspend fun runSync(flight: SingleFlight.Flight<SyncOptions>): AppResult<List<Incoming>> {
        val result = runPagedSync(
            since0 = sessionStore.lastEventSeq.first(),
            fetch = ::fetchPage,
            bootstrapMark = { latest -> sessionStore.setBootstrapUntilSeqIfFresh(latest); sessionStore.bootstrapUntilSeq.first() },
            currentMark = { sessionStore.bootstrapUntilSeq.first() },
            apply = { e, replay -> applyMutex.withLock { reducer.apply(e, replay) } },
            announce = { item -> if (flight.options.notify) notifier.notifyIncoming(item) },
            timeoutMs = FLIGHT_TIMEOUT_MS,
        )
        // Recompute even when nothing new arrived, or a page failed after others were applied: the
        // countdown's "days left" moves at midnight, and what was applied must reach the widgets.
        // Its own try: a widget or DataStore error must not fail a sync whose events (and
        // notifications) are already done. Local only; the photo is fetched outside the run.
        try {
            widgetSnapshotUpdater.recompute(fetchMedia = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
        result.failure?.let { return it }
        // Open on screen but the event came by push or catch-up sync (not the socket): the
        // notification is suppressed in the foreground, so buzz and reveal it in the app instead.
        // Risaina's own screens only: the SOS alarm over the lock screen shows no Home to reveal on.
        if (com.risaina.app.core.common.AppVisibility.isVisible) {
            val freshPing = result.items.filterIsInstance<Incoming.NewPing>().any { java.time.Duration.between(it.ping.createdAt, java.time.Instant.now()).seconds < 60 }
            if (freshPing) haptics.arrival()
            result.items.forEach { _incoming.tryEmit(it) }
        }
        return AppResult.Success(result.items)
    }

    /** Run the network follow-ups the reducer queued (receipts, game refresh). See [DeferredEffects]. */
    suspend fun runDeferred() = deferred.drain()

    /**
     * What the push handler does with the rest of its budget after the sync: download a new widget
     * photo (and redraw), then send the queued receipts and refreshes. Both run in the sync scope, so
     * the handler stops waiting on time even inside a non-cancellable token refresh; whatever is not
     * done carries on in the process, and the SyncWorker covers it if the process is frozen. A drain
     * the worker already runs is not waited for.
     */
    suspend fun finishAfterPush(budgetMs: Long) {
        if (budgetMs <= 0) return
        val work = syncScope.async {
            try {
                widgetSnapshotUpdater.completeMedia()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
            deferred.drain(wait = false)
        }
        try {
            withTimeoutOrNull(budgetMs) { work.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /**
     * Right after a Doze wake the network can lag for a moment: the first page is retried once after
     * a second instead of giving up on the whole sync. A page carries "this phone received a push"
     * for delivery health whenever one is waiting (no separate request inside the push handler's
     * window). Not only the first page: a sync that started a moment before the push was recorded
     * still delivers it on its next page.
     */
    private suspend fun fetchPage(since: Long, first: Boolean): AppResult<EventsResponse> {
        val ack = diagnostics.pendingAck()
        val headers = ack?.let { ackHeaders(it) }.orEmpty()
        suspend fun once() = apiCall { if (headers.isEmpty()) api.events(since = since, limit = 200) else api.eventsWithAck(since, 200, headers) }
        var r = once()
        if (first && r is AppResult.Failure && r.error.isRetryable) {
            delay(1_000)
            r = once()
        }
        if (r is AppResult.Success && ack != null) diagnostics.clearPendingAck(ack)
        return r
    }

    private suspend fun ackHeaders(ack: Pair<String, String>): Map<String, String> {
        val deviceId = sessionStore.current()?.deviceId?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val flags = runCatching { DeliveryHealthRules.flags(deviceState.read()) }.getOrDefault(emptyList())
        return mapOf(
            "X-Risaina-Device" to deviceId,
            "X-Risaina-Push" to "${ack.first}/${ack.second}",
            "X-Risaina-Health" to flags.joinToString(","),
        )
    }

    private companion object {
        /** A whole shared sync: ten pages normally take a few seconds; past this it is stuck. */
        const val FLIGHT_TIMEOUT_MS = 15_000L
        /** A sync running this long is taken as stuck: a new push starts its own (see SingleFlight). */
        const val FLIGHT_STALL_MS = 8_000L
    }
}
