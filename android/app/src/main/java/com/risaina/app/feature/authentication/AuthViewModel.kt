package com.risaina.app.feature.authentication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.AuthRepository
import com.risaina.app.core.datastore.PreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val nameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val formError: String? = null,
    val loading: Boolean = false,
    val forgotSent: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val preferencesStore: PreferencesStore,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    fun onName(v: String) = _state.update { it.copy(name = v, nameError = null, formError = null) }
    fun onEmail(v: String) = _state.update { it.copy(email = v, emailError = null, formError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, formError = null) }

    private fun validate(requireName: Boolean, requirePassword: Boolean = true): Boolean {
        val s = _state.value
        val emailErr = if (!EMAIL.matches(s.email.trim())) "Enter a valid email" else null
        val pwErr = if (requirePassword && s.password.length < 10) "Use at least 10 characters" else null
        val nameErr = if (requireName && s.name.trim().length < 2) "Tell us what to call you" else null
        _state.update { it.copy(emailError = emailErr, passwordError = pwErr, nameError = nameErr) }
        return emailErr == null && pwErr == null && nameErr == null
    }

    fun signUp() {
        if (!validate(requireName = true)) return
        run { auth.register(_state.value.email, _state.value.password, _state.value.name) }
    }

    fun signIn() {
        if (!validate(requireName = false)) return
        run { auth.login(_state.value.email, _state.value.password) }
    }

    fun forgot() {
        if (!validate(requireName = false, requirePassword = false)) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            auth.forgotPassword(_state.value.email)
            // Always report success: the server never reveals whether the email exists.
            _state.update { it.copy(loading = false, forgotSent = true) }
        }
    }

    private fun run(block: suspend () -> AppResult<Unit>) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, formError = null) }
            when (val r = block()) {
                is AppResult.Success -> { preferencesStore.setOnboardingDone(true); _state.update { it.copy(loading = false) } }
                is AppResult.Failure -> _state.update { it.copy(loading = false, formError = r.error.toMessage()) }
            }
        }
    }

    private fun AppError.toMessage(): String = when (this) {
        is AppError.Unauthorized -> "That email and password don't match"
        is AppError.Conflict -> if (code == "conflict" || code == "email_taken") "An account with that email already exists" else message ?: "That didn't work"
        is AppError.Validation -> message ?: "Please check what you entered"
        is AppError.RateLimited -> "Too many attempts. Please wait a moment"
        is AppError.Offline -> "You're offline. Check your connection and try again"
        is AppError.Timeout -> "That took too long. Try again"
        else -> "Something went wrong. Please try again"
    }

    private companion object { val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$") }
}
