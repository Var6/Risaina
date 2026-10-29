package com.risaina.app.feature.settings

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.data.AuthRepository
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.data.ImageCompressor
import com.risaina.app.core.data.MediaRepository
import com.risaina.app.core.data.PairingState
import com.risaina.app.core.data.PreferencesRepository
import com.risaina.app.core.domain.TimeZones
import com.risaina.app.core.model.MediaCategory
import com.risaina.app.core.model.Preferences
import com.risaina.app.core.model.Profile
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import com.risaina.app.core.notifications.PushAvailability
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

data class SettingsUiState(
    val profile: Profile? = null,
    val prefs: Preferences = Preferences(),
    val pairing: PairingState = PairingState.Loading,
    val pushConfigured: Boolean = false,
    val notificationsPermitted: Boolean = true,
    val busy: Boolean = false,
    val avatarFile: File? = null,
    /**
     * The partner shares a zone that tells another time at some point in the year. Otherwise there
     * is no second clock to show, and "Show my partner's local time" is not offered.
     */
    val partnerOnAnotherClock: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val coupleRepository: CoupleRepository,
    private val authRepository: AuthRepository,
    private val mediaRepository: MediaRepository,
    private val imageCompressor: ImageCompressor,
    private val api: RisainaApi,
    private val json: Json,
    pushAvailability: PushAvailability,
    private val widgetSnapshotUpdater: com.risaina.app.core.sync.WidgetSnapshotUpdater,
    private val weatherRepository: com.risaina.app.core.data.WeatherRepository,
    private val time: TimeProvider,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val avatar = MutableStateFlow<File?>(null)
    private val effects = Channel<String>(Channel.BUFFERED)
    val snackbars: Flow<String> = effects.receiveAsFlow()
    private val pushConfigured = pushAvailability.configured

    val state: StateFlow<SettingsUiState> = combine(coupleRepository.profile, preferencesRepository.preferences, coupleRepository.pairingState, busy, avatar) { p, prefs, pairing, b, av ->
        SettingsUiState(
            profile = p, prefs = prefs, pairing = pairing, pushConfigured = pushConfigured,
            notificationsPermitted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            busy = b, avatarFile = av,
        )
    }.combine(coupleRepository.partner) { s, partner ->
        s.copy(partnerOnAnotherClock = !TimeZones.sameClockAllYear(time.zone(), TimeZones.zoneOf(partner?.timeZone), time.now()))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            coupleRepository.profile.collect { p -> avatar.value = p?.avatarMediaId?.let { mediaRepository.localFile(it) ?: mediaRepository.ensureCached(it, "image/jpeg").getOrNull() } }
        }
    }

    fun update(transform: (Preferences) -> Preferences) {
        viewModelScope.launch {
            val r = preferencesRepository.update(transform)
            if (r is AppResult.Failure) effects.trySend("Saved on this phone; will sync to the server when online")
        }
    }

    suspend fun searchCities(q: String) = weatherRepository.searchCities(q)

    fun updateCity(city: com.risaina.app.core.model.City?) {
        viewModelScope.launch { busy.value = true; coupleRepository.updateCity(city).onFailure { effects.trySend(it.message ?: "Couldn't save") }; busy.value = false }
    }

    fun setShareWeather(share: Boolean) {
        update { it.copy(shareWeather = share) }
        viewModelScope.launch {
            val body = kotlinx.serialization.json.buildJsonObject { put("shareWeather", kotlinx.serialization.json.JsonPrimitive(share)) }
            apiCall { api.patchMeRaw(body) }.onFailure { effects.trySend(it.message ?: "Couldn't save") }
        }
    }

    fun refreshLockCard() { viewModelScope.launch { kotlinx.coroutines.delay(300); widgetSnapshotUpdater.recompute() } }

    fun updateBirthday(birthday: String?) {
        viewModelScope.launch {
            busy.value = true
            coupleRepository.updateBirthday(birthday)
                .onSuccess { effects.trySend(if (birthday == null) "Birthday removed" else "Birthday saved") }
                .onFailure { effects.trySend(it.message ?: "Couldn't save your birthday") }
            busy.value = false
        }
    }

    /** Local preference first, then the server (which stops showing your time to your partner). */
    fun setShareLocalTime(share: Boolean) {
        update { it.copy(shareLocalTime = share) }
        viewModelScope.launch { coupleRepository.setShareLocalTime(share).onFailure { effects.trySend("Saved on this phone; will sync when online") } }
    }

    fun updateName(name: String) {
        viewModelScope.launch { busy.value = true; coupleRepository.updateProfile(name, null).onFailure { effects.trySend(it.message ?: "Couldn't save") }; busy.value = false }
    }

    fun updateAvatar(uri: Uri) {
        viewModelScope.launch {
            busy.value = true
            try {
                val c = imageCompressor.compress(uri, maxEdge = 768, quality = 85)
                when (val up = mediaRepository.upload(MediaCategory.AVATAR, c.file, c.mimeType, width = c.width, height = c.height)) {
                    is AppResult.Success -> coupleRepository.updateProfile(null, up.value.id).onFailure { effects.trySend(it.message ?: "Couldn't save") }
                    is AppResult.Failure -> effects.trySend(up.error.message ?: "Upload failed")
                }
                c.file.delete()
            } catch (e: Exception) { effects.trySend("Couldn't read that photo") }
            busy.value = false
        }
    }

    fun updateCouple(start: LocalDate?, nextMeeting: LocalDate?, label: String?) {
        viewModelScope.launch { coupleRepository.updateCouple(start, nextMeeting, label).onFailure { effects.trySend(it.message ?: "Couldn't save") } }
    }

    fun unlink(onDone: () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            when (val r = coupleRepository.unlink()) { is AppResult.Success -> onDone(); is AppResult.Failure -> effects.trySend(r.error.message ?: "Couldn't unlink") }
            busy.value = false
        }
    }

    fun signOut() { viewModelScope.launch { busy.value = true; authRepository.logout(); busy.value = false } }

    fun deleteAccount(password: String, onFailure: (String) -> Unit) {
        viewModelScope.launch {
            busy.value = true
            when (val r = authRepository.deleteAccount(password)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> onFailure(if (r.error is com.risaina.app.core.common.AppError.Unauthorized) "That password isn't right" else r.error.message ?: "Couldn't delete")
            }
            busy.value = false
        }
    }

    /** Saves a JSON export of the user's data to Downloads. */
    fun exportData() {
        viewModelScope.launch {
            busy.value = true
            val res = apiCall { api.exportRaw() }
            when (res) {
                is AppResult.Success -> {
                    val name = "risaina-export-${LocalDate.now()}.json"
                    val bytes = res.value.toByteArray()
                    val message = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, "application/json")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            "Saved $name to Downloads"
                        } else "Couldn't write to Downloads"
                    } else {
                        // API 26–28: scoped Downloads collection does not exist; use the app's own external folder.
                        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                        val f = File(dir, name)
                        f.writeBytes(bytes)
                        "Saved to ${f.absolutePath}"
                    }
                    effects.trySend(message)
                }
                is AppResult.Failure -> effects.trySend(res.error.message ?: "Export failed")
            }
            busy.value = false
        }
    }
}
