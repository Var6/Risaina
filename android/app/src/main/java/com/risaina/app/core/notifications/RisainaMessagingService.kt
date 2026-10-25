package com.risaina.app.core.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.risaina.app.core.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
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

    override fun onNewToken(token: String) {
        RegisterDeviceWorker.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "sos.raised") {
            val name = runCatching { runBlocking { coupleDao.partner()?.displayName } }.getOrNull() ?: "your partner"
            sosAlert.show(name, null, null)
        }
        if (message.data["type"] == "ping.created") {
            runCatching { runBlocking { notifier.notifyPingArrivedFast() } }
        }
        SyncWorker.enqueueFromPush(applicationContext)
    }
}
