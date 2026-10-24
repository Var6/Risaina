package com.risaina.app.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** One radius scale, used everywhere. 4 / 10 / 16 / 22 / 28 dp plus pill. */
object Radius {
    val xs = 4.dp
    val sm = 10.dp
    val md = 16.dp
    val lg = 22.dp
    val xl = 28.dp
}

val RisainaShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)

val PillShape = RoundedCornerShape(50)
