package com.risaina.app.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PillTone
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.StatusPill
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.domain.CalendarText
import com.risaina.app.core.domain.OccasionKind
import com.risaina.app.core.domain.OccasionReminders

/**
 * Calendar reminders on this phone: the master switch, whether "Alarms & reminders" lets them arrive
 * on time, the dates that remind by themselves (anniversary, birthdays, next visit, yearly
 * milestones) and the time all-day reminders arrive. Entries keep their own reminders, set in the
 * entry.
 */
@Composable
fun ReminderSettingsSheet(onDismiss: () -> Unit, vm: ReminderSettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val partner by vm.partner.collectAsStateWithLifecycle()
    val exact by vm.exactAllowed.collectAsStateWithLifecycle()
    val offersMidnight by vm.offersPartnerMidnight.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.recheck()
        onPauseOrDispose { }
    }
    var pickTime by remember { mutableStateOf(false) }
    val name = partner?.displayName?.substringBefore(" ") ?: "Your partner"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Calendar reminders", style = MaterialTheme.typography.headlineMedium)
            Text("These are for this phone only. $name's phone has its own.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            Spacer(Modifier.height(8.dp))
            SwitchRow("Remind me", "Notifications for your plans and the dates that matter", s.enabled, vm::setEnabled)
            if (s.enabled) {
                ExactAlarmStatus(exact, onAllow = vm::allowExact)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("Dates that remind by themselves", style = MaterialTheme.typography.titleMedium)
                OccasionRow("Your anniversary", s.anniversary) { vm.setOccasion(OccasionKind.ANNIVERSARY, it) }
                OccasionRow("$name's birthday", s.partnerBirthday) { vm.setOccasion(OccasionKind.PARTNER_BIRTHDAY, it) }
                // On one clock their midnight is yours: nothing to offer.
                if (s.partnerBirthday.on && offersMidnight) {
                    SwitchRow("Also when it turns midnight for $name", "Where they are, so you can be first", s.partnerMidnight, vm::setPartnerMidnight)
                }
                OccasionRow("Your birthday", s.myBirthday) { vm.setOccasion(OccasionKind.MY_BIRTHDAY, it) }
                OccasionRow("Your next visit", s.nextVisit) { vm.setOccasion(OccasionKind.NEXT_VISIT, it) }
                OccasionRow("Yearly milestones", s.milestones) { vm.setOccasion(OccasionKind.MILESTONE, it) }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("All-day reminders arrive at", style = MaterialTheme.typography.titleSmall)
                        Text("Birthdays, the anniversary and plans without a time", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
                    }
                    OutlineButton(CalendarText.time(s.allDayTime), onClick = { pickTime = true })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickTime) TimePickerDialog(s.allDayTime, onDismiss = { pickTime = false }, onPick = vm::setAllDayTime)
}

/** On time, or "can arrive up to an hour late" with the way to allow "Alarms & reminders" (the SOS readiness card's fix). */
@Composable
fun ExactAlarmStatus(allowed: Boolean, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    if (allowed) {
        StatusPill("Reminders arrive on time", PillTone.Success, modifier.padding(vertical = 6.dp))
        return
    }
    RisainaCard(modifier.fillMaxWidth().padding(vertical = 6.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        StatusPill("Can be late", PillTone.Warning)
        Spacer(Modifier.height(6.dp))
        Text(
            "Reminders can arrive up to an hour late. Allow “Alarms & reminders” for on-time reminders.",
            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
        )
        Spacer(Modifier.height(8.dp))
        OutlineButton("Allow", onClick = onAllow)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val DAY_CHOICES = listOf(28 to "4 weeks before", 7 to "A week before", 3 to "3 days before", 1 to "A day before", 0 to "On the day")

@Composable
private fun OccasionRow(title: String, value: OccasionReminders, onChange: (OccasionReminders) -> Unit) {
    SwitchRow(title, null, value.on) { on -> onChange(value.copy(on = on, daysBefore = value.daysBefore.ifEmpty { listOf(0) })) }
    if (value.on) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            DAY_CHOICES.forEach { (days, label) ->
                val picked = days in value.daysBefore
                FilterChip(
                    selected = picked,
                    onClick = {
                        val next = if (picked) value.daysBefore - days else (value.daysBefore + days).take(4)
                        // None left means off: a switch that is on always reminds of something.
                        onChange(if (next.isEmpty()) value.copy(on = false) else value.copy(daysBefore = next.sortedDescending()))
                    },
                    label = { Text(label) },
                )
            }
        }
    }
}
