package com.risaina.app.feature.authentication

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.components.StatusPill
import com.risaina.app.core.designsystem.components.PillTone
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.EyeIcon

@Composable
private fun AuthFrame(title: String, subtitle: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Risaina.spacing.gutter),
    ) {
        Spacer(Modifier.height(8.dp))
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) { Icon(BackIcon, contentDescription = "Back") }
        Spacer(Modifier.height(if (onBack != null) 20.dp else 56.dp))
        Text(title, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted)
        Spacer(Modifier.height(32.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SignUpScreen(onBack: () -> Unit, onGoToSignIn: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showPw by remember { mutableStateOf(false) }
    AuthFrame("Create your account", "Only you and one other person will ever be in here.", onBack) {
        RisainaTextField(s.name, vm::onName, "Your name", error = s.nameError, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words))
        Spacer(Modifier.height(12.dp))
        RisainaTextField(s.email, vm::onEmail, "Email", error = s.emailError, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
        Spacer(Modifier.height(12.dp))
        RisainaTextField(
            s.password, vm::onPassword, "Password", error = s.passwordError, supporting = "At least 10 characters",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = { IconButton(onClick = { showPw = !showPw }) { Icon(EyeIcon, contentDescription = if (showPw) "Hide password" else "Show password") } },
        )
        FormError(s.formError)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Create account", onClick = vm::signUp, loading = s.loading, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        GhostButton("I already have an account", onClick = onGoToSignIn, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun SignInScreen(onBack: (() -> Unit)?, onGoToSignUp: () -> Unit, onForgot: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showPw by remember { mutableStateOf(false) }
    AuthFrame("Welcome back", "Sign in to pick up where you left off.", onBack) {
        RisainaTextField(s.email, vm::onEmail, "Email", error = s.emailError, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
        Spacer(Modifier.height(12.dp))
        RisainaTextField(
            s.password, vm::onPassword, "Password", error = s.passwordError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = { IconButton(onClick = { showPw = !showPw }) { Icon(EyeIcon, contentDescription = if (showPw) "Hide password" else "Show password") } },
        )
        FormError(s.formError)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Sign in", onClick = vm::signIn, loading = s.loading, modifier = Modifier.fillMaxWidth())
        if (GoogleSignIn.configured) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Spacer(Modifier.height(10.dp))
            com.risaina.app.core.designsystem.components.OutlineButton("Continue with Google", onClick = { vm.google(ctx) }, enabled = !s.googleLoading, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(4.dp))
        GhostButton("Forgot password?", onClick = onForgot, modifier = Modifier.fillMaxWidth())
        GhostButton("Create an account", onClick = onGoToSignUp, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun ForgotPasswordScreen(onBack: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    AuthFrame("Reset your password", "If an account exists for this email, we'll send a reset link.", onBack) {
        if (s.forgotSent) {
            StatusPill("Request received", PillTone.Success)
            Spacer(Modifier.height(12.dp))
            Text(
                "Check your inbox. If nothing arrives, the email may not be registered — or the server's email delivery isn't set up yet.",
                style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted,
            )
        } else {
            RisainaTextField(s.email, vm::onEmail, "Email", error = s.emailError, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done))
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Send reset link", onClick = vm::forgot, loading = s.loading, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FormError(text: String?) {
    if (text != null) {
        Spacer(Modifier.height(8.dp))
        Text(text, style = RisainaTextStyles.timestamp.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = MaterialTheme.colorScheme.error)
    }
}


/** Six-digit email verification. Never blocks using the app; reachable from Home and Settings. */
@Composable
fun VerifyEmailScreen(onBack: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(s.verified) { if (s.verified) onBack() }
    AuthFrame("Confirm your email", "We sent a 6-digit code. It helps you recover your account if you ever lose this phone.", onBack) {
        RisainaTextField(
            s.verifyCode, vm::onVerifyCode, "Code", error = s.verifyError, placeholder = "123456",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Confirm", onClick = vm::verify, loading = s.loading, enabled = s.verifyCode.length == 6, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        GhostButton("Send a new code", onClick = vm::resend, modifier = Modifier.fillMaxWidth())
        if (s.verifyInfo != null) Text(s.verifyInfo!!, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
    }
}
