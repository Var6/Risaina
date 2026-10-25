package com.risaina.app.feature.ping

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.risaina.app.core.designsystem.theme.Palette
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.model.PingStatus
import kotlinx.coroutines.launch

/** What the big button is doing right now (sender's perspective). */
sealed interface PingButtonState {
    data object Idle : PingButtonState
    data class Cooldown(val secondsLeft: Long) : PingButtonState
    data object Sending : PingButtonState
    data class Settled(val status: PingStatus) : PingButtonState
    data class Failed(val reason: String) : PingButtonState
}

/**
 * The signature control. On tap: compresses, a ring expands, the symbol blooms, a haptic
 * confirms the *tap* (nothing else), then the sender watches the state settle as the server
 * reports it. The bloom and ring are one-shot animations keyed by [burstKey]; the state text is
 * always rendered so the animation is never the only signal.
 */
@Composable
fun TouchPingButton(
    state: PingButtonState,
    emoji: String,
    burstKey: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
) {
    val motion = Risaina.motion
    val colors = Risaina.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.93f else 1f, motion.springTactile(), label = "press")

    val ring = remember { Animatable(0f) }
    val bloom = remember { Animatable(0f) }
    LaunchedEffect(burstKey) {
        if (burstKey == 0) return@LaunchedEffect
        ring.snapTo(0f); bloom.snapTo(0f)
        launch { ring.animateTo(1f, if (motion.reduced) tween(150) else tween(motion.expressiveMs, easing = motion.easeEmphasized)) }
        launch { bloom.animateTo(1f, motion.springBloom()); bloom.animateTo(0f, tween(motion.standardMs, easing = motion.easeExit)) }
    }

    val enabled = state !is PingButtonState.Sending && state !is PingButtonState.Cooldown
    val stateText = when (state) {
        PingButtonState.Idle -> "Ready"
        is PingButtonState.Cooldown -> "Wait ${state.secondsLeft}s"
        PingButtonState.Sending -> "Sending"
        is PingButtonState.Settled -> state.status.name.lowercase().replaceFirstChar { it.uppercase() }
        is PingButtonState.Failed -> "Failed"
    }

    Box(
        modifier
            .size(size * 1.9f)
            .semantics {
                role = Role.Button
                contentDescription = "Send a touch ping"
                stateDescription = stateText
            },
        contentAlignment = Alignment.Center,
    ) {
        // Expanding ring + fading glow
        Canvas(Modifier.size(size * 1.9f)) {
            val p = ring.value
            if (p > 0f && p < 1f) {
                val radius = (size.toPx() / 2f) * (1f + 1.1f * p)
                drawCircle(colors.pingGlow.copy(alpha = (1f - p) * 0.55f), radius = radius, style = Stroke(width = 2.dp.toPx() * (1f - p * 0.6f)))
                drawCircle(colors.pingGlow.copy(alpha = (1f - p) * 0.10f), radius = radius)
            }
        }
        // The button body
        Box(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .drawBehind {
                    drawCircle(colors.pingGlow.copy(alpha = 0.16f), radius = size.toPx() / 2f + 10.dp.toPx())
                }
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(colors.pingFill, colors.heroGradientStart), radius = size.value * 2.2f))
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onTap,
                    onLongClick = onLongPress,
                    role = Role.Button,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                PingButtonState.Sending -> SendingArc(size)
                else -> Unit
            }
            Text(
                text = emoji,
                style = RisainaTextStyles.emojiLarge.copy(fontSize = (size.value * 0.34f).sp),
                modifier = Modifier.graphicsLayer {
                    val b = bloom.value
                    scaleX = 1f + 0.35f * b; scaleY = scaleX
                    translationY = -size.toPx() * 0.12f * b
                },
            )
        }
        // Bloom: a second symbol that floats up and fades
        if (bloom.value > 0.02f) {
            Text(
                text = emoji,
                style = RisainaTextStyles.emojiLarge.copy(fontSize = (size.value * 0.26f).sp),
                modifier = Modifier.graphicsLayer {
                    val b = bloom.value
                    alpha = (1f - b).coerceIn(0f, 1f)
                    translationY = -size.toPx() * (0.55f + 0.5f * b)
                    scaleX = 0.8f + 0.5f * b; scaleY = scaleX
                },
            )
        }
    }
}

@Composable
private fun SendingArc(size: Dp) {
    val transition = rememberInfiniteTransition(label = "arc")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween<Float>(1100, easing = LinearEasing)),
        label = "angle",
    )
    val c = MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.size(size - 10.dp)) {
        drawArc(color = c.copy(alpha = 0.9f), startAngle = angle, sweepAngle = 70f, useCenter = false, style = Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** Palette-safe accent for status under the button. */
fun PingStatus.toneColor(colors: com.risaina.app.core.designsystem.theme.RisainaColors, scheme: androidx.compose.material3.ColorScheme): Color = when (this) {
    PingStatus.PENDING -> scheme.onSurfaceVariant
    PingStatus.ACCEPTED -> scheme.primary
    PingStatus.DELIVERED, PingStatus.SEEN -> colors.success
    PingStatus.FAILED -> colors.danger
    PingStatus.EXPIRED -> colors.warning
}
