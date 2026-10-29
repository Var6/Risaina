package com.risaina.app.feature.lists

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.EmptyState
import com.risaina.app.core.designsystem.components.LoadingState
import com.risaina.app.core.designsystem.components.PillTone
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.components.SectionHeader
import com.risaina.app.core.designsystem.components.StatusPill
import com.risaina.app.core.designsystem.theme.PillShape
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.feature.common.AddIcon
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.PushPinIcon
import com.risaina.app.feature.common.SearchIcon

/**
 * Notes & lists (lists.md E): a two-column grid of cards, pinned first, each previewing its first
 * rows, how far along it is and the partner's latest touch. A card with unsent changes says so.
 */
@Composable
fun ListsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: ListsOverviewViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var showNew by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes & lists", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { showNew = true }, icon = { Icon(AddIcon, contentDescription = null) }, text = { Text("New") }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            !s.loaded -> LoadingState(Modifier.padding(padding))
            s.total == 0 -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                EmptyState(
                    title = "Write it down, together",
                    body = "To-do lists, numbered lists and notes you both can edit: plans for your next visit, gift ideas, things to tell each other.",
                )
                NewChoices(onPick = { onOpen(vm.create(it)) }, onTemplate = { onOpen(vm.fromTemplate(it)) }, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(96.dp))
            }
            else -> Grid(s, vm, onOpen, Modifier.padding(padding))
        }
    }
    if (showNew) NewListSheet(
        onDismiss = { showNew = false },
        onPick = { showNew = false; onOpen(vm.create(it)) },
        onTemplate = { showNew = false; onOpen(vm.fromTemplate(it)) },
    )
}

@Composable
private fun Grid(s: OverviewUi, vm: ListsOverviewViewModel, onOpen: (String) -> Unit, modifier: Modifier) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalItemSpacing = 10.dp,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "filters", span = StaggeredGridItemSpan.FullLine) { Filters(s, vm) }
        if (s.pinned.isNotEmpty()) {
            item(key = "pinned", span = StaggeredGridItemSpan.FullLine) { GroupLabel("Pinned") }
            items(s.pinned, key = { it.id }) { c -> ListCardView(c, onOpen = { onOpen(c.id) }, onRetry = { vm.retry(c.id) }) }
            if (s.others.isNotEmpty()) item(key = "others", span = StaggeredGridItemSpan.FullLine) { GroupLabel("Others") }
        }
        items(s.others, key = { it.id }) { c -> ListCardView(c, onOpen = { onOpen(c.id) }, onRetry = { vm.retry(c.id) }) }
        if (s.pinned.isEmpty() && s.others.isEmpty()) item(key = "none", span = StaggeredGridItemSpan.FullLine) {
            Text(
                if (s.query.isNotBlank()) "Nothing matches “${s.query.trim()}”." else "Nothing here yet.",
                style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted, modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun Filters(s: OverviewUi, vm: ListsOverviewViewModel) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        if (s.total > ListPreview.SEARCH_AFTER) {
            RisainaTextField(
                value = s.query, onValueChange = vm::setQuery, label = "Search your lists",
                trailing = { Icon(SearchIcon, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ListFilter.entries.forEach { f ->
                FilterChip(selected = s.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) }, shape = PillShape)
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(), style = RisainaTextStyles.overline, color = Risaina.colors.accentWarm,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
private fun ListCardView(c: ListCard, onOpen: () -> Unit, onRetry: () -> Unit) {
    RisainaCard(Modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(c.glyph, modifier = Modifier.semantics { contentDescription = c.kindLabel })
            Spacer(Modifier.width(8.dp))
            Text(c.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (c.pinned) Icon(PushPinIcon, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (c.lines.isEmpty()) {
            Text(if (c.isNote) "Empty note" else "Nothing on it yet", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
        c.lines.forEach { l ->
            Row(Modifier.padding(vertical = 1.dp)) {
                if (l.prefix.isNotEmpty()) {
                    Text(l.prefix, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    l.text, maxLines = if (c.isNote) 3 else 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = if (l.done) TextDecoration.LineThrough else null),
                    color = if (l.done) Risaina.colors.textMuted else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (c.more > 0) Text("+${c.more} more", style = MaterialTheme.typography.labelSmall, color = Risaina.colors.textMuted, modifier = Modifier.padding(top = 2.dp))
        if (c.showProgress) {
            Spacer(Modifier.height(8.dp))
            Text("${c.done} of ${c.total}", style = MaterialTheme.typography.labelSmall, color = Risaina.colors.textMuted)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { c.done.toFloat() / c.total }, modifier = Modifier.fillMaxWidth().clip(PillShape))
        }
        if (c.footer.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(c.footer, style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        c.chip?.let { chip ->
            Spacer(Modifier.height(8.dp))
            val tone = when (chip) {
                DraftChip.SAVING -> PillTone.Info
                DraftChip.ON_PHONE -> PillTone.Neutral
                DraftChip.NOT_SAVED -> PillTone.Danger
                DraftChip.DELETED -> PillTone.Warning
            }
            // "Not saved – tap to retry" retries right here; the others open the list.
            val retry = chip == DraftChip.NOT_SAVED || chip == DraftChip.ON_PHONE
            Box(
                if (retry) Modifier.heightIn(min = 48.dp).clip(PillShape).clickable(onClickLabel = "Try saving again", onClick = onRetry) else Modifier,
                contentAlignment = Alignment.CenterStart,
            ) { StatusPill(chip.label, tone) }
        }
    }
}

/** To-do list, Numbered list, Bulleted list, Note; then the ideas. One tap makes it and opens it. */
@Composable
private fun NewListSheet(onDismiss: () -> Unit, onPick: (NewKind) -> Unit, onTemplate: (ListTemplates.Template) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            SectionHeader("Start something")
            Spacer(Modifier.height(8.dp))
            NewChoices(onPick, onTemplate)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NewChoices(onPick: (NewKind) -> Unit, onTemplate: (ListTemplates.Template) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        NewKind.entries.forEach { k -> ChoiceRow(k.glyph, k.label, kindHint(k)) { onPick(k) } }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Risaina.colors.divider)
        Text("Or start from an idea", style = MaterialTheme.typography.labelMedium, color = Risaina.colors.textMuted, modifier = Modifier.padding(vertical = 4.dp))
        ListTemplates.all.forEach { t -> ChoiceRow(t.emoji, t.title, null) { onTemplate(t) } }
    }
}

private fun kindHint(k: NewKind) = when (k) {
    NewKind.TODO -> "Tick things off together"
    NewKind.NUMBERED -> "Steps, rankings, top tens"
    NewKind.BULLETED -> "Ideas, packing, plain lists"
    NewKind.NOTE -> "Free text, as long as you like"
}

@Composable
private fun ChoiceRow(glyph: String, title: String, hint: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics {})
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted) }
        }
    }
}
