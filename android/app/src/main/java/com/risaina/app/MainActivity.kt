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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.risaina.app.app.AppViewModel
import com.risaina.app.app.RisainaAppUi
import com.risaina.app.core.designsystem.theme.RisainaTheme
import com.risaina.app.core.model.DarkMode
import com.risaina.app.core.sync.EventSyncManager
import com.risaina.app.core.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()
    @Inject lateinit var syncManager: EventSyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { appViewModel.uiState.value.loading }

        // Foreground-only realtime connection: opened when STARTED, closed when STOPPED.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                syncManager.start(this)
                SyncWorker.enqueueSilent(applicationContext)
                try { awaitCancellation() } finally { syncManager.stop() }
            }
        }
        SyncWorker.schedulePeriodic(applicationContext)
        appViewModel.onDeepLink(intent?.data)

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
