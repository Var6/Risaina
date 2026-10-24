package com.risaina.app.core.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.risaina.app.core.sync.SyncWorker

/**
 * FCM entry point. Messages are data-only and carry no content: `{type, eventId, coupleId, seq}`.
 * We never trust or display the payload; we schedule a sync that fetches authoritative events
 * from the backend and lets [RisainaNotifier] decide what to show.
 */
class RisainaMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        RegisterDeviceWorker.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Payload intentionally not logged.
        SyncWorker.enqueueFromPush(applicationContext)
    }
}
