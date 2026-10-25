package com.risaina.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as DsPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.risaina.app.core.model.DarkMode
import com.risaina.app.core.model.Preferences
import com.risaina.app.core.model.QuietHours
import com.risaina.app.core.model.WidgetPrivacy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<DsPreferences> by preferencesDataStore(name = "settings")

/** Local copy of user preferences. The server copy (used for push decisions) is synced by PreferencesRepository. */
@Singleton
class PreferencesStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val notifications = booleanPreferencesKey("notifications_enabled")
        val preview = booleanPreferencesKey("preview_in_notifications")
        val quietEnabled = booleanPreferencesKey("quiet_enabled")
        val quietStart = stringPreferencesKey("quiet_start")
        val quietEnd = stringPreferencesKey("quiet_end")
        val quietZone = stringPreferencesKey("quiet_zone")
        val allowPushInQuiet = booleanPreferencesKey("allow_push_in_quiet")
        val cooldown = intPreferencesKey("ping_cooldown_seconds")
        val widgetPhoto = booleanPreferencesKey("widget_show_photo")
        val widgetNote = booleanPreferencesKey("widget_show_note")
        val widgetEmoji = booleanPreferencesKey("widget_show_emoji")
        val reducedMotion = booleanPreferencesKey("reduced_motion")
        val darkMode = stringPreferencesKey("dark_mode")
        val rituals = booleanPreferencesKey("rituals_enabled")
        val morning = stringPreferencesKey("morning_ritual")
        val night = stringPreferencesKey("night_ritual")
        val accent = stringPreferencesKey("accent")
        val background = stringPreferencesKey("background")
        val shareLocalTime = booleanPreferencesKey("share_local_time")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val notificationPromptShown = booleanPreferencesKey("notification_prompt_shown")
    }

    val preferences: Flow<Preferences> = context.settingsDataStore.data.map { p ->
        val d = Preferences()
        Preferences(
            notificationsEnabled = p[K.notifications] ?: d.notificationsEnabled,
            previewInNotifications = p[K.preview] ?: d.previewInNotifications,
            quietHours = QuietHours(
                enabled = p[K.quietEnabled] ?: d.quietHours.enabled,
                start = p[K.quietStart]?.let(LocalTime::parse) ?: d.quietHours.start,
                end = p[K.quietEnd]?.let(LocalTime::parse) ?: d.quietHours.end,
                zone = p[K.quietZone]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault(),
            ),
            allowPushInQuietHours = p[K.allowPushInQuiet] ?: d.allowPushInQuietHours,
            pingCooldownSeconds = p[K.cooldown] ?: d.pingCooldownSeconds,
            widgetPrivacy = WidgetPrivacy(
                showPhoto = p[K.widgetPhoto] ?: true,
                showNoteText = p[K.widgetNote] ?: true,
                showEmoji = p[K.widgetEmoji] ?: true,
            ),
            reducedMotion = p[K.reducedMotion] ?: false,
            darkMode = p[K.darkMode]?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() } ?: DarkMode.SYSTEM,
            ritualsEnabled = p[K.rituals] ?: false,
            morningRitualTime = p[K.morning]?.let(LocalTime::parse) ?: d.morningRitualTime,
            nightRitualTime = p[K.night]?.let(LocalTime::parse) ?: d.nightRitualTime,
            accent = p[K.accent] ?: d.accent,
            background = p[K.background] ?: d.background,
            shareLocalTime = p[K.shareLocalTime] ?: d.shareLocalTime,
        )
    }

    val onboardingDone: Flow<Boolean> = context.settingsDataStore.data.map { it[K.onboardingDone] ?: false }
    val notificationPromptShown: Flow<Boolean> = context.settingsDataStore.data.map { it[K.notificationPromptShown] ?: false }

    suspend fun current(): Preferences = preferences.first()

    suspend fun update(transform: (Preferences) -> Preferences) {
        val next = transform(current())
        context.settingsDataStore.edit { p ->
            p[K.notifications] = next.notificationsEnabled
            p[K.preview] = next.previewInNotifications
            p[K.quietEnabled] = next.quietHours.enabled
            p[K.quietStart] = next.quietHours.start.toString()
            p[K.quietEnd] = next.quietHours.end.toString()
            p[K.quietZone] = next.quietHours.zone.id
            p[K.allowPushInQuiet] = next.allowPushInQuietHours
            p[K.cooldown] = next.pingCooldownSeconds
            p[K.widgetPhoto] = next.widgetPrivacy.showPhoto
            p[K.widgetNote] = next.widgetPrivacy.showNoteText
            p[K.widgetEmoji] = next.widgetPrivacy.showEmoji
            p[K.reducedMotion] = next.reducedMotion
            p[K.darkMode] = next.darkMode.name
            p[K.rituals] = next.ritualsEnabled
            p[K.morning] = next.morningRitualTime.toString()
            p[K.night] = next.nightRitualTime.toString()
            p[K.accent] = next.accent
            p[K.background] = next.background
            p[K.shareLocalTime] = next.shareLocalTime
        }
    }

    suspend fun setOnboardingDone(done: Boolean) { context.settingsDataStore.edit { it[K.onboardingDone] = done } }
    suspend fun setNotificationPromptShown() { context.settingsDataStore.edit { it[K.notificationPromptShown] = true } }
}
