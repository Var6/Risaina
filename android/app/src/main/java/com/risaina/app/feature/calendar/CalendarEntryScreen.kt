package com.risaina.app.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.EmptyState
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.LoadingState
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.domain.CalendarText
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.core.model.CalendarSourceKind
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.DeleteIcon
import com.risaina.app.feature.timeline.DateDialog
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * One calendar entry, new or existing: Event or To-do, title and emoji, when (all day or a time,
 * with the partner's clock when it differs), repeat, reminders, who it reminds, pin, notes, "Let Bea
 * know", where it came from, and "Also add to my phone's calendar".
 */
@Composable
fun CalendarEntryScreen(
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenList: (String) -> Unit,
    vm: CalendarEntryViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.closed.collect { msg -> onBack(); snackbar.showSnackbar(msg) } }
    LifecycleResumeEffect(Unit) {
        vm.recheckExact()
        onPauseOrDispose { }
    }
    var pickDate by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var pickUntil by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val f = s.form
    val partnerName = s.partner?.displayName?.substringBefore(" ")
    val todo = f.kind == CalendarKind.TODO

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            s.existing != null -> if (todo) "To-do" else "Plan"
                            todo -> "New to-do"
                            else -> "New plan"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                actions = { if (s.existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(DeleteIcon, contentDescription = "Delete") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            s.loading -> { LoadingState(Modifier.padding(padding)); return@Scaffold }
            s.missing -> {
                EmptyState("This isn't in your calendar any more", "It may have been deleted on the other phone.", Modifier.padding(padding), action = { GhostButton("Go back", onClick = onBack) })
                return@Scaffold
            }
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
        ) {
            if (s.readOnly) {
                Text("This was made in a newer version of Risaina. Update to change it.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, modifier = Modifier.padding(vertical = 8.dp))
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                listOf(CalendarKind.EVENT to "Event", CalendarKind.TODO to "To-do").forEachIndexed { i, (kind, label) ->
                    SegmentedButton(selected = f.kind == kind, onClick = { vm.setKind(kind) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                RisainaTextField(
                    f.title, vm::setTitle, if (todo) "What needs doing?" else "What's the plan?", Modifier.weight(1f),
                    placeholder = if (todo) "Book the tickets" else "Dinner at Olive", error = s.titleError,
                )
                Spacer(Modifier.width(10.dp))
                RisainaTextField(f.emoji, vm::setEmoji, "Emoji", Modifier.width(88.dp), placeholder = "🎉")
            }
            s.suggestion?.let { hit ->
                Text("Found in the title", style = RisainaTextStyles.overline, color = Risaina.colors.accentWarm, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(hit.date to hit.time) + hit.alternatives).distinct().take(3).forEach { (d, t) ->
                        AssistChip(onClick = { vm.applySuggestion(d, t) }, label = { Text("📅 " + CalendarText.dayTime(d, t)) })
                    }
                }
            }
            s.sourceLabel?.let { label ->
                AssistChip(
                    onClick = {
                        val src = s.source
                        when (src?.kind) {
                            CalendarSourceKind.MESSAGE -> onOpenChat()
                            CalendarSourceKind.LIST, CalendarSourceKind.LIST_ITEM -> src.listId?.let(onOpenList)
                            else -> Unit
                        }
                    },
                    label = { Text(label, maxLines = 1) },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Heading("When")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton(f.date?.let { CalendarText.day(it, LocalDate.now().year) } ?: "Pick a date", onClick = { pickDate = true }, modifier = Modifier.weight(1f))
                if (!f.allDay) OutlineButton(f.time?.let { CalendarText.time(it) } ?: "Pick a time", onClick = { pickTime = true }, modifier = Modifier.weight(1f))
            }
            SwitchLine("All day", if (f.allDay) "Reminds at your all-day time" else null, f.allDay, vm::setAllDay)
            if (f.allDay) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GhostButton(f.endDate?.let { "Until ${CalendarText.day(it)}" } ?: "Add an end date", onClick = { pickEnd = true }, enabled = f.date != null)
                    if (f.endDate != null) GhostButton("Clear", onClick = { vm.setEndDate(null) })
                }
            } else {
                Text("How long", style = MaterialTheme.typography.labelMedium, color = Risaina.colors.textMuted, modifier = Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EntryRules.DURATIONS.forEach { m -> FilterChip(selected = f.durationMinutes == m, onClick = { vm.setDuration(m) }, label = { Text(EntryRules.durationLabel(m)) }) }
                }
                ZoneRow(s, partnerName ?: "Your partner", vm::setTimeZone)
            }

            if (!todo) {
                Heading("Repeat")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(CalendarRepeat.NONE to "Never", CalendarRepeat.WEEKLY to "Every week", CalendarRepeat.MONTHLY to "Every month", CalendarRepeat.YEARLY to "Every year").forEach { (r, label) ->
                        FilterChip(selected = f.repeat == r, onClick = { vm.setRepeat(r) }, label = { Text(label) })
                    }
                }
                if (f.repeat != CalendarRepeat.NONE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GhostButton(f.repeatUntil?.let { "Until ${CalendarText.day(it)}" } ?: "Repeat until…", onClick = { pickUntil = true })
                        if (f.repeatUntil != null) GhostButton("No end", onClick = { vm.setRepeatUntil(null) })
                    }
                }
            }

            Heading("Remind")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (f.allDay) EntryRules.ALL_DAY_REMINDERS else EntryRules.TIMED_REMINDERS).forEach { m ->
                    FilterChip(selected = m in f.reminders, onClick = { vm.toggleReminder(m) }, label = { Text(CalendarText.offset(m, f.allDay)) })
                }
            }
            if (!f.allDay && f.reminders.isNotEmpty() && !s.exactAllowed) ExactAlarmStatus(allowed = false, onAllow = vm::allowExact)
            if (s.partner != null) {
                Text(if (todo) "Who does it" else "Who gets reminded", style = MaterialTheme.typography.labelMedium, color = Risaina.colors.textMuted, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(Who.BOTH to "Both of us", Who.ME to "Me", Who.PARTNER to (partnerName ?: "Them"))
                    options.forEachIndexed { i, (who, label) ->
                        SegmentedButton(selected = f.who == who, onClick = { vm.setWho(who) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label, maxLines = 1) }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SwitchLine("Pin to Home", "A countdown on your Home screen", f.pinned, vm::setPinned)
            RisainaTextField(f.notes, vm::setNotes, "Notes", Modifier.padding(top = 4.dp), singleLine = false, minLines = 2, placeholder = "Address, what to bring, anything")
            if (s.partner != null) {
                SwitchLine(
                    "Let $partnerName know",
                    if (s.existing == null) "$partnerName's phone shows that you added this" else "$partnerName's phone shows that you changed this",
                    f.notifyPartner, vm::setNotify,
                )
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (s.existing == null) "Add to our calendar" else "Save", onClick = vm::save, modifier = Modifier.fillMaxWidth(), enabled = !s.readOnly && f.date != null, loading = s.saving)
            Spacer(Modifier.height(8.dp))
            OutlineButton("Also add to my phone's calendar", onClick = { vm.addToPhoneCalendar(context) }, modifier = Modifier.fillMaxWidth(), enabled = f.date != null)
            Spacer(Modifier.height(32.dp))
        }
    }

    if (pickDate) DateDialog(f.date ?: LocalDate.now(), { pickDate = false }) { it?.let(vm::setDate) }
    if (pickEnd) DateDialog(f.endDate ?: f.date?.plusDays(1), { pickEnd = false }) { vm.setEndDate(it) }
    if (pickUntil) DateDialog(f.repeatUntil ?: f.date?.plusMonths(3), { pickUntil = false }) { vm.setRepeatUntil(it) }
    if (pickTime) TimePickerDialog(f.time ?: LocalTime.of(19, 0), onDismiss = { pickTime = false }, onPick = vm::setTime)
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this?") },
        text = { Text("It goes from both your calendars, with its reminders.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
}

/**
 * Whose clock a timed entry follows, only when the partner shares a zone that tells another time:
 * mine, theirs, or each our own. With the live conversion, and a warning when either of you would be
 * asleep.
 */
@Composable
private fun ZoneRow(s: EntryUiState, partnerName: String, onPick: (String?) -> Unit) {
    val f = s.form
    val partnerZone = s.partnerZone ?: return
    val date = f.date ?: return
    val time = f.time ?: return
    if (!EntryRules.zonesDiffer(s.myZone, partnerZone, date)) return
    Text("Whose time", style = MaterialTheme.typography.labelMedium, color = Risaina.colors.textMuted, modifier = Modifier.padding(top = 10.dp))
    val options = listOf(s.myZone.id to "My time", partnerZone.id to "$partnerName's time", null to "Each our own clock")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (zone, label) -> FilterChip(selected = f.timeZone == zone, onClick = { onPick(zone) }, label = { Text(label) }) }
        // A zone that is neither (the entry was made somewhere else): shown so it is not lost by accident.
        if (f.timeZone != null && options.none { it.first == f.timeZone }) FilterChip(selected = true, onClick = {}, label = { Text(f.timeZone) })
    }
    val anchor = f.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    val line = EntryRules.zoneLine(date, time, anchor, s.myZone, partnerZone, partnerName)
    when {
        line != null -> Text(
            line.text + if (line.night) "  ⚠ late at night for one of you" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (line.night) Risaina.colors.warning else Risaina.colors.textMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        anchor == null -> Text("${CalendarText.time(time)} on each of your phones", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Heading(text: String) {
    Text(text.uppercase(), style = RisainaTextStyles.overline, color = Risaina.colors.accentWarm, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun SwitchLine(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A clock face in a dialog, 12-hour like the rest of the app. */
@Composable
fun TimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}
