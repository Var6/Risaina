package com.risaina.app.feature.lists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.data.lists.DocItem
import com.risaina.app.core.data.lists.EditorSnapshot
import com.risaina.app.core.data.lists.ListDoc
import com.risaina.app.core.data.lists.ListWire
import com.risaina.app.core.data.lists.SyncStatus
import com.risaina.app.core.designsystem.components.EmptyState
import com.risaina.app.core.designsystem.components.ErrorState
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.LoadingState
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.theme.PillShape
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.ListLimits
import com.risaina.app.core.model.ListMarker
import com.risaina.app.feature.common.AddIcon
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.CalendarIcon
import com.risaina.app.feature.common.DragHandleIcon
import com.risaina.app.feature.common.MoreIcon
import com.risaina.app.feature.common.PushPinIcon
import com.risaina.app.navigation.CalendarEntryEdit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ROW_KEY = "row:"

/**
 * The list editor (lists.md D): rows you type into like Keep (Enter starts the next row, Backspace
 * at the start joins it to the one above, a pasted list becomes rows), checkboxes, numbers or
 * bullets, who ticked what, reorder by dragging the handle (or Move up / Move down), and a line
 * that always says whether what is on screen is saved. A note is plain text with list continuation.
 */
@Composable
fun ListEditorScreen(onBack: () -> Unit, onAddToCalendar: ((CalendarEntryEdit) -> Unit)?, vm: ListEditorViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    fun leave() { vm.leave(); onBack() }
    BackHandler(onBack = ::leave)
    val snap = ui.snapshot
    val editable = snap != null && snap.loaded && snap.status !is SyncStatus.Gone
    // A new list that is gone (deleted before it finished saving, which a snackbar says, or saved
    // under its server id while the app was away): back to the overview, which has the truth.
    LaunchedEffect(snap?.status, snap?.isLocal) { if (snap?.isLocal == true && snap.status == SyncStatus.Gone) onBack() }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Body selection, for the note's format bar and "Add to calendar".
    var noteValue by remember { mutableStateOf<TextFieldValue?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (snap != null && editable) StatusLine(snap, onRetry = vm::retry) },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(BackIcon, contentDescription = "Back") } },
                actions = {
                    if (editable) {
                        val pinned = snap.doc.pinned
                        IconButton(onClick = { vm.setPinned(!pinned) }) {
                            Icon(PushPinIcon, contentDescription = if (pinned) "Unpin" else "Pin", tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(MoreIcon, contentDescription = "More options") }
                            EditorMenu(snap, expanded = menu, onDismiss = { menu = false }, vm = vm, onDelete = { confirmDelete = true })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (editable) FormatBar(
                doc = snap.doc,
                vm = vm,
                onNoteList = { kind ->
                    val v = noteValue ?: TextFieldValue(snap.doc.body)
                    val e = vm.toggleNoteList(v.text, v.selection.start, v.selection.end, kind)
                    noteValue = TextFieldValue(e.text, TextRange(e.cursor))
                },
                onNoteCalendar = onAddToCalendar?.let { add ->
                    {
                        val v = noteValue ?: TextFieldValue(snap.doc.body)
                        val title = NoteEdits.calendarTitle(v.text, v.selection.start, v.selection.end)
                        if (title == null) vm.say("Put the cursor on a line (or select some words) first.")
                        else add(CalendarEntryEdit(title = title, sourceKind = "list", sourceListId = snap.listId.takeUnless { snap.isLocal }))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            snap == null || (!snap.loaded && snap.loadError == null) -> LoadingState(Modifier.padding(padding))
            snap.loadError != null -> ErrorState("Couldn't open this list", "Check your connection and try again.", Modifier.padding(padding), onRetry = vm::reload)
            snap.status is SyncStatus.Gone -> EmptyState(
                title = "This list was deleted", body = "It isn't on either phone any more.", modifier = Modifier.padding(padding),
                action = { OutlineButton("Back to lists", onClick = ::leave) },
            )
            else -> EditorBody(snap, ui.people, vm, Modifier.padding(padding), onAddToCalendar, onNoteValue = { noteValue = it })
        }
    }

    if (confirmDelete && snap != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${ListWire.title(snap.doc)}”?") },
        text = { Text("It disappears for both of you.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteList(onDone = onBack) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
}

/** Saved / Saving… / Saved on this phone, will send when online / Couldn't save — tap to retry. */
@Composable
private fun StatusLine(snap: EditorSnapshot, onRetry: () -> Unit) {
    val (text, tappable) = when (snap.status) {
        SyncStatus.Saved -> (if (snap.isLocal && !ListWire.hasContent(snap.doc)) "" else "Saved") to false
        SyncStatus.Saving -> "Saving…" to false
        SyncStatus.Offline -> "Saved on this phone, will send when online" to true
        is SyncStatus.Failed -> "Couldn't save — tap to retry" to true
        SyncStatus.Orphaned -> "Not saved" to false
        SyncStatus.Gone -> "" to false
    }
    val color = if (snap.status is SyncStatus.Failed) Risaina.colors.danger else Risaina.colors.textMuted
    Box(
        if (tappable) Modifier.heightIn(min = 48.dp).clip(PillShape).clickable(onClickLabel = "Try saving again", onClick = onRetry) else Modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 2)
    }
}

@Composable
private fun EditorMenu(snap: EditorSnapshot, expanded: Boolean, onDismiss: () -> Unit, vm: ListEditorViewModel, onDelete: () -> Unit) {
    val doc = snap.doc
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (doc.isNote) {
            DropdownMenuItem(text = { Text("Turn into checklist") }, onClick = { onDismiss(); vm.convert(ListKind.CHECKLIST) })
        } else {
            DropdownMenuItem(text = { Text("Turn into a note") }, onClick = { onDismiss(); vm.convert(ListKind.NOTE) })
            if (doc.items.any { it.done }) {
                DropdownMenuItem(text = { Text("Untick everything") }, onClick = { onDismiss(); vm.uncheckAll() })
                DropdownMenuItem(text = { Text("Delete ticked items") }, onClick = { onDismiss(); vm.deleteTicked() })
            }
        }
        if (snap.status is SyncStatus.Failed) {
            DropdownMenuItem(text = { Text("Undo my unsaved changes") }, onClick = { onDismiss(); vm.discardChanges() })
        }
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Delete list", color = Risaina.colors.danger) }, onClick = { onDismiss(); onDelete() })
    }
}

@Composable
private fun EditorBody(
    snap: EditorSnapshot,
    people: People,
    vm: ListEditorViewModel,
    modifier: Modifier,
    onAddToCalendar: ((CalendarEntryEdit) -> Unit)?,
    onNoteValue: (TextFieldValue) -> Unit,
) {
    val doc = snap.doc
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState,
        isRow = { (it as? String)?.startsWith(ROW_KEY) == true },
        onMove = { from, to -> vm.moveOnto((from as String).removePrefix(ROW_KEY), (to as String).removePrefix(ROW_KEY)) },
    )
    val caret by vm.caret.collectAsStateWithLifecycle()
    val now = rememberNow()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 24.dp)) {
        item(key = "title") {
            TitleField(doc, vm, autoFocus = snap.isLocal && !ListWire.hasContent(doc), onNext = { if (doc.isNote) firstFocus.requestFocus() else vm.focusFirstRow() })
        }
        if (snap.status == SyncStatus.Orphaned) item(key = "orphan") { OrphanBanner(vm) }
        (snap.status as? SyncStatus.Failed)?.let { f -> item(key = "failed") { FailedBanner(f.reason, vm) } }
        if (doc.isNote) {
            item(key = "body") { NoteBody(doc, vm, firstFocus, onNoteValue) }
            if (doc.items.any { it.text.isNotBlank() }) item(key = "old-items") { OldItemsStrip(doc, vm) }
        } else {
            // Text a checklist has (a note's lines past 200, or written as it became a checklist) is never hidden.
            if (doc.body.isNotBlank()) item(key = "text-lines") { TextLinesStrip(doc, vm) }
            // Keyed by the row's first id: a new list's rows take the server's ids when it is created,
            // and a new key would rebuild the row being typed in (focus and keyboard lost mid-word).
            itemsIndexed(doc.items, key = { _, r -> ROW_KEY + snap.keyOf(r.id) }) { i, row ->
                RowItem(
                    row = row, key = ROW_KEY + snap.keyOf(row.id), index = i, count = doc.items.size, doc = doc, people = people, vm = vm, reorder = reorder, now = now,
                    caret = caret?.takeIf { it.rowId == row.id || it.rowId == snap.keyOf(row.id) },
                    onAddToCalendar = onAddToCalendar?.let { add ->
                        {
                            add(
                                CalendarEntryEdit(
                                    title = row.text.trim().take(CALENDAR_TITLE_MAX), sourceKind = "list_item",
                                    sourceListId = snap.listId.takeUnless { snap.isLocal }, sourceItemId = row.id.takeUnless { snap.isLocal },
                                ),
                            )
                        }
                    },
                    onDelete = {
                        if (vm.remove(row.id)) scope.launch {
                            val r = snackbar.showSnackbar("Item deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
                            if (r == SnackbarResult.ActionPerformed) vm.undoRemove()
                        }
                    },
                )
            }
            item(key = "add") { AddRowButton(onClick = vm::addRow) }
        }
    }
}

private const val CALENDAR_TITLE_MAX = 120

/** A "now" that moves on every minute, so "3m" under a ticked row stays true while the list is open. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    return now
}

@Composable
private fun TitleField(doc: ListDoc, vm: ListEditorViewModel, autoFocus: Boolean, onNext: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(doc.title, TextRange(doc.title.length))) }
    val focus = remember { FocusRequester() }
    // A list just made from the New sheet starts with the keyboard on its title.
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    // A title from elsewhere (the partner, a merge) is taken only while it is the newest one.
    LaunchedEffect(doc.title) { if (doc.title != value.text && vm.latestTitle() == doc.title) value = TextFieldValue(doc.title, TextRange(doc.title.length)) }
    val style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = value,
        onValueChange = { v ->
            val clean = if ('\n' in v.text) v.copy(text = v.text.replace('\n', ' ')) else v
            val before = value.text
            value = if (clean.text.length > ListLimits.MAX_TITLE) value else clean
            if (value.text != before) vm.setTitle(value.text)
        },
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).focusRequester(focus).semantics { contentDescription = "Title" },
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) Text("Untitled", style = style, color = Risaina.colors.textMuted)
                inner()
            }
        },
    )
}

@Composable
private fun NoteBody(doc: ListDoc, vm: ListEditorViewModel, focus: FocusRequester, onValue: (TextFieldValue) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(doc.body, TextRange(doc.body.length))) }
    LaunchedEffect(doc.body) {
        if (doc.body != value.text && vm.latestBody() == doc.body) {
            value = TextFieldValue(doc.body, TextRange(value.selection.end.coerceAtMost(doc.body.length)))
            onValue(value)
        }
    }
    val style = RisainaTextStyles.noteSerif.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = value,
        onValueChange = { v ->
            value = if (v.text == value.text) v else {
                val e = vm.setBody(value.text, v.text, v.selection.start)
                // Unchanged by list continuation: keep the keyboard's own value (its composing word too).
                if (e.text == v.text) v else TextFieldValue(e.text, TextRange(e.cursor))
            }
            onValue(value)
        },
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(horizontal = 12.dp, vertical = 4.dp).focusRequester(focus)
            .semantics { contentDescription = "Note" },
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) Text("Write or paste anything…", style = style, color = Risaina.colors.textMuted)
                inner()
            }
        },
    )
}

/**
 * Items on what is now a note (lists.md G: an older app saved them onto it, or they were added while
 * the partner turned the list into a note): shown, and can be added to the text.
 */
@Composable
private fun OldItemsStrip(doc: ListDoc, vm: ListEditorViewModel) {
    var open by remember { mutableStateOf(false) }
    val items = doc.items.filter { it.text.isNotBlank() }
    RisainaCard(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), contentPadding = PaddingValues(16.dp)) {
        val n = items.size
        Text("$n list item${if (n == 1) " isn't" else "s aren't"} in the note yet", style = MaterialTheme.typography.bodyMedium)
        if (open) {
            Spacer(Modifier.height(8.dp))
            items.forEach { Text((if (it.done) "☑ " else "☐ ") + it.text, style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(if (open) "Hide" else "Show", onClick = { open = !open })
            GhostButton("Add them to the note", onClick = vm::moveOldItemsIntoNote)
        }
    }
}

/** A checklist's text that is not rows: shown, and can be turned into items. */
@Composable
private fun TextLinesStrip(doc: ListDoc, vm: ListEditorViewModel) {
    var open by remember { mutableStateOf(false) }
    val lines = doc.body.lines().filter { it.isNotBlank() }
    RisainaCard(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), contentPadding = PaddingValues(16.dp)) {
        val n = lines.size
        Text("$n line${if (n == 1) "" else "s"} of text ${if (n == 1) "isn't an item" else "aren't items"} yet", style = MaterialTheme.typography.bodyMedium)
        if (open) {
            Spacer(Modifier.height(8.dp))
            lines.forEach { Text(it.trim(), style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(if (open) "Hide" else "Show", onClick = { open = !open })
            GhostButton("Turn into items", onClick = vm::moveTextIntoItems)
        }
    }
}

@Composable
private fun OrphanBanner(vm: ListEditorViewModel) {
    RisainaCard(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), contentPadding = PaddingValues(16.dp)) {
        Text("This list was deleted", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("It was deleted on the other phone before your changes were saved. They're still here.", style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted)
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Keep as a new list", onClick = vm::keepAsNew, modifier = Modifier.fillMaxWidth())
        GhostButton("Discard my changes", onClick = vm::discardChanges, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FailedBanner(reason: String, vm: ListEditorViewModel) {
    RisainaCard(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), contentPadding = PaddingValues(16.dp)) {
        Text("Not saved yet", style = MaterialTheme.typography.titleMedium, color = Risaina.colors.danger)
        Spacer(Modifier.height(4.dp))
        Text("$reason Your changes are kept on this phone.", style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("Try again", onClick = vm::retry)
            GhostButton("Undo my changes", onClick = vm::discardChanges)
        }
    }
}

@Composable
private fun AddRowButton(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium).clickable(onClickLabel = "Add an item", onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AddIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text("Add item", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LazyItemScope.RowItem(
    row: DocItem,
    /** The row's key in the list (its first id), for the field's state and the drag. */
    key: String,
    index: Int,
    count: Int,
    doc: ListDoc,
    people: People,
    vm: ListEditorViewModel,
    reorder: ReorderState,
    now: Long,
    caret: Caret?,
    onAddToCalendar: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var value by remember(key) { mutableStateOf(TextFieldValue(row.text, TextRange(row.text.length))) }
    // A text from elsewhere (a merge, a split, Undo) is taken only while it is the engine's newest:
    // the flow can lag a keystroke behind the field, and taking an older text would drop one.
    LaunchedEffect(row.text) {
        if (row.text != value.text && vm.latestRowText(row.id) == row.text) value = TextFieldValue(row.text, TextRange(value.selection.end.coerceAtMost(row.text.length)))
    }
    val focus = remember(key) { FocusRequester() }
    LaunchedEffect(caret) {
        val c = caret ?: return@LaunchedEffect
        val text = vm.latestRowText(row.id) ?: value.text
        value = TextFieldValue(text, TextRange(c.at.coerceIn(0, text.length)))
        runCatching { focus.requestFocus() }
        vm.caret.compareAndSet(c, null)
    }
    var menu by remember { mutableStateOf(false) }
    val dragging = reorder.isDragging(key)
    // A tick shows with checkboxes off too: the older app has boxes on every list, so a partner
    // on it can tick a row of a numbered list. It can be unticked from the row's menu.
    val struck = row.done
    val label = row.text.ifBlank { "Empty item" }
    val moves = buildList {
        if (row.done && !doc.checkboxes) add(CustomAccessibilityAction("Untick") { vm.toggle(row.id, false); true })
        if (index > 0) add(CustomAccessibilityAction("Move up") { vm.move(row.id, index - 1); true })
        if (index < count - 1) add(CustomAccessibilityAction("Move down") { vm.move(row.id, index + 1); true })
        if (index > 0) add(CustomAccessibilityAction("Move to top") { vm.move(row.id, 0); true })
        add(CustomAccessibilityAction("Delete item") { onDelete(); true })
    }

    Row(
        Modifier.fillMaxWidth()
            .then(if (dragging) Modifier else Modifier.animateItem())
            .draggedRow(reorder, key)
            .clip(MaterialTheme.shapes.small)
            .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(48.dp)
                .reorderHandle(reorder, key)
                .clickable(onClickLabel = "Options for this item") { menu = true }
                .semantics { contentDescription = "Options for $label. Touch and hold to drag." },
            contentAlignment = Alignment.Center,
        ) {
            Icon(DragHandleIcon, contentDescription = null, tint = Risaina.colors.textMuted, modifier = Modifier.size(20.dp))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (onAddToCalendar != null && row.text.isNotBlank()) DropdownMenuItem(
                    text = { Text("Add to calendar") },
                    leadingIcon = { Icon(CalendarIcon, contentDescription = null) },
                    onClick = { menu = false; onAddToCalendar() },
                )
                if (row.done && !doc.checkboxes) DropdownMenuItem(text = { Text("Untick") }, onClick = { menu = false; vm.toggle(row.id, false) })
                if (index > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; vm.move(row.id, index - 1) })
                if (index < count - 1) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; vm.move(row.id, index + 1) })
                DropdownMenuItem(text = { Text("Delete item", color = Risaina.colors.danger) }, onClick = { menu = false; onDelete() })
            }
        }
        if (doc.checkboxes) {
            Checkbox(checked = row.done, onCheckedChange = { vm.toggle(row.id, it) }, modifier = Modifier.semantics { contentDescription = label })
        }
        when (ListMarker.fromWire(doc.marker)) {
            ListMarker.NUMBER -> Marker("${index + 1}.")
            ListMarker.BULLET -> Marker("•")
            else -> if (!doc.checkboxes) Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f).padding(top = 11.dp, bottom = 11.dp, start = 4.dp)) {
            val style = MaterialTheme.typography.bodyLarge.copy(
                color = if (struck) Risaina.colors.textMuted else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
            )
            BasicTextField(
                value = value,
                onValueChange = { v ->
                    when {
                        v.text == value.text -> value = v
                        // A line break typed or pasted now, or past 300 characters (one already in the row is just text).
                        !RowEdits.isPlain(value.text, v.text) -> {
                            vm.typed(row.id, v.text)
                            val text = vm.latestRowText(row.id) ?: value.text
                            value = TextFieldValue(text, TextRange(v.selection.start.coerceAtMost(text.length)))
                        }
                        else -> { value = v; vm.typed(row.id, v.text) }
                    }
                },
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        val atStart = value.selection.collapsed && value.selection.start == 0
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && atStart && (index > 0 || value.text.isEmpty())) {
                            vm.backspaceAtStart(row.id); true
                        } else false
                    }
                    .semantics { customActions = moves },
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) Text("Item", style = style, color = Risaina.colors.textMuted)
                        inner()
                    }
                },
            )
            people.tickLine(row, now)?.let {
                Text(it, style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (people.addedByPartner(row)) PartnerBadge(people)
    }
}

