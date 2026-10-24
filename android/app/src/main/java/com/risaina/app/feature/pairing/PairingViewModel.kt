package com.risaina.app.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.AuthRepository
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.PairingState
import com.risaina.app.core.model.InvitationCreated
import com.risaina.app.feature.common.UiFlags
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PairingUiState(
    val pairing: PairingState = PairingState.Loading,
    val myName: String = "",
    /** Shown once; kept in memory for this screen only. */
    val freshCode: InvitationCreated? = null,
    val codeInput: String = "",
    val creating: Boolean = false,
    val joining: Boolean = false,
    val revoking: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val coupleRepository: CoupleRepository,
    private val authRepository: AuthRepository,
    private val uiFlags: UiFlags,
) : ViewModel() {
    private val local = MutableStateFlow(PairingUiState())

    val state: StateFlow<PairingUiState> = combine(local, coupleRepository.pairingState, coupleRepository.profile) { l, p, me ->
        l.copy(pairing = p, myName = me?.displayName ?: "")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PairingUiState())

    fun refresh() { viewModelScope.launch { coupleRepository.refreshMe() } }

    fun createInvitation() {
        viewModelScope.launch {
            local.update { it.copy(creating = true, error = null) }
            when (val r = coupleRepository.createInvitation()) {
                is AppResult.Success -> local.update { it.copy(creating = false, freshCode = r.value) }
                is AppResult.Failure -> local.update { it.copy(creating = false, error = r.error.friendly()) }
            }
        }
    }

    fun revokeInvitation() {
        val id = state.value.freshCode?.id ?: (state.value.pairing as? PairingState.Waiting)?.invitation?.id ?: return
        viewModelScope.launch {
            local.update { it.copy(revoking = true, error = null) }
            when (val r = coupleRepository.revokeInvitation(id)) {
                is AppResult.Success -> local.update { it.copy(revoking = false, freshCode = null) }
                is AppResult.Failure -> local.update { it.copy(revoking = false, error = r.error.friendly()) }
            }
        }
    }

    fun onCode(v: String) = local.update { it.copy(codeInput = v.uppercase().filter { c -> c.isLetterOrDigit() }.take(10), error = null) }

    fun join() {
        val code = state.value.codeInput
        if (code.length < 10) { local.update { it.copy(error = "Codes are 10 characters") }; return }
        viewModelScope.launch {
            local.update { it.copy(joining = true, error = null) }
            when (val r = coupleRepository.acceptInvitation(code)) {
                is AppResult.Success -> { uiFlags.justPaired = true; local.update { it.copy(joining = false) } }
                is AppResult.Failure -> local.update { it.copy(joining = false, error = r.error.friendly()) }
            }
        }
    }

    fun signOut() { viewModelScope.launch { authRepository.logout() } }

    private fun AppError.friendly(): String = when (this) {
        is AppError.Conflict -> when (code) {
            "invitation_expired" -> "That code has expired. Ask for a new one."
            "invitation_used" -> "That code has already been used."
            "already_paired" -> "One of you is already paired."
            "not_paired" -> "You're not paired yet."
            else -> message ?: "That didn't work"
        }
        is AppError.NotFound -> "We couldn't find that code. Check it and try again."
        is AppError.RateLimited -> "Too many attempts. Wait a few minutes."
        is AppError.Offline -> "You're offline."
        is AppError.Validation -> message ?: "Check the code"
        else -> "Something went wrong. Try again."
    }
}
