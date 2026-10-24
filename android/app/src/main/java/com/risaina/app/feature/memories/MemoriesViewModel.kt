package com.risaina.app.feature.memories

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.data.ImageCompressor
import com.risaina.app.core.data.MediaRepository
import com.risaina.app.core.data.MemoryRepository
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.model.MediaCategory
import com.risaina.app.core.model.Memory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import javax.inject.Inject

data class MemoriesUiState(val memories: List<Memory> = emptyList(), val myId: String? = null, val uploading: Float? = null, val loaded: Boolean = false)

@HiltViewModel
class MemoriesViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val mediaRepository: MediaRepository,
    private val imageCompressor: ImageCompressor,
    sessionStore: SessionStore,
) : ViewModel() {
    private val uploading = MutableStateFlow<Float?>(null)
    private val loaded = MutableStateFlow(false)
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()

    val state: StateFlow<MemoriesUiState> = combine(memoryRepository.all, sessionStore.session.map { it?.userId }, uploading, loaded) { m, id, up, l ->
        MemoriesUiState(m, id, up, l)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoriesUiState())

    init { viewModelScope.launch { memoryRepository.refresh(); loaded.value = true } }

    fun memory(id: String): Flow<Memory?> = memoryRepository.observe(id)

    /** Local file if cached, otherwise a short-lived URL. Only called for items that are on screen. */
    suspend fun image(memory: Memory): Any? =
        mediaRepository.localFile(memory.mediaId) ?: mediaRepository.ensureCached(memory.mediaId, "image/jpeg").getOrNull() ?: mediaRepository.resolveUrl(memory.mediaId).getOrNull()

    suspend fun localFile(memory: Memory): File? = mediaRepository.localFile(memory.mediaId) ?: mediaRepository.ensureCached(memory.mediaId, "image/jpeg").getOrNull()

    fun add(uri: Uri, caption: String?, takenAt: Instant?, onDone: () -> Unit) {
        viewModelScope.launch {
            uploading.value = 0f
            try {
                val c = imageCompressor.compress(uri)
                mediaRepository.sizeError(MediaCategory.PHOTO, c.file.length())?.let { effects.trySend(it.message ?: "Too large"); return@launch }
                when (val up = mediaRepository.upload(MediaCategory.PHOTO, c.file, c.mimeType, width = c.width, height = c.height) { p -> uploading.value = p }) {
                    is AppResult.Success -> when (val r = memoryRepository.create(up.value.id, caption, takenAt)) {
                        is AppResult.Success -> onDone()
                        is AppResult.Failure -> effects.trySend(r.error.message ?: "Couldn't save")
                    }
                    is AppResult.Failure -> effects.trySend(up.error.message ?: "Upload failed")
                }
                c.file.delete()
            } catch (e: Exception) {
                effects.trySend("Couldn't read that photo")
            } finally { uploading.value = null }
        }
    }

    fun setPinned(m: Memory, pinned: Boolean) { viewModelScope.launch { memoryRepository.update(m.id, pinned = pinned).onFailure { effects.trySend(it.message ?: "Couldn't update") } } }
    fun setCaption(m: Memory, caption: String) { viewModelScope.launch { memoryRepository.update(m.id, caption = caption).onFailure { effects.trySend(it.message ?: "Couldn't update") } } }
    fun delete(m: Memory, onDone: () -> Unit) { viewModelScope.launch { when (val r = memoryRepository.delete(m.id)) { is AppResult.Success -> onDone(); is AppResult.Failure -> effects.trySend(r.error.message ?: "Couldn't delete") } } }
}
