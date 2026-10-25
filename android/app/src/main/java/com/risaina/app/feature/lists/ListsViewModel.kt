package com.risaina.app.feature.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.ListsRepository
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.model.ListItem
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.SharedList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pasting several lines into "add item" creates one item per line (bullets, numbering and [ ] stripped). */
object PasteParser {
    private val checkbox = Regex("^\\[[ xX]\\]\\s*")
    private val number = Regex("^\\d+[.)]\\s*")
    fun split(text: String): List<String> = text.lines()
        .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ").replace(checkbox, "").replace(number, "").trim() }
        .filter { it.isNotEmpty() }
        .take(200)
}

/** Ready-made lists that suit two people apart. Picking one creates a shared checklist. */
object ListTemplates {
    data class Template(val emoji: String, val title: String, val items: List<String>)
    val all = listOf(
        Template("✈️", "When we finally meet", listOf("Long hug at the gate", "Our first meal together", "Watch the sunset", "Take a photo together")),
        Template("🎬", "Watch together (on call)", listOf("A movie we've both never seen", "Rewatch our favourite", "A documentary")),
        Template("🗺️", "Places we'll go", listOf("A beach", "A mountain town", "A city neither of us knows")),
        Template("🎁", "Gift ideas for each other", emptyList()),
        Template("🏡", "Our future home", listOf("Lots of plants", "A reading corner")),
        Template("💬", "Questions to ask each other", listOf("What made you smile today?", "What do you miss most about me?", "Where shall we be in five years?")),
    )
}

data class ListsUiState(val lists: List<SharedList> = emptyList(), val loaded: Boolean = false, val myId: String? = null, val partnerName: String? = null)

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val repo: ListsRepository,
    sessionStore: SessionStore,
    coupleRepository: CoupleRepository,
) : ViewModel() {
    private val loaded = MutableStateFlow(false)
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()

    val state: StateFlow<ListsUiState> = combine(repo.all, loaded, sessionStore.session.map { it?.userId }, coupleRepository.partner) { l, ld, me, p ->
        ListsUiState(l, ld, me, p?.displayName)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState())

    init { viewModelScope.launch { repo.refresh(); loaded.value = true } }

    fun create(title: String, kind: ListKind, items: List<String> = emptyList(), onCreated: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = repo.create(title.ifBlank { if (kind == ListKind.NOTE) "Untitled note" else "Untitled list" }, kind, items = items)) {
                is AppResult.Success -> onCreated(r.value.id)
                is AppResult.Failure -> effects.trySend(r.error.message ?: "Couldn't create")
            }
        }
    }

    fun observe(id: String): Flow<SharedList?> = repo.observe(id)

    // ---- editing with debounced auto-save ----
    private var saveJob: Job? = null

    fun schedule(list: SharedList, title: String, body: String, items: List<ListItem>, pinned: Boolean, immediate: Boolean = false) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (!immediate) delay(700)
            val r = repo.save(list, title.ifBlank { list.title }, body, items.filter { it.text.isNotBlank() }, pinned)
            if (r is AppResult.Failure) effects.trySend(r.error.message ?: "Couldn't save")
        }
    }

    fun delete(id: String, onDone: () -> Unit) {
        viewModelScope.launch { when (val r = repo.delete(id)) { is AppResult.Success -> onDone(); is AppResult.Failure -> effects.trySend(r.error.message ?: "Couldn't delete") } }
    }

    fun splitPasted(text: String): List<String> = PasteParser.split(text)
}
