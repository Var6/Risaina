package com.risaina.app.feature.safety

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Material Symbols path data (Apache 2.0), built here like feature/common/Icons.kt, kept in the
// safety package so the shared icon file is not touched.

/** Shield with a check: Settings → Panic button & SOS. */
val ShieldIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Shield", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes("M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16l-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z"),
            fill = SolidColor(Color.Black),
        )
        .build()
}
