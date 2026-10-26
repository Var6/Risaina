package com.risaina.app.feature.safety

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.MainActivity
import com.risaina.app.R
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.SosRepository
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.domain.LiveLocationRules
import com.risaina.app.core.location.FailReason
import com.risaina.app.core.location.LiveLocationController
import com.risaina.app.core.location.LiveLocationRepository
import com.risaina.app.core.location.LiveLocationState
import com.risaina.app.core.location.LocationClient
import com.risaina.app.core.location.ShareState
import com.risaina.app.core.network.LiveLocationDto
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SocketState
import com.risaina.app.core.network.SosDto
import com.risaina.app.core.network.SosTestRequest
import com.risaina.app.core.network.apiCall
import com.risaina.app.core.safety.ReadinessCheck
import com.risaina.app.core.safety.ReadinessFix
import com.risaina.app.core.safety.SafetyPrefs
import com.risaina.app.core.safety.SafetySettings
import com.risaina.app.core.safety.SosReadiness
import com.risaina.app.core.safety.SosReadinessProbe
import com.risaina.app.core.safety.SosSender
import com.risaina.app.core.sos.SosAlarmController
import com.risaina.app.core.sos.SosSenderStatus
import com.risaina.app.core.sync.EventSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** What this phone can do about location right now (re-read whenever a screen resumes). */
data class LocationAccess(val playServices: Boolean = true, val permitted: Boolean = false, val precise: Boolean = false)

data class SafetyUi(
    val myId: String? = null,
    val partnerName: String = "your partner",
    val share: ShareState = ShareState.Off,
    val live: LiveLocationState = LiveLocationState(),
    val prefs: SafetyPrefs = SafetyPrefs(),
    val sos: List<SosDto> = emptyList(),
    val attempt: SosSender.Attempt = SosSender.Attempt.Idle,
    val access: LocationAccess = LocationAccess(),
    val readiness: List<ReadinessCheck> = emptyList(),
) {
    /** My latest SOS while "I'm safe now" is still on offer (2 hours). */
    val myActive: SosDto?
        get() = sos.firstOrNull { it.senderId == myId }?.takeIf {
            runCatching { Instant.parse(it.createdAt).isAfter(Instant.now().minus(SosSenderStatus.SAFE_NOW_WINDOW)) }.getOrDefault(false)
        }

    /** The partner's live session, hidden as soon as it expires even if no stop event came. */
    val partnerLive: LiveLocationDto?
        get() = live.partner?.takeIf { p ->
            LiveLocationRules.isActive(p.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }, Instant.now())
        }

    val sharingOn: Boolean get() = share is ShareState.Sharing || share is ShareState.Starting || share is ShareState.Paused
}

/**
 * Safety screens: live location (my switch and my partner's position), the panic button, the SOS
 * sender's status and the "Can an SOS reach you?" checklist. One view model so every place shows the
 * same state; the emergency work itself lives in app-scoped singletons, never in this view model.
 */
