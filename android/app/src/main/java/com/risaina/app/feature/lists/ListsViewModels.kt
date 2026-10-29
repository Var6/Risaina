package com.risaina.app.feature.lists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.ListsRepository
import com.risaina.app.core.data.TodoAppend
import com.risaina.app.core.data.lists.DocItem
import com.risaina.app.core.data.lists.EditorSnapshot
import com.risaina.app.core.data.lists.ListDoc
import com.risaina.app.core.data.lists.ListNotice
import com.risaina.app.core.data.lists.ListSyncEngine
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.ListMarker
import com.risaina.app.navigation.ListDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The kinds the New sheet offers. All but the note are checklists on the server, told apart by their format. */
enum class NewKind(val label: String, val glyph: String) {
    TODO("To-do list", "☑️"), NUMBERED("Numbered list", "🔢"), BULLETED("Bulleted list", "📋"), NOTE("Note", "📝");

    fun doc(): ListDoc = when (this) {
        TODO -> ListDoc(kind = ListKind.CHECKLIST.wire, marker = ListMarker.NONE.wire, checkboxes = true)
        NUMBERED -> ListDoc(kind = ListKind.CHECKLIST.wire, marker = ListMarker.NUMBER.wire, checkboxes = false)
        BULLETED -> ListDoc(kind = ListKind.CHECKLIST.wire, marker = ListMarker.BULLET.wire, checkboxes = false)
        NOTE -> ListDoc(kind = ListKind.NOTE.wire)
    }
}

/** A notice from the sync engine, in the couple's words. */
internal fun noticeText(n: ListNotice, partnerName: String?): String = when (n) {
    is ListNotice.BothKept -> "${partnerName?.takeIf { it.isNotBlank() } ?: "Your partner"} edited this note too. Both versions are kept."
    is ListNotice.DeletedBeforeSaved -> "“${n.title}” was deleted before it finished saving."
}

data class OverviewUi(
    val loaded: Boolean = false,
    val pinned: List<ListCard> = emptyList(),
    val others: List<ListCard> = emptyList(),
    /** Every list, before filters: the empty state and whether search shows. */
    val total: Int = 0,
    val filter: ListFilter = ListFilter.ALL,
    val query: String = "",
)

/**
 * The overview (lists.md E). The one screen that fetches every list (once per visit); cards show
 * this phone's unsent drafts over the server copies.
 */
