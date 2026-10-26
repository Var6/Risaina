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
 * FCM entry point. Messages are data-only: `{type, eventId, coupleId, seq, pr}`, never content.
 *
 * Android gives this handler about ten seconds, and on realme/ColorOS the app is frozen or killed
 * the moment it returns, so everything that matters happens here, in this order:
 *  1. an SOS rings (unchanged, first);
 *  2. the safety-net SyncWorker is queued, inside FCM's short window for expedited work, so it
 *     survives even if the process dies below;
 *  3. network-free alerts for a ping or a message, so the phone buzzes within a second;
 *  4. a sync bounded by a budget; each event's notification is posted as soon as it is applied;
 *  5. the widgets are drawn directly (not queued), then a new photo is fetched and drawn again;
 *  6. what is left of the budget goes to receipts and the delivery-health ack.
 * The reducer de-duplicates by seq, so the worker and this handler never notify twice.
 */
@AndroidEntryPoint
class RisainaMessagingService : FirebaseMessagingService() {
    @Inject lateinit var notifier: RisainaNotifier
    @Inject lateinit var sosAlert: SosAlert
    @Inject lateinit var coupleDao: com.risaina.app.core.database.CoupleDao
    @Inject lateinit var syncManager: com.risaina.app.core.sync.EventSyncManager
    @Inject lateinit var widgetSnapshotUpdater: com.risaina.app.core.sync.WidgetSnapshotUpdater
    @Inject lateinit var diagnostics: DeliveryDiagnostics
    @Inject lateinit var healthReporter: DeliveryHealthReporter

    override fun onNewToken(token: String) {
        RegisterDeviceWorker.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "sos.raised") {
            val name = runCatching { runBlocking { coupleDao.partner()?.displayName } }.getOrNull() ?: "your partner"
            sosAlert.show(name, null, null)
        }
        val type = message.data["type"]
        val isTest = type == DeliveryDiagnostics.TEST_PUSH_TYPE
        if (!isTest) runCatching { SyncWorker.enqueueFromPush(applicationContext) }
        diagnostics.recordPush(type, message.priority, message.originalPriority, message.sentTime)
        val priority = DeliverySnapshot.priorityName(message.priority)
        val original = DeliverySnapshot.priorityName(message.originalPriority)
        runCatching {
            runBlocking {
                if (isTest) {
                    notifier.notifyTestPush(diagnostics.snapshot().lastPushLatencyMillis)
                    withTimeoutOrNull(ACK_BUDGET_MS) { healthReporter.ackPush(priority, original, force = true) }
                } else {
                    handle(type, priority, original)
                }
            }
        }
    }

    private suspend fun handle(type: String?, priority: String, original: String) {
        val start = SystemClock.elapsedRealtime()
        fun left() = TOTAL_BUDGET_MS - (SystemClock.elapsedRealtime() - start)

        when (type) {
            "ping.created" -> runCatching { notifier.notifyPingArrivedFast() }
            "message.created" -> runCatching { notifier.notifyMessageArrivedFast() }
        }

        val syncStart = SystemClock.elapsedRealtime()
        val result = withTimeoutOrNull(SYNC_BUDGET_MS) {
            syncManager.syncOnce(fetchMedia = false, onIncoming = { notifier.notifyIncoming(it) })
        }
        diagnostics.recordSync(
            when (result) {
                null -> "timeout"
                is AppResult.Success -> "ok"
                is AppResult.Failure -> "error: ${result.error::class.simpleName}"
            },
            SystemClock.elapsedRealtime() - syncStart,
        )
        // Out of time mid-sync: everything applied so far was already notified; draw it now.
        if (result == null && left() > 0) withTimeoutOrNull(left()) { widgetSnapshotUpdater.recompute(fetchMedia = false) }
        if (left() > MIN_STEP_MS) withTimeoutOrNull(left()) { widgetSnapshotUpdater.completeMedia(timeoutMs = left()) }
        if (left() > MIN_STEP_MS) withTimeoutOrNull(left()) { syncManager.runDeferred() }
        if (left() > MIN_STEP_MS) withTimeoutOrNull(left()) { healthReporter.ackPush(priority, original, force = false) }
    }

    private companion object {
        /** Whole handler. FCM allows roughly ten seconds before Android may stop the process. */
        const val TOTAL_BUDGET_MS = 9_000L
        /** Fetching and applying events, including the first widget draw. */
        const val SYNC_BUDGET_MS = 6_500L
        const val MIN_STEP_MS = 300L
        const val ACK_BUDGET_MS = 4_000L
    }
}