@Composable
private fun Marker(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, color = Risaina.colors.textMuted,
        modifier = Modifier.padding(top = 11.dp, start = 2.dp, end = 4.dp).width(26.dp),
    )
}

/** A small initial on rows the partner added. */
@Composable
private fun PartnerBadge(people: People) {
    val name = people.nameOf(people.partnerId) ?: "Your partner"
    Box(
        Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
            .semantics { contentDescription = "Added by $name" },
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Above the keyboard: the list's format, or the note's list buttons. */
@Composable
private fun FormatBar(doc: ListDoc, vm: ListEditorViewModel, onNoteList: (NoteEdits.Kind) -> Unit, onNoteCalendar: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (doc.isNote) {
                BarChip("• List", selected = false) { onNoteList(NoteEdits.Kind.BULLET) }
                BarChip("1. List", selected = false) { onNoteList(NoteEdits.Kind.NUMBER) }
                BarChip("☑ Turn into checklist", selected = false) { vm.convert(ListKind.CHECKLIST) }
                if (onNoteCalendar != null) BarChip("📅 Add to calendar", selected = false, onClick = onNoteCalendar)
            } else {
                val marker = ListMarker.fromWire(doc.marker)
                BarChip("☑ Checkboxes", selected = doc.checkboxes) { vm.setCheckboxes(!doc.checkboxes) }
                BarChip("1. Numbers", selected = marker == ListMarker.NUMBER) { vm.setMarker(ListMarker.NUMBER) }
                BarChip("• Bullets", selected = marker == ListMarker.BULLET) { vm.setMarker(ListMarker.BULLET) }
            }
        }
    }
}

@Composable
private fun BarChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, style = MaterialTheme.typography.labelMedium) }, shape = PillShape)
}