@HiltViewModel
class ListsOverviewViewModel @Inject constructor(
    private val repo: ListsRepository,
    private val engine: ListSyncEngine,
    sessionStore: SessionStore,
    coupleRepository: CoupleRepository,
    private val time: TimeProvider,
) : ViewModel() {
    private val loaded = MutableStateFlow(false)
    private val filter = MutableStateFlow(ListFilter.ALL)
    private val query = MutableStateFlow("")
    private val effects = Channel<String>(Channel.BUFFERED)
    private val people: StateFlow<People> = combine(sessionStore.session.map { it?.userId }, coupleRepository.partner) { me, p -> People(me, p?.id, p?.displayName) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, People(null, null, null))

    /** Collected while this screen is in the back stack, so a notice sent while the editor was open is not lost. */
    val messages: Flow<String> = effects.receiveAsFlow()

    val state: StateFlow<OverviewUi> = combine(engine.overview, people, loaded, filter, query) { entries, ppl, ld, f, q ->
        val now = time.now().toEpochMilli()
        val shown = entries.filter { ListPreview.matches(it, f, q) }.map { ListPreview.card(it, ppl, now) }
        OverviewUi(ld || entries.isNotEmpty(), shown.filter { it.pinned }, shown.filterNot { it.pinned }, entries.size, f, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewUi())

    init {
        refresh()
        viewModelScope.launch {
            // Kept in Room until said here, so one that came while Lists was closed is said when it opens.
            val said = HashSet<String>()
            engine.dropped.collect { notices ->
                for (n in notices) {
                    if (said.add(n.listId)) effects.trySend(noticeText(n, people.value.partnerName))
                    engine.acknowledge(n)
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val r = repo.refresh()
            loaded.value = true
            // Offline is fine: the lists on this phone are shown, and the connection banner says it.
            (r as? AppResult.Failure)?.error?.takeIf { it !is AppError.Offline && it !is AppError.Timeout }?.let { effects.trySend("Couldn't refresh your lists") }
        }
    }

    fun setFilter(f: ListFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }

    /** A new list exists at once on this phone; the editor opens on it. */
    fun create(kind: NewKind): String = engine.createLocal(kind.doc())

    fun fromTemplate(t: ListTemplates.Template): String =
        engine.createLocal(NewKind.TODO.doc().copy(title = t.title, items = t.items.map { DocItem(TodoAppend.newId(), it) }))

    fun retry(id: String) = engine.retry(id)
}

/** What the editor screen draws: the engine's snapshot plus who is who. */
data class EditorUi(val snapshot: EditorSnapshot?, val people: People)

/**
 * The list editor (lists.md D), one per open list (its own instance: the overview's refresh is not
 * repeated here). Every edit goes to ListSyncEngine as a change by row id; the engine owns saving.
 */
@HiltViewModel
class ListEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val engine: ListSyncEngine,
    sessionStore: SessionStore,
    coupleRepository: CoupleRepository,
    private val time: TimeProvider,
) : ViewModel() {
    /** Follows the list to a new id ("Keep as a new list"); a created list keeps working under its old id. */
    private val listId = MutableStateFlow(savedState.toRoute<ListDetail>().id)
    val id: String get() = listId.value

    private val people: StateFlow<People> = combine(sessionStore.session.map { it?.userId }, coupleRepository.partner) { me, p -> People(me, p?.id, p?.displayName) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, People(null, null, null))

    val state: StateFlow<EditorUi> = combine(listId.flatMapLatest { engine.observe(it) }, people) { s, p -> EditorUi(s, p) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, EditorUi(null, People(null, null, null)))

    private val effects = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            engine.notices.filter { it is ListNotice.BothKept && (it.listId == id || it.listId == state.value.snapshot?.listId) }
                .collect { effects.trySend(noticeText(it, people.value.partnerName)) }
        }
    }

    /** Where the cursor should go next (a row split, a join, a new row); the screen clears it once applied. */
    val caret = MutableStateFlow<Caret?>(null)
    /** The last deleted row, for the snackbar's Undo. */
    private var removed: RemovedRow? = null

    private fun now() = time.now().toEpochMilli()
    private fun edit(change: (ListDoc) -> ListDoc) = engine.edit(id, change)

    /**
     * A row's id now. A new list's rows take the server's ids when it is created; the screen keeps
     * their first ids as keys, and a keystroke can come from a field drawn before the change.
     */
    private fun rid(rowId: String) = engine.rowId(id, rowId)

    /** The engine's newest text for a row: a field only takes a text from the flow when it is still the newest. */
    fun latestRowText(rowId: String): String? = rid(rowId).let { r -> engine.current(id)?.items?.firstOrNull { it.id == r }?.text }
    fun latestTitle(): String? = engine.current(id)?.title
    fun latestBody(): String? = engine.current(id)?.body

    fun setTitle(t: String) = edit { it.copy(title = t.take(com.risaina.app.core.model.ListLimits.MAX_TITLE)) }

    /**
     * The note's text, with list continuation on Enter. Returns the text and cursor the field should
     * show. The change is made on the engine's newest body, which may hold a partner's merged lines
     * the field has not drawn yet; the field takes that body once the flow brings it.
     */
    fun setBody(old: String, new: String, cursor: Int): NoteEdits.Edit {
        val e = NoteEdits.typed(old, new, cursor).let { if (it.text.length > com.risaina.app.core.model.ListLimits.MAX_BODY) NoteEdits.Edit(old, cursor - 1) else it }
        edit { it.copy(body = NoteEdits.onto(it.body, old, e.text)) }
        return e
    }

    fun toggleNoteList(text: String, selStart: Int, selEnd: Int, kind: NoteEdits.Kind): NoteEdits.Edit {
        val e = NoteEdits.toggleList(text, selStart, selEnd, kind)
        edit { it.copy(body = NoteEdits.onto(it.body, text, e.text)) }
        return e
    }

    /** A row's text changed: Enter splits, a paste makes rows. */
    fun typed(rowId: String, text: String) {
        var result: RowEdit? = null
        val r = rid(rowId)
        edit { d -> RowEdits.typed(d, r, text).also { result = it }.doc }
        result?.let { r ->
            r.caret?.let { caret.value = it }
            if (r.leftOut > 0) effects.trySend(leftOutText(r.leftOut))
        }
    }

    fun backspaceAtStart(rowId: String) {
        var result: RowEdit? = null
        val r = rid(rowId)
        edit { d -> RowEdits.joinWithPrevious(d, r).also { result = it }.doc }
        result?.caret?.let { caret.value = it }
    }

    fun addRow() {
        var result: RowEdit? = null
        edit { d -> RowEdits.addRow(d).also { result = it }.doc }
        result?.let { r ->
            r.caret?.let { caret.value = it }
            if (r.leftOut > 0) effects.trySend(leftOutText(r.leftOut))
        }
    }

    fun toggle(rowId: String, done: Boolean) = rid(rowId).let { r -> edit { RowEdits.toggle(it, r, done, people.value.me, now()) } }

    /** Deletes a row; true when there is something to undo. */
    fun remove(rowId: String): Boolean {
        var r: RemovedRow? = null
        val row = rid(rowId)
        edit { d -> RowEdits.remove(d, row).also { r = it.second }.first }
        removed = r
        return r != null
    }

    fun undoRemove() {
        val r = removed ?: return
        removed = null
        edit { RowEdits.restore(it, r) }
    }

    fun move(rowId: String, to: Int) = rid(rowId).let { r -> edit { RowEdits.move(it, r, to) } }

    /** A drag passed over [targetId]: the dragged row takes its place. Both are screen keys. */
    fun moveOnto(rowId: String, targetId: String) {
        val r = rid(rowId)
        val target = rid(targetId)
        edit { d ->
            val to = d.items.indexOfFirst { it.id == target }
            if (to < 0) d else RowEdits.move(d, r, to)
        }
    }

    /** The title's Next key: on to the first row (one is made if there is none) or the note's text. */
    fun focusFirstRow() {
        val first = engine.current(id)?.items?.firstOrNull()
        if (first != null) caret.value = Caret(first.id, first.text.length) else addRow()
    }
    fun setPinned(p: Boolean) = edit { it.copy(pinned = p) }
    fun setCheckboxes(on: Boolean) = edit { it.copy(checkboxes = on) }
    /** Numbers and bullets are one choice: picking the one already on turns it off. */
    fun setMarker(m: ListMarker) = edit { d -> d.copy(marker = if (d.marker == m.wire) ListMarker.NONE.wire else m.wire) }
    fun uncheckAll() = edit(RowEdits::uncheckAll)
    fun deleteTicked() = edit(RowEdits::deleteTicked)

    /** A checklist's text that is not rows yet (ListEditorScreen's strip), as rows at the end. */
    fun moveTextIntoItems() {
        var result: RowEdit? = null
        edit { d -> RowEdits.textToRows(d).also { result = it }.doc }
        result?.leftOut?.takeIf { it > 0 }?.let { effects.trySend("A list holds up to 200 items. The rest stays as text.") }
    }

    /**
     * The rows a note has (an older app saved items onto it after it became a note, or they were
     * added while the partner turned the list into a note), as lines.
     */
    fun moveOldItemsIntoNote() = edit { d ->
        val lines = d.items.filter { it.text.isNotBlank() }.joinToString("\n") { (if (it.done) "☑ " else "☐ ") + it.text.trim() }
        d.copy(body = listOf(d.body.trimEnd(), lines).filter { it.isNotEmpty() }.joinToString("\n"), items = emptyList())
    }

    fun convert(to: ListKind) {
        viewModelScope.launch {
            when (val r = engine.convert(id, to)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> effects.trySend(r.error.message ?: "Couldn't change it right now")
            }
        }
    }

    fun deleteList(onDone: () -> Unit) {
        viewModelScope.launch {
            when (val r = engine.delete(id)) {
                is AppResult.Success -> onDone()
                is AppResult.Failure -> effects.trySend(if (r.error is AppError.Offline) "You're offline. Try deleting it again when you're connected." else r.error.message ?: "Couldn't delete")
            }
        }
    }

    fun retry() = engine.retry(id)
    fun reload() = engine.reload(id)
    fun say(text: String) { effects.trySend(text) }

    /** The list was deleted while this phone had changes: save them as a new list. */
    fun keepAsNew() { engine.keepAsNew(id)?.let { listId.value = it } }
    fun discardChanges() = engine.discardChanges(id)

    /** Leaving: the draft is written and sent now, on the engine's scope (it outlives this screen). */
    fun leave() = engine.release(id)

    override fun onCleared() {
        // Also when the screen is removed without the back button (a notification opening Home).
        engine.release(id)
    }

    private fun leftOutText(n: Int) = if (n == 1) "A list holds up to 200 items." else "A list holds up to 200 items. $n lines were left out."
}
