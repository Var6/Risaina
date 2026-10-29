package com.risaina.app.core.sync

/**
 * Keeps a full replay (fresh sign-in, re-pairing, reinstall) from announcing old things as new.
 *
 * When a phone starts from event cursor 0 it receives the couple's whole history, through the
 * catch-up sync or the foreground socket. That history is applied, but its pings, messages and game
 * nudges must not post notifications, buzz, or pop the in-app reveal: an old gift would read as a
 * new one. The cut-off is the server's `latestSeq` when the replay began, persisted as a high-water
 * mark (SessionStore.bootstrapUntilSeq), so it holds across the 10-page sync limit, process deaths
 * and both delivery paths, and needs no comparison of the phone's clock with server timestamps.
 */
object ReplayGuard {
    fun isReplay(seq: Long, bootstrapUntilSeq: Long): Boolean = seq <= bootstrapUntilSeq

    /**
     * What may be announced for the event with [seq]. A replayed event keeps only items that are not
     * "something new arrived": an unlink, and an SOS, which has its own safety rules (SosAlert).
     */
    fun announceable(results: List<Incoming>, seq: Long, bootstrapUntilSeq: Long): List<Incoming> =
        if (!isReplay(seq, bootstrapUntilSeq)) results
        else results.filterNot { it is Incoming.NewPing || it is Incoming.NewMessage || it is Incoming.Game || it is Incoming.WidgetChanged || it is Incoming.CalendarShared }
}
