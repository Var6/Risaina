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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    private val applyMutex = Mutex()
    private var socketJob: Job? = null

    /** Shared syncs run here, not in a caller that may give up (see [SingleFlight]). */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flights = SingleFlight<SyncOptions, AppResult<List<Incoming>>>(syncScope) { a, b ->
        SyncOptions(notify = a.notify || b.notify, fetchMedia = a.fetchMedia || b.fetchMedia)
    }

    /** [notify]: post notifications for new items. [fetchMedia]: download a new widget photo too. */
    data class SyncOptions(val notify: Boolean, val fetchMedia: Boolean)

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
     * One sync at a time: the push handler, the expedited SyncWorker and the periodic worker share a
     * sync (their options merge), instead of fetching the same events side by side. A caller only
     * shares a sync that started at or after [notBefore] (the push's arrival), so it never settles
     * for one that fetched before its event existed (see [SingleFlight]).
     * With [notify], each new item's notification is posted as soon as its event is applied, before
     * the widgets are recomputed (see [runPagedSync]), so a caller's deadline or a later error never
     * loses one. [fetchMedia] = false leaves a new photo's download to a later sync (the worker), so
     * text, pings and gifts reach the widgets first.
     */
    suspend fun syncOnce(
        notify: Boolean = false,
        fetchMedia: Boolean = true,
        notBefore: Long = System.currentTimeMillis(),
    ): AppResult<List<Incoming>> {
        if (sessionStore.current() == null) return AppResult.Success(emptyList())
        return flights.run(SyncOptions(notify, fetchMedia), notBefore) { flight -> runSync(flight) }
    }

    private suspend fun runSync(flight: SingleFlight.Flight<SyncOptions>): AppResult<List<Incoming>> {
        val result = runPagedSync(
            since0 = sessionStore.lastEventSeq.first(),
            fetch = ::fetchPage,
            bootstrapMark = { latest -> sessionStore.setBootstrapUntilSeqIfFresh(latest); sessionStore.bootstrapUntilSeq.first() },
            currentMark = { sessionStore.bootstrapUntilSeq.first() },
            apply = { e, replay -> applyMutex.withLock { reducer.apply(e, replay) } },
            announce = { item -> if (flight.options.notify) notifier.notifyIncoming(item) },
        )
        // Recompute even when nothing new arrived, or a page failed after others were applied: the
        // countdown's "days left" moves at midnight, and what was applied must reach the widgets.
        // Its own try: a widget, DataStore or download error must not fail a sync whose events (and
        // notifications) are already done.
        try {
            widgetSnapshotUpdater.recompute(fetchMedia = flight.options.fetchMedia)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
        result.failure?.let { return it }
        // Open on screen but the event came by push or catch-up sync (not the socket): the
        // notification is suppressed in the foreground, so buzz and reveal it in the app instead.
        if (androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            val freshPing = result.items.filterIsInstance<Incoming.NewPing>().any { java.time.Duration.between(it.ping.createdAt, java.time.Instant.now()).seconds < 60 }
            if (freshPing) haptics.arrival()
            result.items.forEach { _incoming.tryEmit(it) }
        }
        return AppResult.Success(result.items)
    }

    /** Run the network follow-ups the reducer queued (receipts, game refresh). See [DeferredEffects]. */
    suspend fun runDeferred() = deferred.drain()

    /**
     * Right after a Doze wake the network can lag for a moment: the first page is retried once after
     * a second instead of giving up on the whole sync. The first page also carries "this phone
     * received a push" for delivery health (no separate request inside the push handler's window).
     */
    private suspend fun fetchPage(since: Long, first: Boolean): AppResult<EventsResponse> {
        val ack = if (first) diagnostics.pendingAck() else null
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
}
