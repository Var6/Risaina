package com.risaina.app.feature.lists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.EmptyState
import com.risaina.app.core.designsystem.components.LoadingState
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.SectionHeader
import com.risaina.app.core.designsystem.theme.FrauncesText
import com.risaina.app.core.designsystem.theme.PillShape
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.model.ListItem
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.SharedList
import com.risaina.app.feature.common.AddIcon
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.CloseIcon
import com.risaina.app.feature.common.DeleteIcon
import com.risaina.app.feature.common.Format
import com.risaina.app.feature.common.PushPinIcon

@Composable
fun ListsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: ListsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    var showNew by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes & lists", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { showNew = true }, icon = { Icon(AddIcon, null) }, text = { Text("New") }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            !s.loaded && s.lists.isEmpty() -> LoadingState(Modifier.padding(padding))
            s.lists.isEmpty() -> Column(Modifier.padding(padding)) {
                EmptyState(title = "Write it down, together", body = "Shared notes and checklists you both can edit: plans for your next visit, gift ideas, things you want to tell each other.")
                Templates(onPick = { t -> vm.create(t.title, ListKind.CHECKLIST, t.items, onOpen) })
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(s.lists, key = { it.id }) { l -> ListCard(l, edited = if (l.updatedBy != null && l.updatedBy != s.myId) s.partnerName else null) { onOpen(l.id) } }
            }
        }
    }
    if (showNew) AlertDialog(
        onDismissRequest = { showNew = false },
        title = { Text("Start something") },
        text = {
            Column {
                TextButton(onClick = { showNew = false; vm.create("", ListKind.NOTE, onCreated = onOpen) }) { Text("📝  A note") }
                TextButton(onClick = { showNew = false; vm.create("", ListKind.CHECKLIST, onCreated = onOpen) }) { Text("☑️  A checklist") }
                Spacer(Modifier.height(8.dp))
                Text("Or start from an idea", style = MaterialTheme.typography.labelMedium, color = Risaina.colors.textMuted)
                ListTemplates.all.forEach { t -> TextButton(onClick = { showNew = false; vm.create(t.title, ListKind.CHECKLIST, t.items, onOpen) }) { Text("${t.emoji}  ${t.title}") } }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Templates(onPick: (ListTemplates.Template) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        SectionHeader("Ideas to start with")
        Spacer(Modifier.height(8.dp))
        ListTemplates.all.forEach { t ->
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onPick(t) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.emoji, style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.width(12.dp)); Text(t.title, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun ListCard(l: SharedList, edited: String?, onClick: () -> Unit) {
    RisainaCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (l.kind == ListKind.NOTE) "📝" else "☑️"); Spacer(Modifier.width(10.dp))
            Text(l.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (l.pinned) Icon(PushPinIcon, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        if (l.kind == ListKind.CHECKLIST) {
            val total = l.items.size
            Text(if (total == 0) "No items yet" else "${l.doneCount} of $total done", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            if (total > 0) { Spacer(Modifier.height(6.dp)); LinearProgressIndicator(progress = { l.doneCount.toFloat() / total }, modifier = Modifier.fillMaxWidth().clip(PillShape)) }
        } else {
            Text(l.body.ifBlank { "Empty note" }, style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Text((edited?.let { "$it edited · " } ?: "") + Format.relative(l.updatedAt), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
    }
}

@Composable
fun ListDetailScreen(id: String, onBack: () -> Unit, vm: ListsViewModel = hiltViewModel()) {
    val list by vm.observe(id).collectAsStateWithLifecycle(initialValue = null)
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    val l = list
    var confirmDelete by remember { mutableStateOf(false) }
    // The editor's own copy. A new version refills it only when it is someone else's change (the
    // partner, or the server's copy after a conflict), never when it is this phone's own save coming
    // back: that used to replace what was typed since the save went out (see ListEditorSync).
    var seededVersion by remember { mutableIntStateOf(-1) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(false) }
    val rows = remember { mutableStateListOf<EditRow>() }
    var nextKey by remember { mutableLongStateOf(0L) }
    var newItem by remember { mutableStateOf("") }
    LaunchedEffect(l?.version) {
        if (l == null || l.version == seededVersion) return@LaunchedEffect
        val own = seededVersion != -1 && vm.takeOwnEcho(l)
        seededVersion = l.version
        if (own) {
            ListEditorSync.adoptIds(rows, l.items).forEachIndexed { i, r -> if (rows[i] != r) rows[i] = r }
        } else {
            title = l.title; body = l.body; pinned = l.pinned
            val fresh = ListEditorSync.reseed(rows, l.items) { nextKey++ }
            rows.clear(); rows.addAll(fresh)
        }
    }
    fun save(immediate: Boolean = false) { l?.let { vm.schedule(it, title, body, rows.map { r -> r.item }, pinned, immediate) } }
    fun addFrom(text: String) {
        val parts = vm.splitPasted(text)
        if (parts.isEmpty()) return
        rows.addAll(parts.map { EditRow(nextKey++, ListItem(null, it, false)) }); newItem = ""; save()
    }
    // Rows are found by their key when edited: the index or row captured at composition can be out
    // of date by then (an id adopted, or the partner's change refilled the list).
    fun update(key: Long, change: (ListItem) -> ListItem) {
        val i = rows.indexOfFirst { it.key == key }
        if (i >= 0) { rows[i] = rows[i].copy(item = change(rows[i].item)); save() }
    }
    fun remove(key: Long) {
        val i = rows.indexOfFirst { it.key == key }
        if (i >= 0) { rows.removeAt(i); save() }
    }
    // Leaving saves at once what has not been saved (the debounced save would be dropped with the
    // screen), by the arrow and by system back alike. Nothing changed: no save, so just looking at a
    // list no longer makes a new version that shows the partner "edited".
    fun leave() {
        val cur = l
        if (cur != null && ListEditorSync.sent(cur, title, body, rows.map { it.item }, pinned) != ListEditorSync.contentOf(cur)) save(immediate = true)
        onBack()
    }
    BackHandler(onBack = ::leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = ::leave) { Icon(BackIcon, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { pinned = !pinned; save() }) { Icon(PushPinIcon, contentDescription = if (pinned) "Unpin" else "Pin", tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(DeleteIcon, contentDescription = "Delete") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (l == null) { LoadingState(Modifier.padding(padding)); return@Scaffold }
        val clear = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        )
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            item {
                TextField(title, { title = it.take(120); save() }, textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = FrauncesText),
                    placeholder = { Text("Title") }, colors = clear, modifier = Modifier.fillMaxWidth())
            }
            if (l.kind == ListKind.NOTE) item {
                TextField(body, { body = it.take(10_000); save() }, textStyle = RisainaTextStyles.noteSerif, placeholder = { Text("Write or paste anything…") },
                    colors = clear, modifier = Modifier.fillMaxWidth(), minLines = 8)
            } else {
                items(rows, key = { it.key }) { r ->
                    val item = r.item
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.done, onCheckedChange = { done -> update(r.key) { it.copy(done = done) } })
                        TextField(item.text, { v -> update(r.key) { it.copy(text = v.take(300)) } }, colors = clear, modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (item.done) TextDecoration.LineThrough else null, color = if (item.done) Risaina.colors.textMuted else MaterialTheme.colorScheme.onSurface))
                        IconButton(onClick = { remove(r.key) }) { Icon(CloseIcon, contentDescription = "Remove item") }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                        Icon(AddIcon, contentDescription = null, tint = Risaina.colors.textMuted)
                        TextField(
                            newItem, { v -> if (v.contains('\n')) addFrom(v) else newItem = v.take(300) },
                            placeholder = { Text("Add item, or paste a list") }, colors = clear, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { addFrom(newItem) }),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    if (confirmDelete && l != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${l.title}”?") }, text = { Text("It disappears for both of you.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(l.id, onBack) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
}

