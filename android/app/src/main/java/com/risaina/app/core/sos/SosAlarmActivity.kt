package com.risaina.app.core.sos

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.MainActivity
import com.risaina.app.core.designsystem.theme.RisainaTheme
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import javax.inject.Inject

/**
 * The alarm screen, opened by the full-screen intent while an SOS rings. It is drawn over the lock
 * screen like an alarm clock (showWhenLocked / turnScreenOn) and shows nothing else of the app. It
 * never unlocks the phone by itself, never acknowledges just because it opened, and finishes when
 * the ringing stops. With previews off, the message is hidden until the phone is unlocked.
 */
@AndroidEntryPoint
class SosAlarmActivity : ComponentActivity() {
    @Inject lateinit var controller: SosAlarmController

    /**
     * "I'm on it · see where they are" on a locked phone: the alarm stops at once (its state turns
     * null), but this activity must stay until the unlock request answers. Finishing under the
     * bouncer would drop the callback, or leave it to start the app from a finished activity.
     */
    private var pendingOpen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            RisainaTheme(darkTheme = true) {
                val state by controller.state.collectAsStateWithLifecycle()
                LaunchedEffect(state == null, pendingOpen) { if (state == null && !pendingOpen) finish() }
                val ringing = state
                if (ringing != null) {
                    AlarmScreen(
                        state = ringing,
                        locked = isLocked(),
                        onAck = { controller.acknowledge(); finish() },
                        onAckAndOpen = { route ->
                            pendingOpen = true // before the ack, which turns the state null
                            controller.acknowledge()
                            openApp(route)
                        },
                    )
                } else if (pendingOpen) {
                    UnlockToOpen()
                }
            }
        }
    }

    private fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Asks the user to unlock (never unlocks for them), then opens the app on the given screen. */
    private fun openApp(route: String) {
        val open = {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route), this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = open()
                // Not unlocked: the alarm is already acknowledged, so there is nothing left to show.
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        } else {
            open()
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SosAlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

private val AlarmRed = Color(0xFFB71C1C)

/** Shown behind the unlock prompt after "I'm on it · …" (the alarm has already stopped). */
@Composable
private fun UnlockToOpen() {
    Column(
        Modifier.fillMaxSize().background(AlarmRed).safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Unlock your phone to open Risaina", style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AlarmScreen(state: RingState, locked: Boolean, onAck: () -> Unit, onAckAndOpen: (String) -> Unit) {
    // Back must not dismiss the alarm without a choice (predictive back is on app-wide).
    BackHandler(enabled = true) {}
    val showMessage = !locked || state.previews
    Column(
        Modifier.fillMaxSize().background(AlarmRed).safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🆘", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                state.test -> "Test alarm"
                state.panic -> "${state.partnerName} is in trouble"
                else -> "${state.partnerName} needs you"
            },
            style = MaterialTheme.typography.displaySmall, color = Color.White, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                state.test -> "This is how an SOS from your partner will ring, even on silent."
                showMessage -> SosCopy.text(state.message, state.panic, false)
                else -> "Unlock your phone to see their message."
            },
            style = MaterialTheme.typography.bodyLarge, color = Color.White, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            com.risaina.app.feature.common.Format.time(Instant.ofEpochMilli(state.startedAtMs)),
            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onAck,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AlarmRed),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text(if (state.test) "Stop test" else "I'm on it", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
        if (!state.test) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { onAckAndOpen(if (state.panic) "risaina://app/location" else "risaina://app/messages") }) {
                Text(if (state.panic) "I'm on it · see where they are" else "I'm on it · open chat", color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "It keeps ringing until you tap \"I'm on it\".",
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center,
            )
        }
    }
}
