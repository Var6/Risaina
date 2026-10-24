package com.risaina.app.feature.wallpaper

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.MediaRepository
import com.risaina.app.core.data.MemoryRepository
import com.risaina.app.core.data.MessageRepository
import com.risaina.app.core.data.PingRepository
import com.risaina.app.core.model.Memory
import com.risaina.app.core.model.MessageType
import com.risaina.app.feature.common.Format
import com.risaina.app.navigation.WallpaperStudio
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

enum class ApplyTarget { HOME, LOCK, BOTH }

data class WallpaperUiState(
    val memories: List<Memory> = emptyList(),
    val selected: Memory? = null,
    val photo: File? = null,
    val note: String = "",
    val emoji: String = "",
    val showDate: Boolean = true,
    val dateText: String = "",
    val preview: Bitmap? = null,
    val rendering: Boolean = false,
    val busy: Boolean = false,
    val suggestions: List<String> = emptyList(),
)

@OptIn(FlowPreview::class)
@HiltViewModel
class WallpaperViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val renderer: WallpaperRenderer,
    private val memoryRepository: MemoryRepository,
    private val mediaRepository: MediaRepository,
    private val messageRepository: MessageRepository,
    private val pingRepository: PingRepository,
    private val coupleRepository: CoupleRepository,
) : ViewModel() {
    private val local = MutableStateFlow(WallpaperUiState(dateText = Format.date(LocalDate.now())))
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()
    private val screen: Pair<Int, Int> by lazy {
        val m = context.resources.displayMetrics
        m.widthPixels to m.heightPixels
    }

    val state: StateFlow<WallpaperUiState> = combine(local, memoryRepository.all) { l, mems -> l.copy(memories = mems) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WallpaperUiState())

    init {
        val initialId = runCatching { savedState.toRoute<WallpaperStudio>().memoryId }.getOrNull()
        viewModelScope.launch {
            val mems = memoryRepository.all.first()
            val start = mems.firstOrNull { it.id == initialId } ?: mems.firstOrNull { it.pinned } ?: mems.firstOrNull()
            val notes = messageRepository.recent(40).first().filter { it.type == MessageType.TEXT && !it.text.isNullOrBlank() }.map { it.text!! }.take(3)
            val pingMsgs = pingRepository.recent.first().mapNotNull { it.message }.take(2)
            val couple = coupleRepository.couple.first()
            val dateText = couple?.relationshipStartDate?.let { "Since ${Format.date(it)}" } ?: Format.date(LocalDate.now())
            local.update { it.copy(suggestions = (notes + pingMsgs).distinct(), note = notes.firstOrNull() ?: "", dateText = dateText) }
            if (start != null) select(start)
        }
        viewModelScope.launch {
            local.debounce(250).collect { s -> renderPreview(s) }
        }
    }

    fun select(m: Memory) {
        viewModelScope.launch {
            val file = mediaRepository.localFile(m.mediaId) ?: mediaRepository.ensureCached(m.mediaId, "image/jpeg").getOrNull()
            local.update { it.copy(selected = m, photo = file) }
        }
    }
    fun clearPhoto() = local.update { it.copy(selected = null, photo = null) }
    fun onNote(v: String) = local.update { it.copy(note = v.take(220)) }
    fun onEmoji(v: String) = local.update { it.copy(emoji = v.take(4)) }
    fun onShowDate(v: Boolean) = local.update { it.copy(showDate = v) }

    private var lastRenderKey: Any? = null
    private suspend fun renderPreview(s: WallpaperUiState) {
        val key = listOf(s.photo?.absolutePath, s.note, s.emoji, s.showDate, s.dateText)
        if (key == lastRenderKey) return
        lastRenderKey = key
        local.update { it.copy(rendering = true) }
        val (w, h) = screen
        val bmp = renderer.render(spec(s), w / 3, h / 3)
        local.update { it.copy(preview = bmp, rendering = false) }
    }

    private fun spec(s: WallpaperUiState) = WallpaperSpec(photo = s.photo, note = s.note.ifBlank { null }, emoji = s.emoji.ifBlank { null }, dateText = if (s.showDate) s.dateText else null)

    /** Saves a full-resolution PNG to Pictures/Risaina. */
    fun save() {
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val (w, h) = screen
            val bmp = renderer.render(spec(local.value), w, h)
            val ok = withContext(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "risaina-${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Risaina")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
            }
            bmp.recycle()
            effects.trySend(if (ok) "Saved to Pictures/Risaina" else "Couldn't save")
            local.update { it.copy(busy = false) }
        }
    }

    /** Applies only after the explicit confirmation in the UI. */
    fun apply(target: ApplyTarget) {
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val (w, h) = screen
            val bmp = renderer.render(spec(local.value), w, h)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val wm = WallpaperManager.getInstance(context)
                    val flags = when (target) { ApplyTarget.HOME -> WallpaperManager.FLAG_SYSTEM; ApplyTarget.LOCK -> WallpaperManager.FLAG_LOCK; ApplyTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK }
                    if (!wm.isSetWallpaperAllowed) error("Setting wallpaper isn't allowed on this device profile")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) wm.setBitmap(bmp, null, true, flags) else wm.setBitmap(bmp)
                }
            }
            bmp.recycle()
            effects.trySend(result.fold({ "Wallpaper applied" }, { it.message ?: "Couldn't apply wallpaper" }))
            local.update { it.copy(busy = false) }
        }
    }
}
