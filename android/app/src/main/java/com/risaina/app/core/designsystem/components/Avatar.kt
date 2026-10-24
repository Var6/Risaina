package com.risaina.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.risaina.app.core.designsystem.theme.FrauncesText
import com.risaina.app.core.designsystem.theme.Palette

/**
 * Partner avatar. Shows the image when available (any Coil-loadable model, including a
 * short-lived signed URL), otherwise a serif initial on a soft gradient. Never shows a generic
 * person glyph: the empty state should still feel personal.
 */
@Composable
fun Avatar(
    name: String?,
    model: Any?,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    ring: Boolean = false,
) {
    val initial = name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "·"
    Box(
        modifier
            .size(size)
            .then(if (ring) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Palette.Rosewood, Palette.DeepWine))),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = name?.let { "$it's photo" },
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                text = initial,
                color = Palette.WarmIvory,
                fontFamily = FrauncesText,
                fontSize = (size.value * 0.46f).sp,
            )
        }
    }
}
