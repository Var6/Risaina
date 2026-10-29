package com.risaina.app.feature.wake

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.alarm.MemberStatus
import com.risaina.app.core.alarm.WakeAlarmRules
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PillTone
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.components.SectionHeader
import com.risaina.app.core.designsystem.components.StatusPill
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.safety.CheckStatus
import com.risaina.app.core.safety.ReadinessCheck
import com.risaina.app.feature.common.BackIcon
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Most alarms a couple can have (the server's limit). */
private const val MAX_ALARMS = 10

/**
 * Wake together (risaina://app/wake): the shared alarms with a switch each and who they ring for,
 * this phone's readiness with the fixes, "Test on this phone", and the editor sheet.
 */
@Composable
fun WakeScreen(onBack: () -> Unit, vm: WakeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    // While the editor is open its messages show in the sheet: a snackbar would sit behind it.
    var sheetNote by remember { mutableStateOf<String?>(null) }
    val editing by rememberUpdatedState(editor != null)
    LaunchedEffect(vm) { vm.messages.collect { m -> if (editing) sheetNote = m else snackbar.showSnackbar(m) } }
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }
    var deleting by remember { mutableStateOf<WakeAlarm?>(null) }
    // Right after a change the partner's phone has not set it yet; look again now and then.
    val waiting = s.rows.any { it.alarm.enabled && it.partner == MemberStatus.NOT_SET_YET }
    LaunchedEffect(waiting) {
        while (waiting) {
            delay(45_000)
            vm.refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wake together", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(
                "One alarm, ringing at the same moment on both phones. Each of you can turn it off on your own phone, and \"I'm up\" tells the other.",
                style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted,
            )
            Spacer(Modifier.height(16.dp))
            if (s.available == false) {
                RisainaCard(Modifier.fillMaxWidth()) {
                    Text("Wake together isn't ready on the server yet", style = MaterialTheme.typography.titleMedium)
                    Text("Try again a little later.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                }
                Spacer(Modifier.height(12.dp))
            }
            if (s.rows.isEmpty()) {
                RisainaCard(Modifier.fillMaxWidth()) {
                    Text("☀", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Wake up together", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Set a time and both phones ring at once, even far apart. ${s.partnerName} sees it on their phone and can turn it off there.",
                        style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            s.rows.forEach { row ->
                AlarmCard(
                    row, s.partnerName,
                    onOpen = { vm.openEdit(row.alarm) },
                    onEnabled = { vm.setEnabled(row.alarm, it) },
                    onSkip = { at, skip -> vm.setSkip(row.alarm, at, skip) },
                    enabled = !s.busy,
                )
                Spacer(Modifier.height(12.dp))
            }
            if (s.rows.size < MAX_ALARMS) {
                PrimaryButton("Set an alarm", onClick = vm::openNew, modifier = Modifier.fillMaxWidth(), enabled = s.available != false)
            } else {
                Text("That's 10 alarms, the most you can have. Delete one to add another.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            SectionHeader("On this phone")
            Spacer(Modifier.height(8.dp))
            RisainaCard(Modifier.fillMaxWidth()) {
                val todo = s.checks.count { it.status == CheckStatus.ACTION }
                Text(
                    if (todo == 0) "Ready: Wake together can ring here" else if (todo == 1) "1 thing to fix" else "$todo things to fix",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (todo == 0) Risaina.colors.success else Risaina.colors.warning,
                )
                Spacer(Modifier.height(8.dp))
                s.checks.forEach { check -> CheckRow(check, onFix = { check.fix?.let(vm::openFix) }) }
            }
            Spacer(Modifier.height(12.dp))
            OutlineButton("Test on this phone", onClick = vm::test, modifier = Modifier.fillMaxWidth())
            Text(
                "Rings in 10 seconds, the way a real alarm does. Nothing is sent to ${s.partnerName}.",
                style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    editor?.let { draft ->
        WakeEditorSheet(
            draft = draft,
            ui = s,
            note = sheetNote,
            onChange = { change -> sheetNote = null; vm.edit(change) },
            onSave = vm::save,
            onDelete = { s.rows.firstOrNull { it.alarm.id == draft.id }?.let { deleting = it.alarm } },
            onDismiss = { sheetNote = null; vm.closeEditor() },
        )
    }
    deleting?.let { alarm ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this alarm?") },
            text = { Text("It stops on both phones.") },
            confirmButton = { TextButton(onClick = { vm.delete(alarm); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AlarmCard(
    row: WakeRowUi, partnerName: String, onOpen: () -> Unit, onEnabled: (Boolean) -> Unit, onSkip: (Instant, Boolean) -> Unit, enabled: Boolean,
) {
    val a = row.alarm
    RisainaCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.time, style = MaterialTheme.typography.displaySmall, color = if (a.enabled) MaterialTheme.colorScheme.onSurface else Risaina.colors.textMuted)
                Spacer(Modifier.height(4.dp))
                Text("${row.title} · ${row.schedule}", style = MaterialTheme.typography.titleSmall)
                row.next?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted) }
                row.localLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted) }
                if (!row.supported) Text("Update Risaina to use this alarm", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.warning)
            }
            Switch(
                checked = a.enabled, onCheckedChange = onEnabled, enabled = enabled && row.supported,
                modifier = Modifier.semantics { contentDescription = "${row.title}, on for both of you" },
            )
        }
        if (a.enabled && row.supported) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill("You · ${WakeAlarmRules.statusText(row.me, mine = true)}", tone(row.me))
                StatusPill("$partnerName · ${WakeAlarmRules.statusText(row.partner, mine = false)}", tone(row.partner))
            }
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            row.skipped?.let { at ->
                GhostButton("Undo skip (${dayWord(at, zone, now)})", onClick = { onSkip(at, false) }, enabled = enabled)
            }
            if (row.skipped == null) row.skippable?.let { at ->
                GhostButton("Skip ${dayWord(at, zone, now)} on my phone", onClick = { onSkip(at, true) }, enabled = enabled)
            }
        }
    }
}

/** "today", "tomorrow" or "Fri 14 Nov", for the middle of a sentence. */
private fun dayWord(at: Instant, zone: ZoneId, now: Instant): String =
    WakeAlarmRules.dayText(at, zone, now).let { if (it == "Today" || it == "Tomorrow") it.lowercase() else it }

private fun tone(status: MemberStatus): PillTone = when (status) {
    MemberStatus.READY -> PillTone.Success
    MemberStatus.OFF, MemberStatus.DONE -> PillTone.Neutral
    MemberStatus.NOT_SET_YET -> PillTone.Info
    else -> PillTone.Warning
}

@Composable
private fun CheckRow(check: ReadinessCheck, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        val (mark, color) = when (check.status) {
            CheckStatus.OK -> "✓" to Risaina.colors.success
            CheckStatus.ACTION -> "!" to Risaina.colors.warning
            CheckStatus.INFO -> "i" to Risaina.colors.textMuted
        }
        Text(mark, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(check.title, style = MaterialTheme.typography.bodyMedium)
            check.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted) }
        }
        if (check.fix != null && check.status != CheckStatus.OK) GhostButton(check.fixLabel ?: "Fix", onClick = onFix)
    }
}

/**
 * Set or change an alarm: the time, the days (none: once, on a date), a name, the snooze length,
 * and who it rings for. When the two of you are in different zones, both clocks are shown.
 */
@Composable
private fun WakeEditorSheet(
    draft: WakeDraft, ui: WakeUi, note: String?, onChange: ((WakeDraft) -> WakeDraft) -> Unit, onSave: () -> Unit,
    onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    var pickTime by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    val now = Instant.now()
    val anchor = runCatching { ZoneId.of(draft.zone) }.getOrNull() ?: ZoneId.systemDefault()
    val mine = ZoneId.systemDefault()
    val ring = WakeDrafts.nextRing(draft, now)
    val existing = ui.rows.firstOrNull { it.alarm.id == draft.id }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text(if (draft.id == null) "New alarm" else "Change alarm", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { pickTime = true }, modifier = Modifier.semantics { contentDescription = "Time, ${WakeAlarmRules.timeText(draft.time)}. Change" }) {
                Text(WakeAlarmRules.timeText(draft.time), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            if (ring != null && anchor.rules.getOffset(ring) != mine.rules.getOffset(ring)) {
                StatusPill("Set in ${WakeAlarmRules.zoneName(anchor)} time", PillTone.Info)
                Spacer(Modifier.height(6.dp))
            }
            ring?.let { WakeAlarmRules.zoneLines(it, anchor, mine, ui.partnerZone, ui.partnerName) }?.let { lines ->
                Text(lines.line, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                lines.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.warning) }
            }
            Spacer(Modifier.height(16.dp))
            Text("Repeat", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            DayToggles(draft.days) { day, on -> onChange { d -> d.copy(days = if (on) d.days + day else d.days - day) } }
            Spacer(Modifier.height(6.dp))
            if (draft.days.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Once, on", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlineButton(draft.date?.let { com.risaina.app.feature.common.Format.date(it) } ?: "Pick a day", onClick = { pickDate = true })
                }
            } else {
                Text(WakeAlarmRules.daysText(draft.days.toList()), style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
            Spacer(Modifier.height(16.dp))
            RisainaTextField(
                draft.label, { v -> onChange { it.copy(label = v.take(WakeDrafts.MAX_LABEL)) } }, "Name",
                placeholder = WakeAlarmRules.DEFAULT_LABEL,
            )
            Spacer(Modifier.height(16.dp))
            Text("Snooze", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WakeDrafts.SNOOZE_CHOICES.forEach { m ->
                    FilterChip(selected = draft.snoozeMinutes == m, onClick = { onChange { it.copy(snoozeMinutes = m) } }, label = { Text("$m min") })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Rings on", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("You", style = MaterialTheme.typography.bodyMedium)
                    Text("Only changes your phone", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                }
                Switch(
                    checked = draft.ringOnMyPhone,
                    onCheckedChange = { v -> onChange { it.copy(ringOnMyPhone = v) } },
                    modifier = Modifier.semantics { contentDescription = "Rings on your phone" },
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ui.partnerName, style = MaterialTheme.typography.bodyMedium)
                    Text("Only they can change this", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                }
                if (existing != null) StatusPill(WakeAlarmRules.statusText(existing.partner, mine = false), tone(existing.partner))
                else StatusPill("On", PillTone.Info)
            }
            if (!ui.shareLocalTime) {
                Text("${ui.partnerName} will see this alarm's time zone.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
            note?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(if (draft.id == null) "Set alarm" else "Save", onClick = onSave, modifier = Modifier.fillMaxWidth(), loading = ui.busy)
            if (draft.id != null) {
                GhostButton("Delete alarm", onClick = onDelete, modifier = Modifier.fillMaxWidth(), enabled = !ui.busy)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (pickTime) TimeDialog(draft.time, onDismiss = { pickTime = false }) { t -> onChange { it.copy(time = t) } }
    if (pickDate) com.risaina.app.feature.timeline.DateDialog(draft.date, { pickDate = false }) { date -> if (date != null) onChange { it.copy(date = date) } }
}

/** M T W T F S S: each a 48 dp toggle, named in full for TalkBack. */
@Composable
private fun DayToggles(days: Set<Int>, onToggle: (Int, Boolean) -> Unit) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (1..7).forEach { d ->
            val on = d in days
            val name = DayOfWeek.of(d).getDisplayName(TextStyle.FULL, locale)
            Box(
                Modifier.weight(1f).height(48.dp)
                    .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle(d, it) })
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                        .then(if (on) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        DayOfWeek.of(d).getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}
