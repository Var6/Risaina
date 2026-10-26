package com.risaina.app.core.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.risaina.app.core.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * FCM entry point. Messages are data-only: `{type, eventId, coupleId, seq}`, never content.
 * A ping alerts immediately (onMessageReceived has ~10 s, and this needs no network); then a sync
 * fetches the authoritative event and updates the same notification without buzzing twice.
 */
@AndroidEntryPoint
class RisainaMessagingService : FirebaseMessagingService() {
    @Inject lateinit var notifier: RisainaNotifier
    @Inject lateinit var sosAlert: SosAlert
    @Inject lateinit var coupleDao: com.risaina.app.core.database.CoupleDao
    @Inject lateinit var syncManager: com.risaina.app.core.sync.EventSyncManager
    @Inject lateinit var sosAlarm: com.risaina.app.core.sos.SosAlarmController
    @Inject lateinit var sosLocalState: com.risaina.app.core.sos.SosLocalState
    @Inject lateinit var sessionStore: com.risaina.app.core.datastore.SessionStore

    override fun onNewToken(token: String) {
        RegisterDeviceWorker.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // SOS first, before any database or network work: the high-priority message allows starting
        // the alarm's foreground service for only a few seconds.
        when (message.data["type"]) {
            "sos.raised" -> ringSos(message)
            "sos.test" -> sosAlarm.ringTest()
        }
        if (message.data["type"] == "ping.created") {
            runCatching { runBlocking { notifier.notifyPingArrivedFast() } }
        }
        // Fetch the event right here (high-priority pushes get ~10 s) so widgets and the lock-screen
        // card change within seconds, even if Android postpones background jobs. The worker below is
        // the safety net; the reducer de-duplicates by seq, so nothing is applied or notified twice.
        runCatching {
            runBlocking {
                val r = withTimeoutOrNull(8_000) { syncManager.syncOnce() }
                if (r is com.risaina.app.core.common.AppResult.Success) r.value.forEach { notifier.notifyIncoming(it) }
            }
        }
        SyncWorker.enqueueFromPush(applicationContext)
    }

    /**
     * The push carries no content: the alarm rings generically and the sync that follows fills in the
     * name and message. Rings unless this phone already handled this SOS, it is older than 6 hours, or
     * (for the outbox's push, which has no SOS id) the sync already applied the event.
     */
    private fun ringSos(message: RemoteMessage) {
        val data = message.data
        val seq = data["seq"]?.toLongOrNull()
        val sosId = data["sosId"]
        val cursor = runCatching { runBlocking { withTimeoutOrNull(400) { sessionStore.lastEventSeq.first() } } }.getOrNull()
        val ring = com.risaina.app.core.sos.SosRingPolicy.forPush(
            seq = seq, cursor = cursor, sosId = sosId,
            handledLocally = sosLocalState.isHandled(sosId, seq),
            createdAtMs = data["at"]?.toLongOrNull(), sentAtMs = message.sentTime, nowMs = System.currentTimeMillis(),
        )
        if (!ring) return
        sosAlarm.ring(
            com.risaina.app.core.sos.RingRequest(
                sosId = sosId, seq = seq, source = com.risaina.app.core.sos.RingSource.PUSH,
                pushPriority = priorityName(message.priority), pushOriginalPriority = priorityName(message.originalPriority),
                latencyMs = message.sentTime.takeIf { it > 0 }?.let { System.currentTimeMillis() - it },
            ),
        )
    }

    private fun priorityName(p: Int): String = when (p) {
        RemoteMessage.PRIORITY_HIGH -> "high"
        RemoteMessage.PRIORITY_NORMAL -> "normal"
        else -> "unknown"
    }
}
