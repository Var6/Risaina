package com.risaina.app.feature.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.feature.common.CheckIcon
import com.risaina.app.feature.common.CloseIcon
import com.risaina.app.feature.common.Format
import com.risaina.app.feature.common.MicIcon
import com.risaina.app.feature.common.PauseIcon
import com.risaina.app.feature.common.PlayIcon
import com.risaina.app.feature.common.StopIcon
import kotlinx.coroutines.delay

/**
 * Record → (pause/resume) → stop → preview → send or re-record. The microphone permission is
 * requested the moment the user taps record, never earlier. Cancelling deletes the file.
 */
@Composable
fun VoiceRecorderSheet(
    recorderState: VoiceRecorder.State,
    supportsPause: Boolean,
    playback: PlaybackState,
    uploading: Float?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onPreviewToggle: () -> Unit,
    onReRecord: () -> Unit,
    onSend: () -> Unit,
    onTick: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) onStart() }
    val levels = remember { mutableStateListOf<Float>() }

    LaunchedEffect(recorderState is VoiceRecorder.State.Recording) {
        while (recorderState is VoiceRecorder.State.Recording) { onTick(); delay(100) }
    }
    LaunchedEffect(recorderState) {
        if (recorderState is VoiceRecorder.State.Recording) {
            levels.add((recorderState.amplitude / 12000f).coerceIn(0.06f, 1f))
            if (levels.size > 48) levels.removeAt(0)
        }
        if (recorderState is VoiceRecorder.State.Idle) levels.clear()
    }

    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (recorderState) {
                    VoiceRecorder.State.Idle -> "Voice note"
                    is VoiceRecorder.State.Recording -> "Recording"
                    is VoiceRecorder.State.Paused -> "Paused"
                    is VoiceRecorder.State.Recorded -> "Ready to send"
                    is VoiceRecorder.State.Error -> "Something went wrong"
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(4.dp))
            val elapsed = when (recorderState) {
                is VoiceRecorder.State.Recording -> recorderState.elapsedMs
                is VoiceRecorder.State.Paused -> recorderState.elapsedMs
                is VoiceRecorder.State.Recorded -> recorderState.durationMs
                else -> 0L
            }
            Text("${Format.duration(elapsed)} / ${Format.duration(VoiceRecorder.MAX_DURATION_MS.toLong())}", style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
            Spacer(Modifier.height(20.dp))

            // Level bars (recording) or progress (preview)
            val barPlayed = MaterialTheme.colorScheme.primary
            val barIdle = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            val progressAnim by animateFloatAsState(if (recorderState is VoiceRecorder.State.Recorded) playback.progress else 0f, label = "progress")
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                val barW = 4.dp.toPx(); val gap = 3.dp.toPx(); val count = 48
                val total = count * (barW + gap)
                val startX = (size.width - total) / 2f
                val recorded = recorderState is VoiceRecorder.State.Recorded
                for (i in 0 until count) {
                    val level = if (recorded) 0.25f + 0.55f * kotlin.math.abs(kotlin.math.sin(i * 0.7f)) else levels.getOrNull(i) ?: 0.06f
                    val h = size.height * level
                    val x = startX + i * (barW + gap) + barW / 2
                    val played = recorded && (i.toFloat() / count) <= progressAnim
                    drawLine(
                        color = if (played) barPlayed else barIdle,
                        start = Offset(x, size.height / 2 - h / 2), end = Offset(x, size.height / 2 + h / 2),
                        strokeWidth = barW, cap = StrokeCap.Round,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            when (recorderState) {
                VoiceRecorder.State.Idle, is VoiceRecorder.State.Error -> {
                    if (recorderState is VoiceRecorder.State.Error) Text(recorderState.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    FilledIconButton(onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors()) {
                        Icon(MicIcon, contentDescription = "Start recording", Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Tap to start. Nothing is recorded until you do.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                }
                is VoiceRecorder.State.Recording, is VoiceRecorder.State.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel) { Icon(CloseIcon, contentDescription = "Cancel recording") }
                    if (supportsPause) {
                        FilledIconButton(onClick = { if (recorderState is VoiceRecorder.State.Recording) onPause() else onResume() }, modifier = Modifier.size(60.dp), colors = IconButtonDefaults.filledTonalIconButtonColors()) {
                            Icon(if (recorderState is VoiceRecorder.State.Recording) PauseIcon else MicIcon, contentDescription = if (recorderState is VoiceRecorder.State.Recording) "Pause" else "Resume")
                        }
                    }
                    FilledIconButton(onClick = onStop, modifier = Modifier.size(72.dp)) { Icon(StopIcon, contentDescription = "Stop recording", Modifier.size(28.dp)) }
                }
                is VoiceRecorder.State.Recorded -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FilledIconButton(onClick = onPreviewToggle, modifier = Modifier.size(56.dp), colors = IconButtonDefaults.filledTonalIconButtonColors()) {
                            Icon(if (playback.playing) PauseIcon else PlayIcon, contentDescription = if (playback.playing) "Pause preview" else "Play preview")
                        }
                        Text(if (playback.playing) "Playing…" else "Preview", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton(
                        text = if (uploading != null) "Sending ${(uploading * 100).toInt()}%" else "Send voice note",
                        loading = uploading != null, onClick = onSend, modifier = Modifier.fillMaxWidth(),
                        leading = { Icon(CheckIcon, null, Modifier.size(18.dp)) },
                    )
                    Row { GhostButton("Re-record", onClick = onReRecord); Spacer(Modifier.width(8.dp)); GhostButton("Discard", onClick = onCancel) }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
