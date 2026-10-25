package com.risaina.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.risaina.app.app.AppViewModel
import com.risaina.app.app.RisainaAppUi
import com.risaina.app.core.designsystem.theme.RisainaTheme
import com.risaina.app.core.model.DarkMode
import com.risaina.app.core.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { appViewModel.uiState.value.loading }

        // The realtime socket and "seen" marking follow the whole app's foreground state (RisainaApp).
        SyncWorker.schedulePeriodic(applicationContext)
        // Re-register this phone for push on every start: a sign-out elsewhere or a token change must
        // never leave a signed-in phone unable to receive pings, SOS or widget updates.
        lifecycleScope.launch { if (appViewModel.isSignedIn()) com.risaina.app.core.notifications.RegisterDeviceWorker.enqueue(applicationContext) }
        // A link that launched the app is opened by NavHost itself; only later links are forwarded.

        setContent {
            val state by appViewModel.uiState.collectAsStateWithLifecycle()
            val dark = when (state.darkMode) {
                DarkMode.SYSTEM -> isSystemInDarkTheme()
                DarkMode.LIGHT -> false
                DarkMode.DARK -> true
            }
            RisainaTheme(
                darkTheme = dark,
                reducedMotion = state.reducedMotion,
                accent = runCatching { com.risaina.app.core.designsystem.theme.AccentTheme.valueOf(state.accent) }.getOrDefault(com.risaina.app.core.designsystem.theme.AccentTheme.ROSEWOOD),
            ) {
                RisainaAppUi(state = state, deepLinks = appViewModel.deepLinks)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appViewModel.onDeepLink(intent.data)
    }
}
