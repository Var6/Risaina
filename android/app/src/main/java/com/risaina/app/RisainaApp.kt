package com.risaina.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.Configuration
import kotlinx.coroutines.launch
import com.risaina.app.core.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RisainaApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncManager: com.risaina.app.core.sync.EventSyncManager
    @Inject lateinit var widgetSnapshotUpdater: com.risaina.app.core.sync.WidgetSnapshotUpdater
    @Inject lateinit var safety: com.risaina.app.core.safety.SafetyLifecycle

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        watchForeground()
        safety.start() // SOS alarm volume restore, live location re-check, stop on sign-out/unlink
    }

    /**
     * Realtime connection while Risaina is on screen, owned by the *process*, not by one activity.
     * It used to live in MainActivity, where a second instance (opening a deep link or a
     * notification) found the socket "already running", and then the first instance's stop
     * cancelled it, leaving the app offline with no live pings or buzz until it was restarted.
     */
    private fun watchForeground() {
        val owner = androidx.lifecycle.ProcessLifecycleOwner.get()
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                syncManager.start(this)
                com.risaina.app.core.sync.SyncWorker.enqueueSilent(applicationContext)
                // Opening Risaina is seeing what the partner sent: the widgets stop glowing.
                launch { runCatching { widgetSnapshotUpdater.markSeenAndRecompute(ackServer = true) } }
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()
}
