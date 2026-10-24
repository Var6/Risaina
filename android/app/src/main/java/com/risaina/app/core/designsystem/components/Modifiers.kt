package com.risaina.app.core.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.theme.Risaina

/**
 * Press-scale feedback for tactile controls. Scales to [pressedScale] while pressed and springs
 * back on release. Respects reduced motion through the motion tokens.
 */
fun Modifier.tactile(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = Risaina.motion
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = motion.springTactile(),
        label = "tactileScale",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Soft, low-contrast shadow used on cards. Kept subtle on purpose. */
fun Modifier.softShadow(shape: Shape, elevation: Dp = 10.dp): Modifier = composed {
    val color = Risaina.colors.heroGradientEnd
    shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = color.copy(alpha = 0.14f),
        spotColor = color.copy(alpha = 0.18f),
    )
}
