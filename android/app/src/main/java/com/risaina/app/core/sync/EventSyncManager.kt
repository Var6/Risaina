package com.risaina.app.core.sync

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.network.EventSocket
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SocketMessage
import com.risaina.app.core.network.SocketState
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * [syncOnce] (invoked by SyncWorker after an FCM data message or on a periodic schedule).
 */
@Singleton
class EventSyncManager @Inject constructor(
    private val socket: EventSocket,
    private val api: RisainaApi,
    private val reducer: EventReducer,
    private val sessionStore: SessionStore,
    private val coupleRepository: CoupleRepository,
    private val widgetSnapshotUpdater: WidgetSnapshotUpdater,
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
                    is SocketMessage.Ready -> Unit
                    is SocketMessage.Event -> {
                        val results = applyMutex.withLock { reducer.apply(msg.event) }
                        if (results.isNotEmpty()) {
                            results.forEach { _incoming.tryEmit(it) }
                            widgetSnapshotUpdater.recompute()
                        }
                    }
                    SocketMessage.Synced -> widgetSnapshotUpdater.recompute()
                    SocketMessage.Revoked -> coupleRepository.refreshMe()
                }
            }
        }
    }

    fun stop() { socketJob?.cancel(); socketJob = null }

    /** Fetch and apply everything after the cursor. Returns the new incoming items. */
    suspend fun syncOnce(): AppResult<List<Incoming>> {
        if (sessionStore.current() == null) return AppResult.Success(emptyList())
        val collected = mutableListOf<Incoming>()
        var since = sessionStore.lastEventSeq.first()
        var changed = false
        repeat(10) {
            val page = when (val r = apiCall { api.events(since = since, limit = 200) }) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return r
            }
            if (page.items.isEmpty()) return@repeat
            for (e in page.items) {
                collected += applyMutex.withLock { reducer.apply(e) }
                changed = true
            }
            since = page.items.maxOf { it.seq }
            if (since >= page.latestSeq) return@repeat
        }
        if (changed) widgetSnapshotUpdater.recompute()
        return AppResult.Success(collected)
    }
}
