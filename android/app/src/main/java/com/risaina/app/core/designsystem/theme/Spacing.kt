package com.risaina.app.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 4 dp grid. Screen gutters are 20 dp on phones (feels editorial, not cramped). */
@Immutable
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val huge: Dp = 48.dp,
    val gutter: Dp = 20.dp,
    val sectionGap: Dp = 28.dp,
    val touchTarget: Dp = 48.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
