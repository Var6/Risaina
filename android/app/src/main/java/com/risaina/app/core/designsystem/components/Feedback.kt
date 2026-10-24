package com.risaina.app.core.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.theme.Risaina

/** Empty states carry a serif line and a quiet explanation; they are a feature, not a gap. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    emblem: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        emblem?.let { it(); Spacer(Modifier.height(20.dp)) }
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = Risaina.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

@Composable
fun ErrorState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "Try again",
) {
    EmptyState(
        title = title,
        body = body,
        modifier = modifier,
        action = onRetry?.let { { SecondaryButton(text = retryLabel, onClick = it) } },
    )
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            if (label != null) {
                Spacer(Modifier.height(12.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
        }
    }
}

/** Thin banner shown at the top of the content when the device is offline or reconnecting. */
@Composable
fun ConnectivityBanner(visible: Boolean, text: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = expandVertically(), exit = shrinkVertically(), modifier = modifier) {
        Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
