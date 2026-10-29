package com.risaina.app.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.EmptyState
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.domain.CalendarText
import com.risaina.app.core.domain.OccasionKind
import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.feature.common.AddIcon
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.NotificationsIcon
import com.risaina.app.feature.common.TimelineIcon
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The pager's months: far enough either way that nobody reaches the end. */
private const val MONTH_PAGES = 2401
private const val THIS_MONTH_PAGE = MONTH_PAGES / 2

/**
 * The couple's calendar: Upcoming (default) and a Month grid. Entries are shared; the anniversary,
 * birthdays and the next visit are shown from where they already live (tapping one goes there). The
 * timeline stays as "Our story".
 */
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onAdd: (LocalDate?) -> Unit,
    onOurStory: () -> Unit,
    onOpenProfile: () -> Unit,
    openReminders: Boolean = false,
    vm: CalendarViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    var showReminders by rememberSaveable { mutableStateOf(openReminders) }
    var selected by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val day = selected ?: s.today
    val pager = rememberPagerState(initialPage = THIS_MONTH_PAGE) { MONTH_PAGES }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ctx = RowContext(s.today, s.zone, s.myId, s.partner, s.partnerZone)

    val openItem: (AgendaItem) -> Unit = { item ->
        when (item) {
            is AgendaItem.Entry -> onOpenEntry(item.entry.id)
            is AgendaItem.Occasion -> when (item.occasionDay.occasion.kind) {
                OccasionKind.MY_BIRTHDAY -> onOpenProfile()
                OccasionKind.PARTNER_BIRTHDAY -> vm.say("${s.partner?.displayName?.substringBefore(" ") ?: "Your partner"}'s birthday comes from their profile")
                else -> onOurStory()
            }
        }
    }
    val toggle: (CalendarEntry, Boolean) -> Unit = { e, done -> vm.setDone(e, done) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                actions = {
                    GhostButton("Today", onClick = {
                        selected = null
                        scope.launch { if (tab == 1) pager.animateScrollToPage(THIS_MONTH_PAGE) else list.animateScrollToItem(0) }
                    })
                    IconButton(onClick = { showReminders = true }) { Icon(NotificationsIcon, contentDescription = "Reminder settings") }
                    IconButton(onClick = onOurStory) { Icon(TimelineIcon, contentDescription = "Our story") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (s.available != false) {
                FloatingActionButton(onClick = { onAdd(if (tab == 1) day else null) }, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(AddIcon, contentDescription = "Add to the calendar")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (s.available == false) {
            EmptyState(
                title = "The calendar is on its way",
                body = "It needs the newest Risaina server, which isn't here yet. Your anniversary and birthdays are still in Our story.",
                modifier = Modifier.padding(padding).fillMaxSize(),
                action = { GhostButton("Open Our story", onClick = onOurStory) },
            )
            return@Scaffold
        }
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                    listOf("Upcoming", "Month").forEachIndexed { i, label ->
                        SegmentedButton(selected = tab == i, onClick = { vm.setTab(i) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
            }
            if (tab == 0) {
                if (s.sections.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Nothing planned yet",
                            body = "Add a date night, a trip or a to-do. Your anniversary and birthdays show up here on their own.",
                            action = { PrimaryButton("Add something", onClick = { onAdd(null) }) },
                        )
                    }
                }
                s.sections.forEach { section ->
                    item(key = "h-${section.title}") { SectionLabel(section.title, top = 14.dp) }
                    items(section.items, key = { "${section.title}-${it.key}" }) { item ->
                        AgendaRow(item, ctx, onOpen = { openItem(item) }, onToggle = { done -> (item as? AgendaItem.Entry)?.let { toggle(it.entry, done) } })
                    }
                }
            } else {
                item {
                    MonthHeader(
                        month = YearMonth.from(s.today).plusMonths((pager.currentPage - THIS_MONTH_PAGE).toLong()),
                        onPrevious = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        onNext = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    )
                    WeekdayLabels(s.firstDayOfWeek)
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), beyondViewportPageCount = 1) { page ->
                        val month = YearMonth.from(s.today).plusMonths((page - THIS_MONTH_PAGE).toLong())
                        MonthGrid(month, s, day, onPick = { selected = it })
                    }
                    SectionLabel(CalendarText.day(day, s.today.year), top = 16.dp)
                }
                val dayItems = CalendarAgenda.dayItems(s.entries, s.occasions, day, s.zone)
                if (dayItems.isEmpty()) {
                    item {
                        Text(
                            "Nothing on this day.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                        )
                    }
                }
                items(dayItems, key = { "d-${it.key}" }) { item ->
                    AgendaRow(item, ctx, onOpen = { openItem(item) }, onToggle = { done -> (item as? AgendaItem.Entry)?.let { toggle(it.entry, done) } })
                }
            }
        }
    }

    if (showReminders) ReminderSettingsSheet(onDismiss = { showReminders = false })
}

@Composable
private fun SectionLabel(text: String, top: androidx.compose.ui.unit.Dp) {
    Text(text.uppercase(), style = RisainaTextStyles.overline, color = Risaina.colors.accentWarm, modifier = Modifier.padding(start = 4.dp, top = top, bottom = 4.dp))
}

@Composable
private fun MonthHeader(month: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month") }
        Text(
            month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)), style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month") }
    }
}

@Composable
private fun WeekdayLabels(first: java.time.DayOfWeek) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        (0L until 7L).forEach { i ->
            Text(
                first.plus(i).getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelMedium,
                color = Risaina.colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Six weeks; today ringed, the chosen day filled, up to three dots: an occasion, an event, a to-do. */
@Composable
private fun MonthGrid(month: YearMonth, s: CalendarUiState, selected: LocalDate, onPick: (LocalDate) -> Unit) {
    val days = remember(month, s.firstDayOfWeek) { CalendarAgenda.gridDays(month, s.firstDayOfWeek) }
    val marks = remember(month, s.entries, s.occasions, s.zone) { CalendarAgenda.marks(s.entries, s.occasions, days.first(), days.last(), s.zone) }
    val dotColors = mapOf(
        DayMark.OCCASION to Risaina.colors.accentWarm,
        DayMark.EVENT to MaterialTheme.colorScheme.primary,
        DayMark.TODO to Risaina.colors.success,
    )
    Column(Modifier.fillMaxWidth()) {
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { d ->
                    val inMonth = YearMonth.from(d) == month
                    val isSelected = d == selected
                    val isToday = d == s.today
                    val dayMarks = marks[d].orEmpty()
                    val label = CalendarText.day(d, s.today.year) + when (dayMarks.size) { 0 -> ""; else -> ", has plans" } + if (isToday) ", today" else ""
                    Column(
                        Modifier.weight(1f).height(52.dp).clip(MaterialTheme.shapes.small).clickable { onPick(d) }.semantics { contentDescription = label },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape)
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .then(if (isToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                d.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    inMonth -> MaterialTheme.colorScheme.onSurface
                                    else -> Risaina.colors.textMuted.copy(alpha = 0.5f)
                                },
                            )
                        }
                        Row(Modifier.height(8.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            DayMark.entries.filter { it in dayMarks }.forEach { m ->
                                Box(Modifier.size(5.dp).clip(CircleShape).background(dotColors.getValue(m).copy(alpha = if (inMonth) 1f else 0.5f)))
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
