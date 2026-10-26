package com.risaina.app.core.sync

import android.content.Context
import androidx.core.content.edit
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.GameRepository
import com.risaina.app.core.data.PingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network follow-ups of applying an event (the "delivered" receipt for a ping, re-fetching a game,
 * re-loading the partner after pairing). They used to run inside the reducer, so in the push handler
 * they used up the few seconds Android gives it and held the apply lock before the widgets were
 * drawn. Now the reducer only queues them; callers run [drain] once notifications and widgets are
 * done (the push handler with what is left of its budget, the SyncWorker after it, the foreground
 * socket once it has caught up). The queue is persisted, so a phone frozen mid-way still sends the
 * receipt later. Every effect is idempotent on the server.
 */
@Singleton
class DeferredEffects @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pingRepository: PingRepository,
    private val gameRepository: GameRepository,
    private val coupleRepository: dagger.Lazy<CoupleRepository>,
) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val queue = DeferredQueue(object : DeferredQueue.Storage {
        override fun items(): Set<String> = prefs.getStringSet(K_QUEUE, emptySet()).orEmpty().toSet()
        override fun saveItems(items: Set<String>) = prefs.edit { putStringSet(K_QUEUE, items) }
        override fun failures(item: String): Int = prefs.getInt(K_FAILS + item, 0)
        override fun saveFailures(item: String, count: Int) = prefs.edit {
            if (count <= 0) remove(K_FAILS + item) else putInt(K_FAILS + item, count)
        }
    })

    fun ackDelivered(pingId: String) = queue.add("$ACK$pingId")
    fun refreshGame(gameId: String) = queue.add("$GAME$gameId")
    fun refreshMe() = queue.add(ME)

    fun pending(): Set<String> = queue.pending()

    /**
     * Runs everything queued (see [DeferredQueue.drain]). With [wait] = false it returns at once when
     * another drain is already under way, instead of queueing behind its network calls.
     * Cancellation (the push handler's time budget) leaves the rest queued.
     */
    suspend fun drain(wait: Boolean = true) = queue.drain(wait) { item ->
        try {
            when {
                item.startsWith(ACK) -> pingRepository.acknowledgeDelivered(item.removePrefix(ACK)).outcome()
                item.startsWith(GAME) -> { gameRepository.refreshOne(item.removePrefix(GAME)); EffectOutcome.DONE }
                item == ME -> coupleRepository.get().refreshMe().outcome()
                else -> EffectOutcome.DONE
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            EffectOutcome.FAILED
        }
    }

    // After sign-out or unlink a queued receipt fails with a non-retryable error and is dropped, so
    // the queue needs no explicit clearing.
    private fun AppResult<*>.outcome(): EffectOutcome = when {
        this !is AppResult.Failure || !error.isRetryable -> EffectOutcome.DONE
        error is AppError.Offline -> EffectOutcome.OFFLINE
        else -> EffectOutcome.FAILED
    }

    private companion object {
        const val PREFS = "deferred_effects"
        const val K_QUEUE = "queue"
        const val K_FAILS = "fails:"
        const val ACK = "ack:"
        const val GAME = "game:"
        const val ME = "me"
    }
}

/** How one run of a queued effect went. */
enum class EffectOutcome {
    /** Done, or failed in a way a retry will not fix: it leaves the queue. */
    DONE,
    /** No network: it stays, and this does not count against the item. */
    OFFLINE,
    /** Server error, rate limit or timeout: it stays, but only for [DeferredQueue.MAX_FAILURES] runs. */
    FAILED,
}

/**
 * The queue behind [DeferredEffects], with its storage passed in so it is unit-tested
 * (DeferredQueueTest).
 *
 * - An item queued again while it runs (a second game move while the first refresh is in flight)
 *   runs again: the first run may have fetched the state before the newer event.
 * - Each item runs at most once per drain otherwise, so an item that keeps failing cannot spin.
 * - An item that fails on the server side [MAX_FAILURES] times is dropped (a receipt the server keeps
 *   refusing would otherwise be retried on every sync forever). Offline runs do not count.
 */
class DeferredQueue(private val storage: Storage) {
    interface Storage {
        fun items(): Set<String>
        fun saveItems(items: Set<String>)
        fun failures(item: String): Int
        /** 0 forgets the count. */
        fun saveFailures(item: String, count: Int)
    }

    private val lock = Any()
    private val drainMutex = Mutex()
    /** The item being run right now, and whether it was queued again meanwhile. Guarded by [lock]. */
    private var running: String? = null
    private var requeued = false

    fun add(item: String) = synchronized(lock) {
        storage.saveItems(storage.items() + item)
        if (item == running) requeued = true
    }

    fun pending(): Set<String> = synchronized(lock) { storage.items() }

    /**
     * Runs what is queued, including items added while it runs. With [wait] = false it returns at
     * once if another drain is under way (that drain picks up anything added meanwhile).
     */
    suspend fun drain(wait: Boolean = true, run: suspend (String) -> EffectOutcome) {
        if (wait) drainMutex.lock() else if (!drainMutex.tryLock()) return
        try {
            val tried = mutableSetOf<String>()
            while (true) {
                val item = synchronized(lock) {
                    storage.items().firstOrNull { it !in tried }?.also { running = it; requeued = false }
                } ?: return
                tried += item
                var outcome: EffectOutcome? = null
                try {
                    outcome = run(item)
                } finally {
                    // Also on cancellation or an exception (outcome null): the item then stays queued.
                    synchronized(lock) {
                        val again = requeued
                        running = null
                        requeued = false
                        when {
                            outcome == null -> Unit
                            again -> tried -= item
                            outcome == EffectOutcome.DONE -> forget(item)
                            outcome == EffectOutcome.FAILED -> {
                                val n = storage.failures(item) + 1
                                if (n >= MAX_FAILURES) forget(item) else storage.saveFailures(item, n)
                            }
                            else -> Unit // OFFLINE: keep, uncounted
                        }
                    }
                }
            }
        } finally {
            drainMutex.unlock()
        }
    }

    private fun forget(item: String) {
        storage.saveItems(storage.items() - item)
        storage.saveFailures(item, 0)
    }

    companion object {
        const val MAX_FAILURES = 5
    }
}
