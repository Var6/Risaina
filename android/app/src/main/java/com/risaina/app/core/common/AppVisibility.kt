package com.risaina.app.core.common

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether Risaina's own screens (Home, chat, settings...) are on screen. This is what "the app is
 * open" means for notifications, the in-app reveal and the live socket.
 *
 * ProcessLifecycleOwner cannot answer it: it also counts the SOS alarm screen, which shows over the
 * lock screen for up to 5 minutes with no Home or chat behind it. While it rang, the partner's
 * messages and pings were treated as "shown in the app" (no notification, ever), the socket applied
 * them silently, and the ping was marked seen on the server. Activities that [register] is told to
 * ignore are left out.
 */
object AppVisibility {
    private val _visible = MutableStateFlow(false)
    /** True while one of Risaina's own (not ignored) activities is started. */
    val mainUiVisible: StateFlow<Boolean> = _visible
    val isVisible: Boolean get() = _visible.value

    /**
     * Leaving is reported this long after the last activity stopped, as ProcessLifecycleOwner does:
     * a rotation or a hop between two activities is not leaving the app.
     */
    private const val LEAVE_DELAY_MS = 700L

    private val counter = VisibilityCounter { _visible.value = it }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val leave = Runnable { counter.onLeaveDelayOver() }

    fun register(app: Application, ignore: (Activity) -> Boolean) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (ignore(activity)) return
                main.removeCallbacks(leave)
                counter.onStarted()
            }

            override fun onActivityStopped(activity: Activity) {
                if (ignore(activity)) return
                if (counter.onStopped()) main.postDelayed(leave, LEAVE_DELAY_MS)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/**
 * The counting behind [AppVisibility], without Android, so it is unit-tested (AppVisibilityTest).
 * Visible as soon as one activity starts; hidden only once none is started and the leave delay has
 * passed without another one starting. Main thread only.
 */
class VisibilityCounter(private val publish: (Boolean) -> Unit) {
    private var started = 0
    private var leaving = false

    fun onStarted() {
        started++
        leaving = false
        publish(true)
    }

    /** Returns true when the caller should call [onLeaveDelayOver] after the leave delay. */
    fun onStopped(): Boolean {
        started = (started - 1).coerceAtLeast(0)
        if (started > 0) return false
        leaving = true
        return true
    }

    fun onLeaveDelayOver() {
        if (!leaving || started > 0) return
        leaving = false
        publish(false)
    }
}
