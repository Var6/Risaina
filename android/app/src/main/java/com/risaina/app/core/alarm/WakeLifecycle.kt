package com.risaina.app.core.alarm

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.risaina.app.core.common.AppVisibility
import com.risaina.app.core.common.ApplicationScope
import com.risaina.app.core.datastore.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wake together around the app, started from RisainaApp.onCreate:
 * - every process start sets the next rings again from the store (a phone maker's force-stop drops
 *   every alarm; a push, a sync or opening the app starts the process again);
 * - opening Risaina and signing in fetch the alarms (GET /alarms);
 * - signing out and unlinking cancel every set ring and notice and empty the store.
 */
@Singleton
class WakeLifecycle @Inject constructor(
    private val sessionStore: SessionStore,
    private val store: WakeAlarmStore,
    private val controller: WakeAlarmController,
    private val sync: WakeAlarmSync,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            // Gone while no process ran (session expired, unlinked elsewhere and wiped on the next
            // start): nothing may ring for an account or a couple that is no longer here.
            val signedIn = sessionStore.current() != null && sessionStore.activeCoupleId.first() != null
            runCatching { if (!signedIn && store.current().me != null) controller.clearAll() else controller.rearm() }
        }
        val owner = ProcessLifecycleOwner.get()
        owner.lifecycleScope.launch {
            AppVisibility.mainUiVisible.collect { visible -> if (visible && paired()) scope.launch { runCatching { sync.refresh() } } }
        }
        scope.launch {
            var last: Pair<Boolean, String?>? = null
            combine(sessionStore.session.map { it != null }, sessionStore.activeCoupleId) { signedIn, couple -> signedIn to couple }
                .distinctUntilChanged()
                .collect { now ->
                    val before = last
                    last = now
                    if (before == null) return@collect // startup: handled above
                    val wasPaired = before.first && before.second != null
                    val isPaired = now.first && now.second != null
                    if (wasPaired && (!isPaired || now.second != before.second)) runCatching { controller.clearAll() }
                    if (isPaired && now != before) runCatching { sync.refresh() }
                }
        }
    }

    private suspend fun paired(): Boolean = sessionStore.current() != null && sessionStore.activeCoupleId.first() != null
}
