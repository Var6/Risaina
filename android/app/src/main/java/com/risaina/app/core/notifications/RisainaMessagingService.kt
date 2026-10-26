package com.risaina.app.core.notifications

import android.os.SystemClock
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
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
 *  4. notification first: network-free alerts for a ping or a message;
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

    override fun onNewToken(token: String) {
        RegisterDeviceWorker.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "sos.raised") {
            val name = runCatching { runBlocking { coupleDao.partner()?.displayName } }.getOrNull() ?: "your partner"
            sosAlert.show(name, null, null)
        }
        // A sync that began before this moment cannot contain this push's event (see SingleFlight).
        val receivedAt = System.currentTimeMillis()
        val type = message.data["type"]
        val isTest = type == DeliveryDiagnostics.TEST_PUSH_TYPE
        // Also queues "this phone received a push", which rides on the next sync request. Before the
        // worker is queued: WorkManager starts it in this process at once, and its first request
        // must not leave without the ack (the handler would then share that sync and the ack would
        // wait for the next one, maybe the hourly sync).
        runCatching { diagnostics.recordPush(type, message.priority, message.originalPriority, message.sentTime) }
        if (!isTest) runCatching { SyncWorker.enqueueFromPush(applicationContext) }
        runCatching { runBlocking { handle(type, message.data["ref"]?.takeIf { it.isNotBlank() }, isTest, receivedAt) } }
    }

    private suspend fun handle(type: String?, ref: String?, isTest: Boolean, receivedAt: Long) {
        when {
            isTest -> notifier.notifyTestPush(diagnostics.snapshot().lastPushLatencyMillis)
            type == "ping.created" -> runCatching { notifier.notifyPingArrivedFast(ref) }
            // Without the message id (an older server) the quick alert could not be replaced, so skip it.
            type == "message.created" && ref != null -> runCatching { notifier.notifyMessageArrivedFast(ref) }
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
}
