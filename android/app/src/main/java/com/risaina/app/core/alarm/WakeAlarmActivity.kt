package com.risaina.app.core.alarm

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.theme.Palette
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.designsystem.theme.RisainaTheme
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * The alarm screen, opened by the ring's full-screen intent (or directly while Risaina is on
 * screen). Drawn over the lock screen like an alarm clock, it never unlocks the phone and shows
 * nothing else of the app: the time, the label, whether the partner is up, "I'm up" and Snooze.
 * Back does nothing (a choice is needed), and it finishes as soon as the ring stops, wherever it was
 * answered (the notification, the snoozed notice, the timeout, an SOS taking over).
 */
@AndroidEntryPoint
class WakeAlarmActivity : ComponentActivity() {
    @Inject lateinit var controller: WakeAlarmController
    @Inject lateinit var store: WakeAlarmStore

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
                val ring by controller.ringing.collectAsStateWithLifecycle(initialValue = store.current().ring?.takeIf { it.phase == WakePhase.RINGING })
                val local by store.state.collectAsStateWithLifecycle()
                LaunchedEffect(ring == null) { if (ring == null) finish() }
                ring?.let { r ->
                    val partner = local.alarm(r.alarmId)?.members?.firstOrNull { it.userId != local.me }
                    val zone = ZoneId.systemDefault()
                    WakeScreen(
                        ring = r,
                        time = WakeAlarmRules.timeText(Instant.ofEpochMilli(r.occurrence), zone),
                        partnerLine = if (r.test) null else WakeAlarmRules.ringPartnerLine(
                            local.partnerName ?: "Your partner", partner, Instant.ofEpochMilli(r.occurrence), r.partnerUpAt?.let(Instant::ofEpochMilli), zone,
                        ),
                        onUp = { controller.up(r.alarmId) },
                        onSnooze = { controller.snooze(r.alarmId) },
                    )
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, WakeAlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

@Composable
private fun WakeScreen(ring: WakeRing, time: String, partnerLine: String?, onUp: () -> Unit, onSnooze: () -> Unit) {
    // Back must not dismiss the alarm without a choice (predictive back is on app-wide).
    BackHandler(enabled = true) {}
    val colors = Risaina.colors
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.heroGradientStart, colors.heroGradientEnd)))
            .safeDrawingPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("☀", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(ring.label.uppercase(), style = RisainaTextStyles.overline, color = colors.heroAccent, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            time,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp, lineHeight = 76.sp),
            color = Palette.WarmIvory,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                ring.test -> "This is how Wake together rings on this phone."
                partnerLine != null -> partnerLine
                else -> "Good morning"
            },
            style = MaterialTheme.typography.bodyLarge, color = colors.heroAccentSoft, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
        PrimaryButton(if (ring.test) "Stop test" else "I'm up", onClick = onUp, modifier = Modifier.fillMaxWidth().height(64.dp))
        if (!ring.test) {
            Spacer(Modifier.height(12.dp))
            OutlineButton("Snooze ${ring.snoozeMinutes} min", onClick = onSnooze, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Text(
                "Snooze and \"I'm up\" only change this phone.",
                style = MaterialTheme.typography.bodySmall, color = Palette.MutedOnDark, textAlign = TextAlign.Center,
            )
        }
    }
}
