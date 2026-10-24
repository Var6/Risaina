package com.risaina.app.feature.voice

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackState(val id: String? = null, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** One shared ExoPlayer for voice notes. All calls must happen on the main thread (Compose/VM scope). */
@Singleton
class VoicePlaybackController @Inject constructor(@ApplicationContext private val context: Context) {
    private var player: ExoPlayer? = null
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    private fun ensure(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _state.value = _state.value.copy(playing = isPlaying) }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) { p.pause(); p.seekTo(0); _state.value = _state.value.copy(playing = false, positionMs = 0) }
                if (playbackState == Player.STATE_READY) _state.value = _state.value.copy(durationMs = p.duration.coerceAtLeast(0))
            }
        })
        player = p
    }

    fun toggle(id: String, file: File) {
        val p = ensure()
        if (_state.value.id == id) {
            if (p.isPlaying) p.pause() else p.play()
            return
        }
        p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        p.prepare()
        p.play()
        _state.value = PlaybackState(id = id, playing = true, positionMs = 0, durationMs = 0)
    }

    /** Called by a UI ticker while something plays. */
    fun refresh() {
        val p = player ?: return
        if (_state.value.id != null) _state.value = _state.value.copy(positionMs = p.currentPosition, durationMs = p.duration.coerceAtLeast(0), playing = p.isPlaying)
    }

    fun seekTo(fraction: Float) { val p = player ?: return; if (p.duration > 0) p.seekTo((p.duration * fraction).toLong()) }

    fun stop() { player?.pause(); player?.seekTo(0); _state.value = PlaybackState() }

    fun release() { player?.release(); player = null; _state.value = PlaybackState() }
}
