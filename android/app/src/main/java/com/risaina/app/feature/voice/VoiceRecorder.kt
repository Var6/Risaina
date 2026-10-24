package com.risaina.app.feature.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin, explicit wrapper over MediaRecorder. Recording starts only from an explicit user action
 * after RECORD_AUDIO is granted, stops automatically at the limit, and cancel deletes the file.
 * AAC in an MP4 container, mono, 64 kbps: small files that every Android device can play.
 */
@Singleton
class VoiceRecorder @Inject constructor(@ApplicationContext private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class Recording(val elapsedMs: Long, val amplitude: Int) : State
        data class Paused(val elapsedMs: Long) : State
        data class Recorded(val file: File, val durationMs: Long, val sizeBytes: Long) : State
        data class Error(val message: String) : State
    }

    companion object { const val MAX_DURATION_MS = 180_000 }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var segmentStart = 0L
    private var accumulated = 0L

    val supportsPause: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    fun start() {
        if (recorder != null) return
        val out = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setMaxDuration(MAX_DURATION_MS)
            r.setOutputFile(out.absolutePath)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop() }
            r.prepare()
            r.start()
            recorder = r; file = out
            accumulated = 0L; segmentStart = SystemClock.elapsedRealtime()
            _state.value = State.Recording(0, 0)
        } catch (e: Exception) {
            runCatching { r.release() }
            out.delete()
            _state.value = State.Error("Couldn't start recording")
        }
    }

    /** Called ~10×/s by the UI while recording to refresh elapsed time and level. */
    fun tick() {
        val r = recorder ?: return
        val s = _state.value
        if (s is State.Recording) {
            val elapsed = accumulated + (SystemClock.elapsedRealtime() - segmentStart)
            _state.value = State.Recording(elapsed, runCatching { r.maxAmplitude }.getOrDefault(0))
        }
    }

    fun pause() {
        val r = recorder ?: return
        if (!supportsPause || _state.value !is State.Recording) return
        r.pause()
        accumulated += SystemClock.elapsedRealtime() - segmentStart
        _state.value = State.Paused(accumulated)
    }

    fun resume() {
        val r = recorder ?: return
        if (_state.value !is State.Paused) return
        r.resume()
        segmentStart = SystemClock.elapsedRealtime()
        _state.value = State.Recording(accumulated, 0)
    }

    fun stop() {
        val r = recorder ?: return
        val f = file
        val elapsedGuess = when (val s = _state.value) {
            is State.Recording -> accumulated + (SystemClock.elapsedRealtime() - segmentStart)
            is State.Paused -> s.elapsedMs
            else -> 0L
        }
        try { r.stop() } catch (_: RuntimeException) { /* nothing recorded */ }
        r.release()
        recorder = null
        if (f == null || !f.exists() || f.length() == 0L) { _state.value = State.Error("Nothing was recorded"); return }
        val duration = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(f.absolutePath)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            } finally {
                mmr.release()
            }
        }.getOrNull() ?: elapsedGuess
        _state.value = State.Recorded(f, duration, f.length())
    }

    /** Discard any recording in progress or finished. Always deletes the temp file. */
    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete(); file = null
        _state.value = State.Idle
    }

    fun reset() { _state.value = State.Idle }
}
