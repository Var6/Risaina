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
 * re-loading the partner after pairing, re-checking the partner's live location). They used to run
 * inside the reducer, so in the push handler they used up the few seconds Android gives it and held
 * the apply lock before the widgets were drawn. Now the reducer only queues them; callers run [drain] once notifications and widgets are
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
    private val liveRepo: com.risaina.app.core.location.LiveLocationRepository,
    private val safetyNotifier: com.risaina.app.core.safety.SafetyNotifier,
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
    /** Games marked stale by events (GameRepository.onEvent): fetched once each, or as one list after a replay. */
    fun refreshStaleGames(full: Boolean) = queue.add(if (full) GAMES_FULL else GAMES_STALE)
    fun refreshMe() = queue.add(ME)
    /**
     * "Is the partner still sharing?" asked of the server after "is sharing" was posted from the
     * event (an old start whose stop is still on its way, or a share purged without an event, must
     * not keep saying so). A GET in the reducer would hold the apply lock in the push handler.
     */
    fun checkPartnerLocation() = queue.add(LOCATION_CHECK)

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
                // Done only once no stale game is left: a fetch that failed (timeout, server waking up)
                // keeps the job queued for the next drain, or the card would show the old board
                // until another game event came.
                item == GAMES_STALE -> gameRepository.flushStale().outcome()
                item == GAMES_FULL -> gameRepository.flushStale(full = true).outcome()
                item.startsWith(GAME) -> { gameRepository.refreshOne(item.removePrefix(GAME)); EffectOutcome.DONE }
                item == ME -> coupleRepository.get().refreshMe().outcome()
                item == LOCATION_CHECK -> liveRepo.refresh().also { r ->
                    if (r is AppResult.Success && liveRepo.state.value.partner == null) safetyNotifier.cancelPartnerSharing()
                }.outcome()
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
    private fun AppResult<*>.outcome(): EffectOutcome = (this as? AppResult.Failure)?.error.outcome()

    private fun AppError?.outcome(): EffectOutcome = when {
        this == null || !isRetryable -> EffectOutcome.DONE
        this is AppError.Offline -> EffectOutcome.OFFLINE
        else -> EffectOutcome.FAILED
    }

    private companion object {
        const val PREFS = "deferred_effects"
        const val K_QUEUE = "queue"
        const val K_FAILS = "fails:"
        const val ACK = "ack:"
        const val GAME = "game:"
        const val GAMES_STALE = "games:stale"
        const val GAMES_FULL = "games:all"
        const val ME = "me"
        const val LOCATION_CHECK = "location:check"
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
 *   runs again: the first run may have fetched the state before the newer event. So does one queued
 *   again after this drain already ran it.
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
    /** What the drain under way has already run (null when none runs). Guarded by [lock]. */
    private var drainTried: MutableSet<String>? = null

    fun add(item: String) = synchronized(lock) {
        storage.saveItems(storage.items() + item)
        // Queued again after the running drain already ran it (a newer game move while that drain
        // sends receipts): run it again, or the drain would skip it as tried, and a drain started
        // meanwhile returned at once because this one holds the lock.
        if (item == running) requeued = true else drainTried?.remove(item)
    }

    fun pending(): Set<String> = synchronized(lock) { storage.items() }

    /**
     * Runs what is queued, including items added while it runs. With [wait] = false it returns at
     * once if another drain is under way (that drain picks up anything added meanwhile).
     */
    suspend fun drain(wait: Boolean = true, run: suspend (String) -> EffectOutcome) {
        if (wait) drainMutex.lock() else if (!drainMutex.tryLock()) return
        val tried = mutableSetOf<String>()
        synchronized(lock) { drainTried = tried }
        try {
            while (true) {
                val item = synchronized(lock) {
                    storage.items().firstOrNull { it !in tried }?.also { running = it; requeued = false; tried += it }
                } ?: return
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
            synchronized(lock) { drainTried = null }
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
