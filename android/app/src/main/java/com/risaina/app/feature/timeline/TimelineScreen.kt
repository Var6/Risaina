package com.risaina.app.feature.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.HeroPanel
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.components.SecondaryButton
import com.risaina.app.core.designsystem.components.SectionHeader
import com.risaina.app.core.designsystem.theme.Palette
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.model.Milestone
import com.risaina.app.core.model.MilestoneKind
import com.risaina.app.feature.common.AddIcon
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.EditIcon
import com.risaina.app.feature.common.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun TimelineScreen(onBack: () -> Unit, vm: TimelineViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    var pickStart by remember { mutableStateOf(false) }
    var editMeeting by remember { mutableStateOf(false) }
    var editMilestone by remember { mutableStateOf<Milestone?>(null) }
    var addMilestone by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Timeline", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                actions = { IconButton(onClick = { addMilestone = true }) { Icon(AddIcon, contentDescription = "Add a milestone") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            HeroPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp)) {
                    Text("TOGETHER", style = RisainaTextStyles.overline, color = Palette.SoftChampagne)
                    Spacer(Modifier.height(6.dp))
                    Text(s.together ?: "Set your start date", style = MaterialTheme.typography.displaySmall, color = Palette.WarmIvory)
                    Spacer(Modifier.height(6.dp))
                    Text(s.couple?.relationshipStartDate?.let { "Since ${Format.date(it)}" } ?: "The day it began, as you both count it.", style = MaterialTheme.typography.bodySmall, color = Palette.MutedOnDark)
                    Spacer(Modifier.height(14.dp))
                    GhostButton(if (s.couple?.relationshipStartDate == null) "Choose date" else "Change date", onClick = { pickStart = true })
                }
            }
            Spacer(Modifier.height(24.dp))
            SectionHeader("Next time together", trailing = { IconButton(onClick = { editMeeting = true }) { Icon(EditIcon, contentDescription = "Edit next meeting") } })
            Spacer(Modifier.height(10.dp))
            val meeting = s.upcoming.firstOrNull { it.kind == MilestoneKind.MEETING }
            RisainaCard(Modifier.fillMaxWidth(), onClick = { editMeeting = true }) {
                if (meeting != null) {
                    Text(meeting.countdown.headline, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Text("${meeting.title} · ${Format.date(meeting.date)}", style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted)
                } else {
                    Text("No date yet", style = MaterialTheme.typography.titleMedium)
                    Text("Add the next visit and it shows up on your home screen and the countdown widget.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                }
            }
            Spacer(Modifier.height(24.dp))
            SectionHeader("Coming up")
            Spacer(Modifier.height(6.dp))
            if (s.upcoming.isEmpty()) Text("Nothing scheduled. Add an anniversary or a milestone.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, modifier = Modifier.padding(vertical = 10.dp))
            s.upcoming.forEach { u ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.title, style = MaterialTheme.typography.titleSmall)
                        Text(Format.date(u.date), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted)
                    }
                    Text(u.countdown.headline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (u.milestone != null) IconButton(onClick = { editMilestone = u.milestone }) { Icon(EditIcon, contentDescription = "Edit") }
                }
            }
            if (s.milestones.any { it.date.isBefore(LocalDate.now()) && it.kind != MilestoneKind.ANNIVERSARY }) {
                Spacer(Modifier.height(16.dp))
                SectionHeader("Already happened")
                s.milestones.filter { it.date.isBefore(LocalDate.now()) && it.kind != MilestoneKind.ANNIVERSARY }.sortedByDescending { it.date }.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(m.title, style = MaterialTheme.typography.titleSmall); Text(Format.date(m.date), style = RisainaTextStyles.timestamp, color = Risaina.colors.textMuted) }
                        IconButton(onClick = { editMilestone = m }) { Icon(EditIcon, contentDescription = "Edit") }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (pickStart) DateDialog(s.couple?.relationshipStartDate, { pickStart = false }) { vm.setStartDate(it) }
    if (editMeeting) MeetingDialog(s.couple?.nextMeetingDate, s.couple?.nextMeetingLabel, onDismiss = { editMeeting = false }, onSave = { d, l -> vm.setNextMeeting(d, l) })
    if (addMilestone) MilestoneDialog(null, onDismiss = { addMilestone = false }, onSave = { t, d, k -> vm.addMilestone(t, d, k) }, onDelete = null)
    editMilestone?.let { m -> MilestoneDialog(m, onDismiss = { editMilestone = null }, onSave = { t, d, k -> vm.updateMilestone(m.id, t, d, k) }, onDelete = { vm.deleteMilestone(m.id) }) }
}

@Composable
fun DateDialog(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate?) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }); onDismiss() }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

@Composable
private fun MeetingDialog(initialDate: LocalDate?, initialLabel: String?, onDismiss: () -> Unit, onSave: (LocalDate?, String?) -> Unit) {
    var date by remember { mutableStateOf(initialDate) }
    var label by remember { mutableStateOf(initialLabel ?: "") }
    var pick by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Next time together") },
        text = {
            Column {
                OutlineButton(date?.let { Format.date(it) } ?: "Choose a date", onClick = { pick = true }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                RisainaTextField(label, { label = it.take(40) }, "Label", placeholder = "e.g. Goa, finally")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(date, label.ifBlank { null }); onDismiss() }) { Text("Save") } },
        dismissButton = {
            Row { if (initialDate != null) TextButton(onClick = { onSave(null, null); onDismiss() }) { Text("Clear") }; TextButton(onClick = onDismiss) { Text("Cancel") } }
        },
    )
    if (pick) DateDialog(date, { pick = false }) { date = it }
}

@Composable
private fun MilestoneDialog(existing: Milestone?, onDismiss: () -> Unit, onSave: (String, LocalDate, MilestoneKind) -> Unit, onDelete: (() -> Unit)?) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now()) }
    var kind by remember { mutableStateOf(existing?.kind ?: MilestoneKind.CUSTOM) }
    var pick by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New milestone" else "Edit milestone") },
        text = {
            Column {
                RisainaTextField(title, { title = it.take(60) }, "Title", placeholder = "First trip, the proposal, …")
                Spacer(Modifier.height(12.dp))
                OutlineButton(Format.date(date), onClick = { pick = true }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MilestoneKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(when (k) { MilestoneKind.ANNIVERSARY -> "Yearly"; MilestoneKind.MEETING -> "Visit"; MilestoneKind.CUSTOM -> "Once" }) })
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title.trim(), date, kind); onDismiss() }, enabled = title.trim().length >= 2) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (pick) DateDialog(date, { pick = false }) { it?.let { d -> date = d } }
}

@Suppress("unused") private val keepSecondary = ::SecondaryButton
