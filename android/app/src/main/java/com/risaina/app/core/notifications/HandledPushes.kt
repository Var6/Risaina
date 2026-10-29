package com.risaina.app.core.notifications

/**
 * The last [MAX] pushes this phone handled, by eventId, oldest first. FCM can deliver a push twice
 * (a redelivery, a second token of this phone, an outbox resend): the repeat then posts no second
 * quick alert. Pure, with the stored form ("id,id,…") passed in and out (HandledPushesTest);
 * DeliveryDiagnostics keeps it in SharedPreferences.
 */
object HandledPushes {
    const val MAX = 64

    /** Whether [eventId] is new, and the list to store. A blank or missing id always counts as new and is not kept. */
    fun record(stored: String?, eventId: String?): Pair<Boolean, String?> {
        val id = eventId?.trim()
        if (id.isNullOrEmpty() || ',' in id) return true to stored
        val ids = stored.orEmpty().split(',').filter { it.isNotEmpty() }
        if (id in ids) return false to stored
        return true to (ids + id).takeLast(MAX).joinToString(",")
    }
}
