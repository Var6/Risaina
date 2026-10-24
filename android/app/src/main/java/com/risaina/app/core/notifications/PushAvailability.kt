package com.risaina.app.core.notifications

import android.content.Context
import com.google.firebase.FirebaseApp
import com.risaina.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Firebase Cloud Messaging can work on this build. The app compiles and runs without
 * `google-services.json`; in that case pings still arrive over the WebSocket while the app is
 * open, and the settings screen explains that background notifications are not configured.
 */
@Singleton
class PushAvailability @Inject constructor(@ApplicationContext private val context: Context) {
    val configured: Boolean
        get() = BuildConfig.HAS_GOOGLE_SERVICES && FirebaseApp.getApps(context).isNotEmpty()
}
