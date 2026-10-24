package com.risaina.app.feature.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.theme.Palette
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles

/** First screen. Dark, quiet, one sentence of promise, two actions. */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, onSignIn: () -> Unit) {
    val motion = Risaina.motion
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reveal by animateFloatAsState(if (shown) 1f else 0f, tween(if (motion.reduced) 150 else 700, easing = motion.easeEmphasized), label = "reveal")

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.DeepWine, Palette.MidnightPlum, Palette.MidnightPlum))),
    ) {
        Mark(Modifier.align(Alignment.TopCenter).padding(top = 120.dp).size(168.dp).graphicsLayer { alpha = reveal; scaleX = 0.9f + 0.1f * reveal; scaleY = scaleX })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Risaina", style = MaterialTheme.typography.displayLarge, color = Palette.WarmIvory, modifier = Modifier.alpha(reveal))
            Spacer(Modifier.height(6.dp))
            Text("Always a little closer.", style = RisainaTextStyles.heroItalic.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize), color = Palette.Blush, modifier = Modifier.alpha(reveal))
            Spacer(Modifier.height(18.dp))
            Text(
                "Tiny signals, private memories, and a voice from far away. Made for exactly two people.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.MutedOnDark,
                modifier = Modifier.alpha(reveal), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            PrimaryButton(text = "Get started", onClick = onGetStarted, modifier = Modifier.fillMaxWidth().alpha(reveal))
            Spacer(Modifier.height(6.dp))
            GhostButton(text = "I already have an account", onClick = onSignIn, modifier = Modifier.alpha(reveal))
        }
    }
}

/** The two-discs mark, drawn directly so it scales crisply. */
@Composable
fun Mark(modifier: Modifier = Modifier, blush: Color = Palette.Blush, champagne: Color = Palette.SoftChampagne, ivory: Color = Palette.WarmIvory) {
    Canvas(modifier) {
        val r = size.minDimension * 0.19f
        val cy = size.height * 0.5f
        val ax = size.width * 0.5f - r * 0.47f
        val bx = size.width * 0.5f + r * 0.47f
        drawCircle(blush, r, Offset(ax, cy))
        drawCircle(champagne, r, Offset(bx, cy))
        // lens
        val path = androidx.compose.ui.graphics.Path().apply {
            val half = kotlin.math.sqrt(r * r - (r * 0.47f) * (r * 0.47f))
            moveTo(size.width * 0.5f, cy - half)
            arcTo(androidx.compose.ui.geometry.Rect(Offset(ax - r, cy - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r)), -62f, 124f, false)
            arcTo(androidx.compose.ui.geometry.Rect(Offset(bx - r, cy - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r)), 118f, 124f, false)
            close()
        }
        drawPath(path, ivory)
    }
}
