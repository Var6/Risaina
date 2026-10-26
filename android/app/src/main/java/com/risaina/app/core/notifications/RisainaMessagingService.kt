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
 *  2. the safety-net SyncWorker is queued, inside FCM's short exemption for expedited work, so it
 *     survives even if the process dies below;
 *  3. notification first: network-free alerts for a ping or a message;
 *  4. a sync bounded by a short budget; each event's notification is posted as it is applied, and
 *     the widgets are drawn directly at the end (not queued). The sync is shared with the worker
 *     and keeps running if this handler stops waiting; the photo download, receipts and game
 *     refreshes are left to the worker.
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
        if (!isTest) runCatching { SyncWorker.enqueueFromPush(applicationContext) }
        // Also queues "this phone received a push", which rides on the sync's first request.
        diagnostics.recordPush(type, message.priority, message.originalPriority, message.sentTime)
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
        val result = withTimeoutOrNull(SYNC_BUDGET_MS) {
            syncManager.syncOnce(notify = true, fetchMedia = false, notBefore = receivedAt)
        }
        diagnostics.recordSync(
            when (result) {
                null -> "timeout"
                is AppResult.Success -> "ok"
                is AppResult.Failure -> "error: ${result.error::class.simpleName}"
            },
            SystemClock.elapsedRealtime() - start,
        )
    }

    private companion object {
        /**
         * How long this handler waits for the shared sync (fetch, apply, notify, draw). With cold start
         * that keeps the handler near 4-5 s; the sync itself carries on in the process if it needs
         * longer, and the worker finishes what remains.
         */
        const val SYNC_BUDGET_MS = 4_000L
    }
}
