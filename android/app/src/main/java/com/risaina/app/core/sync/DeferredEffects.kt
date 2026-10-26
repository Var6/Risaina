package com.risaina.app.core.sync

import android.content.Context
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.GameRepository
import com.risaina.app.core.data.PingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network follow-ups of applying an event (the "delivered" receipt for a ping, re-fetching a game,
 * re-loading the partner after pairing). They used to run inside the reducer, so in the push handler
 * they used up the few seconds Android gives it and held the apply lock before the widgets were
 * drawn. Now the reducer only queues them; callers run [drain] once notifications and widgets are
 * done (the push handler with what is left of its budget, the SyncWorker after it, the foreground
 * socket right away). The queue is persisted, so a phone frozen mid-way still sends the receipt
 * later. Every effect is idempotent on the server.
 */
@Singleton
class DeferredEffects @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pingRepository: PingRepository,
    private val gameRepository: GameRepository,
    private val coupleRepository: dagger.Lazy<CoupleRepository>,
) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val drainMutex = Mutex()
    private val lock = Any()

    fun ackDelivered(pingId: String) = add("$ACK$pingId")
    fun refreshGame(gameId: String) = add("$GAME$gameId")
    fun refreshMe() = add(ME)

    fun pending(): Set<String> = synchronized(lock) { prefs.getStringSet(K_QUEUE, emptySet()).orEmpty().toSet() }

    private fun add(item: String) = synchronized(lock) {
        val next = pending() + item
        prefs.edit().putStringSet(K_QUEUE, next).apply()
    }

    private fun remove(item: String) = synchronized(lock) {
        prefs.edit().putStringSet(K_QUEUE, pending() - item).apply()
    }

    /**
     * Runs everything queued. An item leaves the queue once it ran, or failed in a way a retry will
     * not fix; offline and server errors keep it for the next drain. Cancellation (the push handler's
     * time budget) leaves the rest queued.
     */
    suspend fun drain() = drainMutex.withLock {
        for (item in pending()) {
            val done = when {
                item.startsWith(ACK) -> pingRepository.acknowledgeDelivered(item.removePrefix(ACK)).isFinal()
                item.startsWith(GAME) -> { gameRepository.refreshOne(item.removePrefix(GAME)); true }
                item == ME -> coupleRepository.get().refreshMe().isFinal()
                else -> true
            }
            if (done) remove(item)
        }
    }

    // After sign-out or unlink a queued receipt fails with a non-retryable error and is dropped, so
    // the queue needs no explicit clearing.
    private fun AppResult<*>.isFinal(): Boolean = this !is AppResult.Failure || !error.isRetryable

    private companion object {
        const val PREFS = "deferred_effects"
        const val K_QUEUE = "queue"
        const val ACK = "ack:"
        const val GAME = "game:"
        const val ME = "me"
    }
}
