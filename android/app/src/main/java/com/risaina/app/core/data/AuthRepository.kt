package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.database.RisainaDatabase
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.datastore.WidgetSnapshotStore
import com.risaina.app.core.model.Session
import com.risaina.app.core.network.DeleteMeRequest
import com.risaina.app.core.network.ForgotRequest
import com.risaina.app.core.network.GoogleAuthRequest
import com.risaina.app.core.network.VerifyEmailRequest
import com.risaina.app.core.network.LoginRequest
import com.risaina.app.core.network.LogoutRequest
import com.risaina.app.core.network.RegisterRequest
import com.risaina.app.core.network.ResetRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.TokensDto
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Sign-in lifecycle. After authentication the couple/partner/preferences are pulled by [CoupleRepository]. */
@Singleton
class AuthRepository @Inject constructor(
    private val api: RisainaApi,
    private val sessionStore: SessionStore,
    private val preferencesStore: PreferencesStore,
    private val widgetSnapshotStore: WidgetSnapshotStore,
    private val db: RisainaDatabase,
    private val coupleRepository: CoupleRepository,
    private val deviceRegistrar: DeviceRegistrar,
    private val googleSignOut: GoogleSignOut,
    private val widgetBoardRepository: WidgetBoardRepository,
    private val lockScreenCard: com.risaina.app.core.notifications.LockScreenCard,
    private val notifier: com.risaina.app.core.notifications.RisainaNotifier,
) {
    val isSignedIn: Flow<Boolean> = sessionStore.session.map { it != null }

    suspend fun register(email: String, password: String, displayName: String): AppResult<Unit> =
        apiCall { api.register(RegisterRequest(email.trim().lowercase(), password, displayName.trim())) }
            .map { r -> r.tokens to r.user }
            .let { res ->
                when (res) {
                    is AppResult.Success -> { onAuthenticated(res.value.first, res.value.second.id, res.value.second.displayName, res.value.second.avatarMediaId, res.value.second.emailVerified); AppResult.Success(Unit) }
                    is AppResult.Failure -> res
                }
            }

    suspend fun login(email: String, password: String): AppResult<Unit> =
        when (val res = apiCall { api.login(LoginRequest(email.trim().lowercase(), password)) }) {
            is AppResult.Success -> { onAuthenticated(res.value.tokens, res.value.user.id, res.value.user.displayName, res.value.user.avatarMediaId, res.value.user.emailVerified); AppResult.Success(Unit) }
            is AppResult.Failure -> res
        }

    /** Exchange a Google ID token (from Credential Manager) for a Risaina session. */
    suspend fun signInWithGoogle(idToken: String): AppResult<Boolean> =
        when (val res = apiCall { api.google(GoogleAuthRequest(idToken)) }) {
            is AppResult.Success -> {
                onAuthenticated(res.value.tokens, res.value.user.id, res.value.user.displayName, res.value.user.avatarMediaId, true)
                AppResult.Success(res.value.isNewUser)
            }
            is AppResult.Failure -> res
        }

    suspend fun verifyEmail(code: String): AppResult<Unit> {
        val res = apiCall { api.verifyEmail(VerifyEmailRequest(code.trim())) }
        if (res is AppResult.Success) sessionStore.saveProfile(res.value.user.displayName, res.value.user.avatarMediaId, res.value.user.emailVerified)
        return res.map { }
    }

    suspend fun resendVerification(): AppResult<Unit> = apiCall { api.resendVerification() }

    private suspend fun onAuthenticated(tokens: TokensDto, userId: String, displayName: String, avatarMediaId: String?, emailVerified: Boolean = false) {
        val deviceId = sessionStore.deviceId()
        sessionStore.save(
            Session(
                userId = userId,
                accessToken = tokens.accessToken,
                accessExpiresAt = Instant.parse(tokens.accessExpiresAt),
                refreshToken = tokens.refreshToken,
                refreshExpiresAt = Instant.parse(tokens.refreshExpiresAt),
                deviceId = deviceId,
            ),
        )
        sessionStore.saveProfile(displayName, avatarMediaId, emailVerified)
        coupleRepository.refreshMe()
        deviceRegistrar.scheduleRegistration()
    }

    suspend fun forgotPassword(email: String): AppResult<Unit> = apiCall { api.forgotPassword(ForgotRequest(email.trim().lowercase())) }
    suspend fun resetPassword(token: String, newPassword: String): AppResult<Unit> = apiCall { api.resetPassword(ResetRequest(token.trim(), newPassword)) }

    /** Best-effort server logout, then always wipe local state. */
    suspend fun logout() {
        val session = sessionStore.current()
        if (session != null) {
            apiCall { api.logout(LogoutRequest(session.refreshToken, session.deviceId)) }
        }
        clearLocal()
    }

    /** Password accounts confirm with their password; Google-only accounts type DELETE. */
    suspend fun deleteAccount(passwordOrConfirm: String): AppResult<Unit> {
        val req = if (passwordOrConfirm == "DELETE") DeleteMeRequest(confirm = "DELETE") else DeleteMeRequest(password = passwordOrConfirm)
        val res = apiCall { api.deleteMe(req) }
        if (res is AppResult.Success) clearLocal()
        return res
    }

    /** Called when a refresh is rejected: the session is gone server-side. */
    suspend fun onSessionExpired() { clearLocal() }

    private suspend fun clearLocal() {
        googleSignOut.clear()
        sessionStore.clear()
        db.clearAllContent()
        widgetSnapshotStore.clear()
        widgetBoardRepository.clear()
        lockScreenCard.cancel()
        notifier.clearAllPartnerNotifications()
        preferencesStore.setOnboardingDone(true) // returning users skip the welcome carousel
    }
}

/** Abstraction so the auth layer does not depend on Firebase directly. Implemented in core.notifications. */
/** Clears the system's remembered Google account choice on sign-out. */
interface GoogleSignOut {
    suspend fun clear()
}

interface DeviceRegistrar {
    fun scheduleRegistration()
}

