package com.risaina.app.core.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What this phone last saw of pushes, syncs and widget redraws. Shown in "Delivery health". */
data class DeliverySnapshot(
    val lastPushAt: Long? = null,
    val lastPushType: String? = null,
    /** Priority the push arrived with, and the one it was sent with: they differ when FCM downgraded it. */
    val lastPushPriority: String? = null,
    val lastPushOriginalPriority: String? = null,
    /** FCM's send time of that push, to show how long delivery took. */
    val lastPushSentAt: Long? = null,
    val lastSyncAt: Long? = null,
    val lastSyncResult: String? = null,
    val lastSyncMillis: Long? = null,
    val lastWidgetDrawAt: Long? = null,
    /** Widgets drawn directly, and how many fell back to Glance's queued update. */
    val lastWidgetDrawn: Int = 0,
    val lastWidgetFallbacks: Int = 0,
    val lastWorkerRunAt: Long? = null,
    val lastTestPushAt: Long? = null,
) {
    /** Sent high, arrived normal: FCM has started to deprioritise this phone. */
    val downgraded: Boolean get() = lastPushOriginalPriority == PRIORITY_HIGH && lastPushPriority == PRIORITY_NORMAL

    /** How long the last push took to arrive, or null when either time is unknown or the clocks disagree. */
    val lastPushLatencyMillis: Long?
        get() {
            val at = lastPushAt ?: return null
            val sent = lastPushSentAt?.takeIf { it > 0 } ?: return null
            return (at - sent).takeIf { it >= 0 }
        }

    companion object {
        const val PRIORITY_HIGH = "high"
        const val PRIORITY_NORMAL = "normal"
        const val PRIORITY_UNKNOWN = "unknown"

        /** RemoteMessage.PRIORITY_HIGH = 1, PRIORITY_NORMAL = 2, PRIORITY_UNKNOWN = 0. */
        fun priorityName(fcmPriority: Int): String = when (fcmPriority) {
            1 -> PRIORITY_HIGH
            2 -> PRIORITY_NORMAL
            else -> PRIORITY_UNKNOWN
        }
    }
}

/** When a detailed notification follows the quick one posted from its push. Pure (FastAlertsTest). */
object FastAlerts {
    /** Long enough for a slow sync or the SyncWorker after a Doze wake; short enough to never mute a new item. */
    const val WINDOW_MS = 2 * 60_000L

    /**
     * True when the quick alert at [fastAt] (0 = none) was for [ref] (or for an unknown item, from
     * an older server) and is under [WINDOW_MS] old: the detailed post must then not alert again.
     */
    fun follows(fastAt: Long, fastRef: String?, ref: String, now: Long): Boolean =
        fastAt > 0 && now - fastAt in 0 until WINDOW_MS && (fastRef == null || fastRef == ref)
}

/**
 * Small, synchronous diagnostics store. SharedPreferences rather than DataStore on purpose: the push
 * handler writes here first thing, on its own thread, and the expedited SyncWorker may run in a
 * fresh process right after, so writes must be cheap and visible at once. Nothing private is
 * stored: event types, priorities and times only.
 */
