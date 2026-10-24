package com.risaina.app.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.theme.Risaina

/**
 * Permission education, shown once after pairing and never at launch. Explains what the
 * permission is for before the system prompt, and accepts "not now" without nagging.
 */
@Composable
fun NotificationPermissionScreen(onDone: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Mark(Modifier.size(96.dp))
        Spacer(Modifier.height(28.dp))
        Text("Let their pings reach you", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Risaina only notifies you about what your partner sends. You choose quiet hours, whether previews are shown, and you can change your mind any time in Settings.",
            style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        PrimaryButton(
            text = "Allow notifications",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        GhostButton(text = "Not now", onClick = onDone)
    }
}
