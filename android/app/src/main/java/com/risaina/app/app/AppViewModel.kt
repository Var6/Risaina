package com.risaina.app.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.data.AuthRepository
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.PairingState
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.model.DarkMode
import com.risaina.app.core.network.SessionEvents
import com.risaina.app.core.sync.ConnectivityObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which root the user belongs in. Navigation resets to it whenever it changes. */
enum class Gate { LOADING, WELCOME, SIGN_IN, PAIRING, HOME }

data class AppUiState(
    val gate: Gate = Gate.LOADING,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val reducedMotion: Boolean = false,
    val online: Boolean = true,
    val accent: String = "ROSEWOOD",
    val background: String = "GLOW",
) { val loading get() = gate == Gate.LOADING }

@HiltViewModel
class AppViewModel @Inject constructor(
    sessionStore: SessionStore,
    preferencesStore: PreferencesStore,
    coupleRepository: CoupleRepository,
    connectivity: ConnectivityObserver,
    sessionEvents: SessionEvents,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _deepLinks = MutableSharedFlow<Uri>(extraBufferCapacity = 4)
    val deepLinks: SharedFlow<Uri> = _deepLinks

    val uiState: StateFlow<AppUiState> = combine(
        sessionStore.session, preferencesStore.onboardingDone, coupleRepository.pairingState,
        preferencesStore.preferences, connectivity.isOnline,
    ) { session, onboardingDone, pairing, prefs, online ->
        val gate = when {
            session == null -> if (onboardingDone) Gate.SIGN_IN else Gate.WELCOME
            pairing is PairingState.Loading -> Gate.LOADING
            pairing is PairingState.Paired -> Gate.HOME
            else -> Gate.PAIRING
        }
        AppUiState(gate = gate, darkMode = prefs.darkMode, reducedMotion = prefs.reducedMotion, online = online, accent = prefs.accent, background = prefs.background)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    init {
        viewModelScope.launch {
            // Pull server truth on launch; a stale local couple state would otherwise linger.
            if (sessionStore.current() != null) coupleRepository.refreshMe() else Unit
        }
        viewModelScope.launch { sessionEvents.expired.collect { authRepository.onSessionExpired() } }
    }

    fun onDeepLink(uri: Uri?) { if (uri != null) _deepLinks.tryEmit(uri) }
}
