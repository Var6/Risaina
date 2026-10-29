package com.risaina.app.feature.delivery

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.designsystem.components.PillTone
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.SecondaryButton
import com.risaina.app.core.designsystem.components.StatusPill
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.notifications.DeliveryDiagnostics
import com.risaina.app.core.notifications.DeliveryHealthReporter
import com.risaina.app.core.notifications.DeliveryHealthRules
import com.risaina.app.core.notifications.DeliverySnapshot
import com.risaina.app.core.notifications.DeviceStateReader
import com.risaina.app.core.notifications.HealthCheck
import com.risaina.app.core.notifications.HealthStatus
import com.risaina.app.core.notifications.OemBackgroundSettings
import com.risaina.app.core.notifications.RegisterDeviceWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

data class DeliveryHealthUiState(
    val loaded: Boolean = false,
    val checks: List<HealthCheck> = emptyList(),
    val summary: String = "",
    val diagnostics: DeliverySnapshot = DeliverySnapshot(),
    val partnerName: String? = null,
    /** When the partner's phone last told the server it received a push (any of their phones). */
    val partnerLastAckAt: Long? = null,
    val partnerFlags: List<String> = emptyList(),
    val steps: String? = null,
    /** The phone maker's background switches are still unconfirmed: offer "I've done this". */
    val canConfirmOemSteps: Boolean = false,
    val sendingTest: Boolean = false,
    val testMessage: String? = null,
    /** How many phones the server pushes to for this account (GET /devices/health `mine`); null until known. */
    val myPhones: Int? = null,
    /** The other phones "Remove the others" would remove (see [otherPhones]). */
    val otherPhoneIds: List<String> = emptyList(),
    val removingPhones: Boolean = false,
    val phonesMessage: String? = null,
)

@HiltViewModel
class DeliveryHealthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateReader: DeviceStateReader,
    private val diagnostics: DeliveryDiagnostics,
    private val reporter: DeliveryHealthReporter,
    private val coupleDao: CoupleDao,
) : ViewModel() {
    private val _state = MutableStateFlow(DeliveryHealthUiState())
    val state: StateFlow<DeliveryHealthUiState> = _state

    /** Re-read on every resume: the person comes back from a settings screen after fixing something. */
    fun refresh() {
        viewModelScope.launch {
            val device = withContext(Dispatchers.Default) { stateReader.read() }
            val checks = DeliveryHealthRules.checks(device)
            _state.update {
                it.copy(
                    loaded = true, checks = checks, summary = DeliveryHealthRules.summary(checks),
                    diagnostics = diagnostics.snapshot(), steps = OemBackgroundSettings.steps(device.manufacturer),
                    canConfirmOemSteps = checks.any { c -> c.id == "oem" && c.status != HealthStatus.OK },
                    partnerName = coupleDao.partner()?.displayName,
                )
            }
            val health = reporter.health()
            if (health is AppResult.Success) {
                val partner = health.value.partner
                val mine = health.value.mine
                val myId = reporter.deviceId()
                _state.update {
                    it.copy(
                        partnerLastAckAt = partner.mapNotNull { d -> d.lastPushAckAt?.let(::parseMillis) }.maxOrNull(),
                        partnerFlags = partner.flatMap { d -> d.healthFlags }.distinct(),
                        myPhones = mine.size,
                        otherPhoneIds = otherPhones(mine.map { d -> d.deviceId }, myId),
                    )
                }
            }
        }
    }

    /**
     * "Remove the others": stops pushes to every other phone on this account. One the person still
     * uses registers again the next time Risaina opens on it, so a mistake heals itself.
     */
    fun removeOtherPhones() {
        val ids = _state.value.otherPhoneIds
        if (ids.isEmpty() || _state.value.removingPhones) return
        viewModelScope.launch {
            _state.update { it.copy(removingPhones = true, phonesMessage = null) }
            val failed = ids.count { reporter.removeDevice(it) is AppResult.Failure }
            _state.update {
                it.copy(
                    removingPhones = false,
                    phonesMessage = if (failed > 0) "Couldn't reach Risaina to remove every phone. Check your connection and try again." else null,
                )
            }
            refresh()
        }
    }

    /** "I've done this": the app cannot read the phone maker's switches, so it takes the person's word. */
    fun confirmOemSteps() {
        diagnostics.confirmOemSteps()
        refresh()
    }

    fun sendTestPush() {
        if (_state.value.sendingTest) return
        viewModelScope.launch {
            _state.update { it.copy(sendingTest = true, testMessage = null) }
            val message = when (val r = reporter.sendTestPush(TEST_DELAY_SECONDS)) {
                is AppResult.Success -> when {
                    r.value.scheduled -> "On its way. Lock your phone or close Risaina now: a test notification should arrive in about $TEST_DELAY_SECONDS seconds."
                    r.value.reason == "push_disabled" -> "Push isn't set up on the server yet, so nothing can be sent."
                    else -> {
                        RegisterDeviceWorker.enqueue(context)
                        "This phone isn't registered for pushes yet. Try again in a minute."
                    }
                }
                is AppResult.Failure -> "Couldn't reach Risaina. Check your connection and try again."
            }
            _state.update { it.copy(sendingTest = false, testMessage = message) }
            // Pick up the arrival in "Last push" without leaving the screen.
            delay((TEST_DELAY_SECONDS + 8) * 1000L)
            _state.update { it.copy(diagnostics = diagnostics.snapshot()) }
        }
    }

    private fun parseMillis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    private companion object { const val TEST_DELAY_SECONDS = 10 }
}