@HiltViewModel
class SafetyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: LiveLocationController,
    private val liveRepo: LiveLocationRepository,
    private val locationClient: LocationClient,
    private val settings: SafetySettings,
    private val sosRepository: SosRepository,
    private val sender: SosSender,
    private val alarm: SosAlarmController,
    private val probe: SosReadinessProbe,
    private val api: RisainaApi,
    private val syncManager: EventSyncManager,
    sessionStore: SessionStore,
    couple: CoupleRepository,
) : ViewModel() {
    private val access = MutableStateFlow(readAccess())
    private val readiness = MutableStateFlow<List<ReadinessCheck>>(emptyList())
    private val effects = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = effects.receiveAsFlow()

    private val who = combine(sessionStore.session.map { it?.userId }, couple.partner) { me, p -> me to (p?.displayName ?: "your partner") }
    private val location = combine(controller.state, liveRepo.state, access) { share, live, a -> Triple(share, live, a) }
    private val sosSide = combine(sosRepository.items, sender.attempt, settings.state, readiness) { items, attempt, prefs, checks -> SosSide(items, attempt, prefs, checks) }

    private data class SosSide(val items: List<SosDto>, val attempt: SosSender.Attempt, val prefs: SafetyPrefs, val checks: List<ReadinessCheck>)

    val state: StateFlow<SafetyUi> = combine(who, location, sosSide) { (me, name), (share, live, a), s ->
        SafetyUi(me, name, share, live, s.prefs, s.items, s.attempt, a, s.checks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SafetyUi())

    init {
        viewModelScope.launch { sosRepository.refresh() }
    }

    private fun readAccess() = LocationAccess(
        playServices = runCatching { locationClient.playServicesAvailable() }.getOrDefault(false),
        permitted = locationClient.hasPermission(),
        precise = locationClient.hasPrecise(),
    )

    /** Screens call this on every resume: permissions and phone settings change outside the app. */
    fun refreshDevice() {
        access.value = readAccess()
        readiness.value = SosReadiness.evaluate(probe.read())
    }

    // ---- my live location -----------------------------------------------------------------------

    /** Start manual sharing for the chosen length. Called after the permission flow, from a visible screen. */
    fun startSharing() {
        access.value = readAccess()
        viewModelScope.launch {
            val r = controller.start(panic = false, minutes = settings.current.liveMinutes)
            if (r is LiveLocationController.StartResult.Failed) effects.trySend(failText(r.reason))
        }
    }

    fun stopSharing() {
        viewModelScope.launch { controller.stop("stopped") }
    }

    fun resumeSharing() {
        viewModelScope.launch {
            val r = controller.resume()
            if (r is LiveLocationController.StartResult.Failed) effects.trySend(failText(r.reason))
        }
    }

    /** A new length applies at once to a manual session that is running (it may also shorten it). */
    fun setDuration(minutes: Int) {
        settings.update { it.copy(liveMinutes = minutes) }
        val s = controller.state.value
        if (s is ShareState.Sharing && !s.panic) viewModelScope.launch { controller.start(panic = false, minutes = minutes) }
    }

    private fun failText(reason: FailReason) = when (reason) {
        FailReason.NO_PERMISSION -> "Allow location to share it"
        FailReason.NO_PLAY_SERVICES -> "Live location needs Google Play services"
        FailReason.START_REFUSED -> "Open Risaina to start sharing"
    }

    // ---- partner's live location ---------------------------------------------------------------

    /**
     * While a location card is on screen: fixes normally arrive over the socket; when it is down,
     * ask the server every 30 s (15 s in an emergency).
     */
    suspend fun keepPartnerFresh() {
        liveRepo.refresh()
        while (true) {
            val panic = liveRepo.state.value.partner?.reason == "panic"
            delay(if (panic) 15_000 else 30_000)
            if (syncManager.socketState.value != SocketState.LIVE) liveRepo.refresh()
        }
    }

    // ---- SOS and panic -------------------------------------------------------------------------

    /** The hold completed (or TalkBack confirmed). Location is shared when allowed and permitted. */
    fun panic(message: String, shareLocation: Boolean) {
        access.value = readAccess()
        sender.send(message, panic = true, shareLocation = shareLocation && access.value.permitted)
    }

    /** From the partner-profile SOS dialog: sharing location makes it a panic alert. */
    fun sendSos(message: String, shareLocation: Boolean) {
        access.value = readAccess()
        val share = shareLocation && access.value.permitted
        sender.send(message, panic = share, shareLocation = share)
    }

    fun resetAttempt() = sender.reset()

    fun safeNow(sosId: String) {
        viewModelScope.launch {
            when (val r = sender.safeNow(sosId)) {
                is AppResult.Success -> effects.trySend("${state.value.partnerName} has been told you're safe")
                is AppResult.Failure -> effects.trySend(
                    if (r.error is AppError.Offline || r.error is AppError.Timeout) "No internet. Try again, or call ${state.value.partnerName}."
                    else r.error.message ?: "Couldn't send that. Try again.",
                )
            }
        }
    }

    /** "I'm on it" from the in-app SOS screen (never automatic: opening the screen does not count). */
    fun acknowledge(sosId: String) {
        alarm.acknowledgeById(sosId)
        sosRepository.markAcknowledged(sosId, Instant.now().toString())
    }

    // ---- readiness and tests ---------------------------------------------------------------------

    fun openFix(fix: ReadinessFix) = probe.open(fix)

    fun setFullVolume(on: Boolean) {
        settings.update { it.copy(fullVolume = on) }
        refreshDevice()
    }

    fun setShareWithSos(on: Boolean) = settings.update { it.copy(shareLocationWithSos = on) }

    fun setPartnerPhone(phone: String) = settings.update { it.copy(partnerPhone = phone.filter { c -> c.isDigit() || c in "+ -()" }.take(24)) }

    fun testOnThisPhone() {
        alarm.ringTest()
    }

    fun testThroughServer() {
        viewModelScope.launch {
            when (val r = apiCall { api.sosTest(SosTestRequest(delaySeconds = 15)) }) {
                is AppResult.Success -> effects.trySend(
                    if (r.value.queued) "Lock your phone or close Risaina now. The test rings in 15 seconds."
                    else "This phone isn't registered for push yet. Open Risaina again in a minute and retry.",
                )
                is AppResult.Failure -> effects.trySend(
                    if (r.error is AppError.RateLimited) "That's enough tests for now. Try again in a few minutes."
                    else r.error.message ?: "Couldn't start the test",
                )
            }
        }
    }

    /** Asks the launcher to pin a one-tap "I'm in trouble" icon (the user confirms; the hold still applies). */
    fun pinPanicShortcut() {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            effects.trySend("Your home screen doesn't support this. Long-press the Risaina icon instead.")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("risaina://app/panic"), context, MainActivity::class.java)
        val info = ShortcutInfoCompat.Builder(context, "panic_pinned")
            .setShortLabel(context.getString(R.string.shortcut_panic_short))
            .setLongLabel(context.getString(R.string.shortcut_panic_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_panic))
            .setIntent(intent)
            .build()
        runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }
            .onFailure { effects.trySend("Couldn't add it. Long-press the Risaina icon instead.") }
    }
}
