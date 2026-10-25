package com.risaina.app.feature.messages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.risaina.app.core.designsystem.theme.Risaina
import java.io.File

/** The chat's own background. Only changes this phone's chat screen, never the phone wallpaper. */
object ChatWallpapers {
    val presets = listOf("DEFAULT" to "Plain", "PAPER" to "Soft paper", "HEARTS" to "Little hearts", "NIGHT" to "Night sky", "GLOW" to "Glow", "PHOTO" to "My photo")
}

@Composable
fun ChatBackground(style: String, photo: Any?, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primaryContainer
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxSize().background(bg)) {
        when (style) {
            "PAPER" -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.25f), bg, accent.copy(alpha = 0.2f)))))
            "GLOW" -> Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(accent.copy(alpha = 0.6f), Color.Transparent), center = Offset(900f, 300f), radius = 1600f)))
            "NIGHT" -> Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0E1024), Color(0xFF241634))))
                // deterministic stars
                var seed = 7L
                repeat(90) {
                    seed = (seed * 1103515245 + 12345) and 0x7fffffff
                    val x = (seed % 1000) / 1000f * size.width
                    seed = (seed * 1103515245 + 12345) and 0x7fffffff
                    val y = (seed % 1000) / 1000f * size.height
                    drawCircle(Color.White.copy(alpha = 0.25f + (it % 5) * 0.1f), radius = 1.2f + (it % 3), center = Offset(x, y))
                }
            }
            "HEARTS" -> Canvas(Modifier.fillMaxSize()) {
                val step = 96.dp.toPx(); val s = 9.dp.toPx()
                var row = 0
                var y = step / 2
                while (y < size.height + step) {
                    var x = if (row % 2 == 0) step / 2 else step
                    while (x < size.width + step) {
                        val p = Path().apply {
                            moveTo(x, y + s * 0.9f)
                            cubicTo(x - s * 1.6f, y - s * 0.2f, x - s * 0.6f, y - s * 1.3f, x, y - s * 0.4f)
                            cubicTo(x + s * 0.6f, y - s * 1.3f, x + s * 1.6f, y - s * 0.2f, x, y + s * 0.9f)
                            close()
                        }
                        drawPath(p, primary.copy(alpha = 0.08f))
                        x += step
                    }
                    y += step * 0.8f; row++
                }
            }
            "PHOTO" -> if (photo != null) {
                AsyncImage(model = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(bg.copy(alpha = 0.55f)))
            }
            else -> Unit
        }
    }
}

@Composable
fun ChatWallpaperSheet(current: String, photo: Any?, onPick: (String) -> Unit, onPickPhoto: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Chat wallpaper", style = MaterialTheme.typography.headlineMedium)
            Text("Changes the chat for both of you.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            Spacer(Modifier.height(14.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ChatWallpapers.presets) { (key, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(width = 96.dp, height = 140.dp).clip(MaterialTheme.shapes.medium)
                                .border(if (current == key) 3.dp else 1.dp, if (current == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .clickable { if (key == "PHOTO") onPickPhoto() else onPick(key) },
                        ) {
                            ChatBackground(key, photo)
                            if (key == "PHOTO" && photo == null) Text("Choose…", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelMedium)
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