/**
 * "Delivery health": why alerts and widgets might arrive late on this phone, with a button that
 * fixes each problem, the last push this phone received, when the partner's phone last received
 * one, and a test push. Lives on the Widgets screen, where people notice late updates; that
 * screen's help sheet explains why background delivery matters.
 */
@Composable
fun DeliveryHealthCard(modifier: Modifier = Modifier, vm: DeliveryHealthViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    RisainaCard(modifier.fillMaxWidth()) {
        Text("Delivery health", style = MaterialTheme.typography.titleMedium)
        // The why ("only if this phone lets Risaina wake up in the background") is in the Widgets help.
        Text("Whether alerts and widgets reach this phone on time", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        if (s.loaded) {
            Spacer(Modifier.height(10.dp))
            val worst = s.checks.maxOfOrNull { it.status } ?: HealthStatus.OK
            StatusPill(s.summary, when (worst) { HealthStatus.OK -> PillTone.Success; HealthStatus.WARNING -> PillTone.Warning; HealthStatus.PROBLEM -> PillTone.Danger })
            Spacer(Modifier.height(6.dp))
            s.checks.forEach { c -> CheckRow(c) { action -> OemBackgroundSettings.open(context, action) } }
            s.steps?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
            if (s.canConfirmOemSteps) TextButton(onClick = vm::confirmOemSteps) { Text("I've done this") }
            Spacer(Modifier.height(12.dp))
            Text(lastPushLine(s.diagnostics), style = MaterialTheme.typography.bodySmall)
            if (s.diagnostics.downgraded) {
                Text(
                    "Android delivered the last urgent push late. Allowing background activity helps.",
                    style = MaterialTheme.typography.bodySmall, color = Risaina.colors.warning,
                )
            }
            partnerLine(s)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted) }
            s.myPhones?.let { PhonesRow(it, s, vm::removeOtherPhones) }
            Spacer(Modifier.height(6.dp))
            // For a helper looking at this screen: which build the phone runs and what the last push was.
            Text(
                technicalLine(com.risaina.app.BuildConfig.VERSION_NAME, s.diagnostics),
                style = MaterialTheme.typography.labelSmall, color = Risaina.colors.textMuted,
            )
            Spacer(Modifier.height(12.dp))
            SecondaryButton(if (s.sendingTest) "Sending…" else "Send me a test push", onClick = vm::sendTestPush, enabled = !s.sendingTest)
            s.testMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
        }
    }
}

/**
 * How many phones the server pushes to for this account. More than one (an old phone, or one set up
 * again under a new id) means every alert also goes there: say so, and offer to remove the others.
 */
@Composable
private fun PhonesRow(count: Int, s: DeliveryHealthUiState, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Text(
        phonesLine(count), style = MaterialTheme.typography.bodySmall,
        color = if (count > 1) Risaina.colors.warning else Risaina.colors.textMuted,
    )
    if (count > 1) {
        Text(
            "Your partner's pings and messages go to every one of them. If the others are old phones, remove them. A phone you still use registers again when you open Risaina on it.",
            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
        )
        if (s.otherPhoneIds.isNotEmpty()) {
            TextButton(onClick = { confirm = true }, enabled = !s.removingPhones) { Text(if (s.removingPhones) "Removing…" else "Remove the others") }
        }
    }
    s.phonesMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.warning) }
    if (confirm) {
        val others = s.otherPhoneIds.size
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (others == 1) "Remove the other phone?" else "Remove the other $others phones?") },
            text = { Text("They stop getting Risaina's alerts. This phone keeps them.") },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** "1 phone is registered on your account." / "2 phones are registered on your account." */
