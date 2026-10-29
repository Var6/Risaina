package com.risaina.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.risaina.app.core.common.AppVisibility
import com.risaina.app.core.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RisainaApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncManager: com.risaina.app.core.sync.EventSyncManager
    @Inject lateinit var widgetSnapshotUpdater: com.risaina.app.core.sync.WidgetSnapshotUpdater
    @Inject lateinit var safety: com.risaina.app.core.safety.SafetyLifecycle
    @Inject lateinit var reminders: com.risaina.app.core.reminders.ReminderScheduler
    @Inject lateinit var wake: com.risaina.app.core.alarm.WakeLifecycle

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        // The SOS and wake alarm screens over the lock screen are not "Risaina is open" (see AppVisibility).
        AppVisibility.register(this) { it is com.risaina.app.core.sos.SosAlarmActivity || it is com.risaina.app.core.alarm.WakeAlarmActivity }
        watchForeground()
        safety.start() // SOS alarm volume restore, live location re-check, stop on sign-out/unlink
        // Calendar reminders: post any that came due while the app was not running, set the next
        // alarm (a phone maker's battery manager may have dropped it), follow sign-out and unlink.
        reminders.start()
        wake.start() // Wake together: re-arm, refresh on open and sign-in, clear on sign-out/unlink
    }

    /**
     * Realtime connection while Risaina is on screen, owned by the *process*, not by one activity.
     * It used to live in MainActivity, where a second instance (opening a deep link or a
     * notification) found the socket "already running", and then the first instance's stop
     * cancelled it, leaving the app offline with no live pings or buzz until it was restarted.
     * Follows Risaina's own screens, not the process: the SOS alarm screen alone does not open the
     * socket, mark pings seen or calm the widgets (nothing of the chat or Home is visible there).
     */
    private fun watchForeground() {
        val owner = androidx.lifecycle.ProcessLifecycleOwner.get()
        owner.lifecycleScope.launch {
            AppVisibility.mainUiVisible.collectLatest { visible ->
                if (!visible) return@collectLatest
                coroutineScope {
                    syncManager.start(this)
                    com.risaina.app.core.sync.SyncWorker.enqueueOnOpen(applicationContext)
                    // Opening Risaina is seeing what the partner sent: the widgets stop glowing.
                    launch { runCatching { widgetSnapshotUpdater.markSeenAndRecompute(ackServer = true, clearAlerts = true) } }
                    try { kotlinx.coroutines.awaitCancellation() } finally {
                        syncManager.stop()
                        // Anything that arrived while the app was open was shown in-app, so leave the
                        // home screen calm rather than lighting up for it.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            runCatching { widgetSnapshotUpdater.markSeenAndRecompute(ackServer = true) }
                        }
                    }
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()
}
