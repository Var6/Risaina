package com.risaina.app.feature.authentication

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.risaina.app.BuildConfig
import com.risaina.app.core.data.GoogleSignOut
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GoogleResult {
    data class Token(val idToken: String) : GoogleResult
    data object Cancelled : GoogleResult
    data class Error(val message: String) : GoogleResult
}

/**
 * "Continue with Google" via Android Credential Manager. Needs an *Activity* context because it
 * shows the system account sheet. The ID token goes only to our backend, which verifies it.
 */
object GoogleSignIn {
    val configured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    suspend fun requestIdToken(activityContext: Context): GoogleResult {
        if (!configured) return GoogleResult.Error("Google sign-in isn't set up in this build")
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).setNonce(nonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val result = CredentialManager.create(activityContext).getCredential(activityContext, request)
            val cred = result.credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleResult.Token(GoogleIdTokenCredential.createFrom(cred.data).idToken)
            } else GoogleResult.Error("Unexpected sign-in response")
        } catch (e: GetCredentialCancellationException) {
            GoogleResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleResult.Error("No Google account on this phone. Add one in Settings, or use email.")
        } catch (e: GetCredentialException) {
            GoogleResult.Error("Google sign-in didn't work. Try again or use email.")
        }
    }
}

@Singleton
class CredentialManagerSignOut @Inject constructor(@ApplicationContext private val context: Context) : GoogleSignOut {
    override suspend fun clear() {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleSignOutModule {
    @Binds abstract fun bind(impl: CredentialManagerSignOut): GoogleSignOut
}
