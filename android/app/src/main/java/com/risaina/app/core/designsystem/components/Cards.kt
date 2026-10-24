package com.risaina.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.theme.Risaina

/**
 * Standard surface card: tinted container, hairline outline, soft shadow. Shadows are reserved
 * for cards that are interactive or sit on photography; static cards rely on tint alone.
 */
@Composable
fun RisainaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    elevated: Boolean = onClick != null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val base = modifier
        .then(if (elevated) Modifier.softShadow(shape) else Modifier)
        .clip(shape)
        .background(containerColor)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .then(
            if (onClick != null) Modifier
                .tactile(interaction, pressedScale = 0.985f)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            else Modifier,
        )
    Column(base.padding(contentPadding), content = content)
}

/** Dark, editorial panel used for the home hero and special moments. */
@Composable
fun HeroPanel(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Risaina.colors
    Box(
        modifier
            .softShadow(shape, elevation = 14.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(colors.heroGradientStart, colors.heroGradientEnd),
                ),
            ),
        content = content,
    )
}
