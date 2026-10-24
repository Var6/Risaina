package com.risaina.app.core.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (overline != null) {
                Text(overline.uppercase(), style = RisainaTextStyles.overline, color = Risaina.colors.accentWarm)
                Spacer(Modifier.height(4.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        trailing?.invoke()
    }
}
