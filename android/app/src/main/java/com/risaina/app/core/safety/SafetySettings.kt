package com.risaina.app.core.safety

import android.content.Context
import androidx.core.content.edit
import com.risaina.app.core.domain.LiveLocationRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SafetyPrefs(
    /** Raise the alarm volume to the maximum while an SOS rings (restored afterwards). */
    val fullVolume: Boolean = true,
    /** Last length chosen for live location: 60, 480 or [LiveLocationRules.UNTIL_OFF]. */
    val liveMinutes: Int = LiveLocationRules.DEFAULT_MINUTES,
    /** "Also share my live location" in the SOS dialog. */
    val shareLocationWithSos: Boolean = true,
    /** The partner's phone number for Call / Text when an SOS is not getting through. This phone only. */
    val partnerPhone: String = "",
)

/**
 * Safety preferences, kept only on this phone. SharedPreferences rather than DataStore because the
 * SOS alarm service reads them synchronously in its first milliseconds, before any coroutine runs.
 */
@Singleton
class SafetySettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("safety_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SafetyPrefs> = _state

    val current: SafetyPrefs get() = _state.value

    fun update(transform: (SafetyPrefs) -> SafetyPrefs) {
        val next = transform(_state.value).let { p ->
            p.copy(liveMinutes = p.liveMinutes.takeIf { it in LiveLocationRules.MANUAL_CHOICES } ?: LiveLocationRules.DEFAULT_MINUTES)
        }
        prefs.edit {
            putBoolean(K_FULL_VOLUME, next.fullVolume)
            putInt(K_LIVE_MINUTES, next.liveMinutes)
            putBoolean(K_SHARE_WITH_SOS, next.shareLocationWithSos)
            putString(K_PARTNER_PHONE, next.partnerPhone.trim())
        }
        _state.value = next
    }

    private fun read() = SafetyPrefs(
        fullVolume = prefs.getBoolean(K_FULL_VOLUME, true),
        liveMinutes = prefs.getInt(K_LIVE_MINUTES, LiveLocationRules.DEFAULT_MINUTES),
        shareLocationWithSos = prefs.getBoolean(K_SHARE_WITH_SOS, true),
        partnerPhone = prefs.getString(K_PARTNER_PHONE, "").orEmpty(),
    )

    private companion object {
        const val K_FULL_VOLUME = "sos_full_volume"
        const val K_LIVE_MINUTES = "live_location_minutes"
        const val K_SHARE_WITH_SOS = "share_location_with_sos"
        const val K_PARTNER_PHONE = "partner_phone"
    }
}