@Singleton
class DeliveryDiagnostics @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordPush(type: String?, priority: Int, originalPriority: Int, sentTime: Long, now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(K_PUSH_AT, now)
            .putString(K_PUSH_TYPE, type)
            .putString(K_PUSH_PRIORITY, DeliverySnapshot.priorityName(priority))
            .putString(K_PUSH_ORIGINAL, DeliverySnapshot.priorityName(originalPriority))
            .putLong(K_PUSH_SENT, sentTime)
            .apply { if (type == TEST_PUSH_TYPE) putLong(K_TEST_AT, now) }
            .apply {
                val p = DeliverySnapshot.priorityName(priority)
                val o = DeliverySnapshot.priorityName(originalPriority)
                if (p != DeliverySnapshot.PRIORITY_UNKNOWN && o != DeliverySnapshot.PRIORITY_UNKNOWN) putString(K_ACK_PRIORITY, p).putString(K_ACK_ORIGINAL, o)
            }
            .apply()
    }

    /**
     * "This phone received a push", waiting to ride on the next GET /events (the sync that the push
     * starts anyway), so no extra request is made inside the push handler's few seconds.
     */
    fun pendingAck(): Pair<String, String>? {
        val p = prefs
        val priority = p.getString(K_ACK_PRIORITY, null) ?: return null
        val original = p.getString(K_ACK_ORIGINAL, null) ?: return null
        return priority to original
    }

    fun clearPendingAck(sent: Pair<String, String>) {
        // Only if no newer push replaced it while the request was in flight.
        if (pendingAck() == sent) prefs.edit().remove(K_ACK_PRIORITY).remove(K_ACK_ORIGINAL).apply()
    }

    fun recordSync(result: String, millis: Long, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(K_SYNC_AT, now).putString(K_SYNC_RESULT, result).putLong(K_SYNC_MS, millis).apply()
    }

    fun recordWidgetDraw(drawn: Int, fallbacks: Int, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(K_DRAW_AT, now).putInt(K_DRAWN, drawn).putInt(K_FALLBACKS, fallbacks).apply()
    }

    fun recordWorkerRun(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(K_WORKER_AT, now).apply()
    }

    /**
     * When a generic alert ("is thinking of you", "sent you a message") was posted straight from a
     * push, before any network, and for which item ([ref]: the ping or message id, null when the
     * push carried none). The detailed notification for that item uses it to avoid buzzing a second
     * time (see RisainaNotifier). Persisted because the SyncWorker may post it in a new process.
     */
    fun recordFastAlert(kind: String, ref: String?, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(K_FAST_PREFIX + kind, now).putString(K_FAST_REF_PREFIX + kind, ref).apply()
    }

    /** The last quick alert of this kind: when (0 = never) and for which item (null = unknown). */
    fun lastFastAlert(kind: String): Pair<Long, String?> =
        prefs.getLong(K_FAST_PREFIX + kind, 0L) to prefs.getString(K_FAST_REF_PREFIX + kind, null)

    fun snapshot(): DeliverySnapshot {
        val p = prefs
        fun long(k: String): Long? = if (p.contains(k)) p.getLong(k, 0L) else null
        return DeliverySnapshot(
            lastPushAt = long(K_PUSH_AT),
            lastPushType = p.getString(K_PUSH_TYPE, null),
            lastPushPriority = p.getString(K_PUSH_PRIORITY, null),
            lastPushOriginalPriority = p.getString(K_PUSH_ORIGINAL, null),
            lastPushSentAt = long(K_PUSH_SENT),
            lastSyncAt = long(K_SYNC_AT),
            lastSyncResult = p.getString(K_SYNC_RESULT, null),
            lastSyncMillis = long(K_SYNC_MS),
            lastWidgetDrawAt = long(K_DRAW_AT),
            lastWidgetDrawn = p.getInt(K_DRAWN, 0),
            lastWidgetFallbacks = p.getInt(K_FALLBACKS, 0),
            lastWorkerRunAt = long(K_WORKER_AT),
            lastTestPushAt = long(K_TEST_AT),
        )
    }

    companion object {
        const val TEST_PUSH_TYPE = "test.push"
        private const val PREFS = "delivery_diagnostics"
        private const val K_PUSH_AT = "push_at"
        private const val K_PUSH_TYPE = "push_type"
        private const val K_PUSH_PRIORITY = "push_priority"
        private const val K_PUSH_ORIGINAL = "push_original_priority"
        private const val K_PUSH_SENT = "push_sent_at"
        private const val K_SYNC_AT = "sync_at"
        private const val K_SYNC_RESULT = "sync_result"
        private const val K_SYNC_MS = "sync_ms"
        private const val K_DRAW_AT = "draw_at"
        private const val K_DRAWN = "draw_count"
        private const val K_FALLBACKS = "draw_fallbacks"
        private const val K_WORKER_AT = "worker_at"
        private const val K_TEST_AT = "test_push_at"
        private const val K_FAST_PREFIX = "fast_alert_at_"
        private const val K_FAST_REF_PREFIX = "fast_alert_ref_"
        private const val K_ACK_PRIORITY = "ack_priority"
        private const val K_ACK_ORIGINAL = "ack_original_priority"
    }
}