internal fun phonesLine(count: Int): String =
    if (count == 1) "1 phone is registered on your account." else "$count phones are registered on your account."

/**
 * The device ids "Remove the others" removes: every phone of this account but this one, and none at
 * all when this phone is not in the list (not registered yet, or under an id the server no longer
 * has), so the action can never leave the account with no phone. Pure (PhonesLineTest).
 */
internal fun otherPhones(mine: List<String?>, myDeviceId: String?): List<String> {
    if (myDeviceId.isNullOrBlank() || myDeviceId !in mine) return emptyList()
    return mine.filterNotNull().filter { it.isNotBlank() && it != myDeviceId }.distinct()
}

@Composable
private fun CheckRow(c: HealthCheck, onAction: (com.risaina.app.core.notifications.HealthAction) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val (mark, color) = when (c.status) {
            HealthStatus.OK -> "✓" to Risaina.colors.success
            HealthStatus.WARNING -> "!" to Risaina.colors.warning
            HealthStatus.PROBLEM -> "✕" to Risaina.colors.danger
        }
        Text(mark, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall)
            if (c.status != HealthStatus.OK) Text(c.detail, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (c.action != null && c.actionLabel != null) TextButton(onClick = { onAction(c.action) }) { Text(c.actionLabel) }
            if (c.secondaryAction != null && c.secondaryLabel != null) TextButton(onClick = { onAction(c.secondaryAction) }) { Text(c.secondaryLabel) }
        }
    }
}

private fun lastPushLine(d: DeliverySnapshot): String {
    val at = d.lastPushAt ?: return "This phone hasn't received a push since this version was installed."
    val took = d.lastPushLatencyMillis?.takeIf { it >= 1_000 }?.let { ", ${com.risaina.app.core.notifications.RisainaNotifier.formatLatency(it)} after it was sent" } ?: ""
    val sync = when {
        d.lastSyncResult == null -> ""
        d.lastSyncResult == "ok" -> " Synced fine."
        d.lastSyncResult == "timeout" -> " The sync ran out of time; it finished in the background."
        else -> " The sync failed; it retries in the background."
    }
    return "Last push received ${relative(at)}$took.$sync"
}

/**
 * "Build 0.2.0+1a2b3c4 · last push message.created, arrived high (sent high) · widgets drawn 2 ·
 * background sync 5 min ago". The build id answers "is this phone on the new version?"; arrived vs
 * sent shows an FCM downgrade; the background sync time shows whether Android lets the worker run.
 */
internal fun technicalLine(build: String, d: DeliverySnapshot, now: Long = System.currentTimeMillis()): String = buildList {
    add("Build $build")
    d.lastPushType?.let { type ->
        val arrived = d.lastPushPriority?.let { p -> ", arrived $p" + (d.lastPushOriginalPriority?.let { " (sent $it)" } ?: "") } ?: ""
        add("last push $type$arrived")
    }
    if (d.lastWidgetDrawAt != null) {
        add("widgets drawn ${d.lastWidgetDrawn}" + if (d.lastWidgetFallbacks > 0) ", queued ${d.lastWidgetFallbacks}" else "")
    }
    d.lastWorkerRunAt?.let { add("background sync ${relative(it, now)}") }
}.joinToString(" · ")

private fun partnerLine(s: DeliveryHealthUiState): String? {
    val name = s.partnerName ?: return null
    val first = name.substringBefore(" ")
    val at = s.partnerLastAckAt ?: return "$first's phone hasn't reported a push yet (it needs this version of Risaina)."
    val issues = s.partnerFlags.mapNotNull(::flagWords)
    val why = if (issues.isEmpty()) "" else " On their phone: ${issues.joinToString(", ")}."
    return "$first's phone last received a push ${relative(at)}.$why"
}

/** Partner flags in plain words (codes from DeliveryHealthRules.flags). */
private fun flagWords(flag: String): String? = when (flag) {
    "notifications_off" -> "notifications are off"
    "channel_off" -> "some alerts are turned off"
    "battery_optimized" -> "battery optimisation is on"
    "background_restricted" -> "background use is restricted"
    "standby_rare", "standby_restricted" -> "Android rarely wakes Risaina"
    "data_saver" -> "Data Saver blocks background data"
    "no_play_services" -> "no Google Play services"
    "force_stopped" -> "Risaina was force-stopped"
    else -> null
}

private fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
    val mins = (now - millis) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 60 * 24 -> "${mins / 60} h ago"
        else -> "${mins / (60 * 24)} d ago"
    }
}
