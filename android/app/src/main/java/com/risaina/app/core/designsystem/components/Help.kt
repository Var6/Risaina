package com.risaina.app.core.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.theme.Risaina
import kotlinx.coroutines.launch

/**
 * The "?" in a screen's top app bar actions. A screen's standing how-to (what it is for, how to add
 * or use it, tips) lives in the sheet it opens, so the screen itself shows only its content and
 * controls. Live status, warnings, errors and fix buttons stay on the screen.
 */
@Composable
fun HelpAction(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(HelpIcon, contentDescription = "Help") }
    if (open) HelpSheet(title, onDismiss = { open = false }, content = content)
}

/** The sheet behind [HelpAction]: a title, the points, and "Got it". */
@Composable
fun HelpSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            // SectionHeader puts the modifier on its outer Row, which holds no text itself, so merge the
            // title Text into it: TalkBack then focuses one node that reads the title as a heading.
            SectionHeader(title, modifier = Modifier.semantics(mergeDescendants = true) { heading() })
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(8.dp))
            GhostButton(
                "Got it",
                onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } },
                modifier = Modifier.align(Alignment.End),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One point in a help sheet: an optional short heading, then the explanation. */
@Composable
fun HelpPoint(text: String, title: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted)
    }
}

/** Material "help_outline" (Apache 2.0), built here because material-icons-core has no help icon. */
private val HelpIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Help", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M11 18h2v-2h-2v2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 " +
                    "8 3.59 8 8-3.59 8-8 8zm0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5 0-2.21-1.79-4-4-4z",
            ),
            fill = SolidColor(Color.Black),
        )
        .build()
}
