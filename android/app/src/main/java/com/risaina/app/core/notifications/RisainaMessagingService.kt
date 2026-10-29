package com.risaina.app.core.notifications

import android.os.SystemClock
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * FCM entry point. Messages are data-only: `{type, eventId, coupleId, seq, pr, ref?}`, never
 * content (`ref` is the opaque id of a message.created or ping.created item, so a quick
 * notification can be replaced in place, or skipped when an earlier sync already brought the item).
 *
 * Firebase gives this handler only a few seconds ("long enough to render a notification"), and
 * realme/ColorOS freezes or kills the app soon after it returns. So, in this order:
 *  1. an SOS rings (unchanged, first);
 *  2. "this phone received a push" is recorded (a cheap local write), before the worker below can
 *     start a sync whose first request should carry it to the server;
 *  3. the safety-net SyncWorker is queued, inside FCM's short exemption for expedited work, so it
 *     survives even if the process dies below;
 *  4. notification first: network-free alerts for a ping or a message, unless this phone already
 *     handled this very push (HandledPushes);
 *  5. a sync bounded by a short budget; each event's notification is posted as it is applied, and
 *     the widgets are drawn directly at the end (not queued). The sync is shared with the worker
 *     and keeps running if this handler stops waiting;
 *  6. with whatever is left of the budget: a new widget photo, then the queued receipts and game
 *     refreshes. The worker finishes anything that does not fit.
 * The reducer de-duplicates by seq, so the worker and this handler never notify twice.
 */
@AndroidEntryPoint
class RisainaMessagingService : FirebaseMessagingService() {
    @Inject lateinit var notifier: RisainaNotifier
    @Inject lateinit var sosAlert: SosAlert
    @Inject lateinit var coupleDao: com.risaina.app.core.database.CoupleDao
    @Inject lateinit var syncManager: com.risaina.app.core.sync.EventSyncManager
    @Inject lateinit var diagnostics: DeliveryDiagnostics
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
            // The sender is safe: stop that alarm now, without waiting for a sync that a slow or
            // unreachable server could hold up for the whole ring. Only this SOS (a newer open one
            // keeps ringing); the sync still posts "safe now" when it applies the event.
            "sos.resolved" -> message.data["sosId"]?.takeIf { it.isNotBlank() }?.let { id ->
                runCatching { sosLocalState.markResolved(id) }
                sosAlarm.stopFor(id, null)
            }
        }
        // A sync that began before this moment cannot contain this push's event (see SingleFlight).
        val receivedAt = System.currentTimeMillis()
        val type = message.data["type"]
        val isTest = type == DeliveryDiagnostics.TEST_PUSH_TYPE
        // The same push twice (an FCM redelivery, a stale second token, an outbox resend): no second
        // quick alert. It is still acknowledged and still syncs, which costs nothing. After the SOS
        // above, which has its own de-duplication by SOS id.
        val firstTime = runCatching { diagnostics.firstTimePush(message.data["eventId"]) }.getOrDefault(true)
        // Also queues "this phone received a push", which rides on the next sync request. Before the
        // worker is queued: WorkManager starts it in this process at once, and its first request
        // must not leave without the ack (the handler would then share that sync and the ack would
        // wait for the next one, maybe the hourly sync).
        runCatching { diagnostics.recordPush(type, message.priority, message.originalPriority, message.sentTime) }
        if (!isTest) runCatching { SyncWorker.enqueueFromPush(applicationContext) }
        val coupleId = message.data["coupleId"]?.takeIf { it.isNotBlank() }
        runCatching { runBlocking { handle(type, message.data["ref"]?.takeIf { it.isNotBlank() }, coupleId, isTest, firstTime, receivedAt) } }
    }

    private suspend fun handle(type: String?, ref: String?, coupleId: String?, isTest: Boolean, firstTime: Boolean, receivedAt: Long) {
        when {
            isTest -> notifier.notifyTestPush(diagnostics.snapshot().lastPushLatencyMillis)
            !firstTime -> Unit
            type == "ping.created" -> runCatching { notifier.notifyPingArrivedFast(ref, coupleId) }
            // Without the message id (an older server) the quick alert could not be replaced, so skip it.
            type == "message.created" && ref != null -> runCatching { notifier.notifyMessageArrivedFast(ref, coupleId) }
        }
        val start = SystemClock.elapsedRealtime()
        val result = withTimeoutOrNull(BUDGET_MS) {
            syncManager.syncOnce(notify = true, fetchMedia = false, notBefore = receivedAt)
        }
        val took = SystemClock.elapsedRealtime() - start
        diagnostics.recordSync(
            when (result) {
                null -> "timeout"
                is AppResult.Success -> "ok"
                is AppResult.Failure -> "error: ${result.error::class.simpleName}"
            },
            took,
        )
        // Only after a sync that worked (a failed one means no network for these either), and not
        // with a sliver of time left: the worker does it anyway.
        val left = BUDGET_MS - took
        if (result is AppResult.Success && left >= MIN_LEFTOVER_MS) syncManager.finishAfterPush(left)
    }

    private companion object {
        /**
         * How long this handler spends after the quick alert: the shared sync (fetch, apply, notify,
         * draw), then the photo and receipts if time is left. With cold start that keeps the handler
         * near 4-5 s; the work itself carries on in the process if it needs longer, and the worker
         * finishes what remains.
         */
        const val BUDGET_MS = 4_000L
        const val MIN_LEFTOVER_MS = 500L
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
