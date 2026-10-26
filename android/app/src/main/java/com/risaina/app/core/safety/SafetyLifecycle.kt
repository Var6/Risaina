package com.risaina.app.core.safety

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.SosRepository
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.location.LiveLocationController
import com.risaina.app.core.location.LiveLocationRepository
import com.risaina.app.core.network.SocketState
import com.risaina.app.core.notifications.NotificationChannels
import com.risaina.app.core.sos.SosAlarmActivity
import com.risaina.app.core.sos.SosAlarmController
import com.risaina.app.core.sos.SosAlarmService
import com.risaina.app.core.sos.SosLocalState
import com.risaina.app.core.sync.EventSyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide safety housekeeping, started from RisainaApp.onCreate:
 * - puts the alarm volume back if the process died while an SOS was ringing;
 * - on every foreground: re-creates the SOS channels (turns Do Not Disturb bypass on once access was
 *   granted), shows a ringing alarm's screen (with notifications off the full-screen alert cannot),
 *   re-checks live location with the server, and refreshes the partner's location when the socket
 *   comes back;
 * - on sign-out or unlink: stops sharing and ringing on this phone (the server ends sharing itself).
 */
@Singleton
class SafetyLifecycle @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localState: SosLocalState,
    private val alarm: SosAlarmController,
    private val liveController: LiveLocationController,
    private val liveRepo: LiveLocationRepository,
    private val sessionStore: SessionStore,
    private val sosRepository: SosRepository,
    private val syncManager: dagger.Lazy<EventSyncManager>,
    private val notifier: SafetyNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        // Application.onCreate runs before any service of this process, so nothing can be ringing
        // here: a saved volume means the previous process died mid-ring. Put it back now; a ring that
        // starts later in this process saves the user's volume again before raising it.
        if (alarm.state.value == null) SosAlarmService.restoreAlarmVolume(context, localState)
        val owner = ProcessLifecycleOwner.get()
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                runCatching { NotificationChannels.ensureSafety(context) }
                if (alarm.state.value != null) runCatching { context.startActivity(SosAlarmActivity.intent(context)) }
                if (sessionStore.current() != null) {
                    launch { liveController.reconcile() }
                    launch { sosRepository.refresh() }
                }
                syncManager.get().socketState.map { it == SocketState.LIVE }.distinctUntilChanged().collect { live ->
                    // A share the server purged without an event (12 h with no fix: the partner's
                    // phone died) must not keep saying "is sharing": its notification goes once the
                    // server confirms there is no partner session.
                    if (live && liveRepo.refresh() is AppResult.Success && liveRepo.state.value.partner == null) notifier.cancelPartnerSharing()
                }
            }
        }
        scope.launch {
            var last: Pair<Boolean, String?>? = null
            combine(sessionStore.session.map { it != null }, sessionStore.activeCoupleId) { signedIn, couple -> signedIn to couple }
                .distinctUntilChanged()
                .collect { now ->
                    val before = last
                    last = now
                    // Only on a change (signed out, or the couple ended or changed), never at startup.
                    if (before != null && before.first && (before.second != null) && (!now.first || now.second != before.second)) onAccountOrCoupleGone()
                }
        }
    }

    private fun onAccountOrCoupleGone() {
        liveController.stopLocal()
        liveRepo.clear()
        // No location.stopped comes after an unlink (the server purges the session silently).
        notifier.cancelPartnerSharing()
        alarm.stopAll()
        sosRepository.clear()
        localState.clear()
    }
}
