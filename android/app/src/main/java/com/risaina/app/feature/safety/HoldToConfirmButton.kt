package com.risaina.app.feature.safety

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal val SafetyRed = Color(0xFFC62828)

/**
 * Press and hold for 2 seconds to send. A progress ring fills with haptic ticks at a quarter, half
 * and three quarters; it fires while the finger is still down, the moment the ring is full. Letting
 * go early never sends (see [HoldTracker]). TalkBack users get a confirmation dialog instead, since
 * a timed hold is not possible with a screen reader.
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    onConfirm: () -> Unit,
    confirmTitle: String,
    confirmText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busyLabel: String? = null,
    diameter: Dp = 188.dp,
) {
    val tracker = remember { HoldTracker() }
    var progress by remember { mutableFloatStateOf(0f) }
    var askConfirm by remember { mutableStateOf(false) }
    val confirm by rememberUpdatedState(onConfirm)
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .size(diameter)
            .alpha(if (enabled) 1f else 0.55f)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
                onClick(label = "Send") {
                    if (enabled) askConfirm = true
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    tracker.press(SystemClock.uptimeMillis())
                    // Runs beside the gesture: lifting the finger does not cancel onPress, so the
                    // timing loop is its own job, cancelled on release (and it fires only via tick).
                    val holding = scope.launch {
                        var lastQuarter = 0
                        while (true) {
                            withFrameMillis { }
                            val now = SystemClock.uptimeMillis()
                            progress = tracker.progress(now)
                            val quarter = tracker.quarter(now)
                            if (quarter in 1..3 && quarter > lastQuarter) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                lastQuarter = quarter
                            }
                            if (tracker.tick(now)) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                confirm()
                                break
                            }
                        }
                    }
                    try {
                        tryAwaitRelease()
                    } finally {
                        // Also when the gesture itself is cancelled (the button was disabled or left
                        // the screen mid-hold): the timing loop must never outlive the finger.
                        holding.cancel()
                        val fired = tracker.release()
                        scope.launch { animate(progress, 0f, animationSpec = tween(if (fired) 700 else 250)) { v, _ -> progress = v } }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            // red disc inside, a faint track and the filling progress ring around it
            drawCircle(SafetyRed, radius = size.minDimension / 2 - stroke * 1.6f)
            drawArc(
                color = SafetyRed.copy(alpha = 0.2f), startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke),
            )
            drawArc(
                color = SafetyRed, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(busyLabel ?: "HOLD", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White, textAlign = TextAlign.Center)
        }
    }

    if (askConfirm) AlertDialog(
        onDismissRequest = { askConfirm = false },
        title = { Text(confirmTitle) },
        text = { Text(confirmText) },
        confirmButton = { TextButton(onClick = { askConfirm = false; confirm() }) { Text("Send now") } },
        dismissButton = { TextButton(onClick = { askConfirm = false }) { Text("Cancel") } },
    )
}
