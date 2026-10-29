package com.risaina.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.risaina.app.core.domain.OccasionReminders
import com.risaina.app.core.domain.ReminderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import androidx.datastore.preferences.core.Preferences as DsPreferences

private val Context.reminderDataStore: DataStore<DsPreferences> by preferencesDataStore(name = "reminders")

/**
 * Calendar reminder settings for this phone (D6). Never synced: the partner's phone has its own,
 * and only this phone acts on them. A missing value is the default, so a fresh install reminds of
 * the anniversary and the partner's birthday without being set up.
 */
@Singleton
class ReminderSettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val anniversaryOn = booleanPreferencesKey("anniversary_on")
        val anniversaryDays = stringPreferencesKey("anniversary_days")
        val partnerOn = booleanPreferencesKey("partner_birthday_on")
        val partnerDays = stringPreferencesKey("partner_birthday_days")
        val mineOn = booleanPreferencesKey("my_birthday_on")
        val mineDays = stringPreferencesKey("my_birthday_days")
        val visitOn = booleanPreferencesKey("next_visit_on")
        val visitDays = stringPreferencesKey("next_visit_days")
        val milestonesOn = booleanPreferencesKey("milestones_on")
        val milestonesDays = stringPreferencesKey("milestones_days")
        val allDayTime = stringPreferencesKey("all_day_time")
        val partnerMidnight = booleanPreferencesKey("partner_midnight")
        val since = longPreferencesKey("since")
    }

    val settings: Flow<ReminderSettings> = context.reminderDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map(::read)

    private fun read(p: DsPreferences): ReminderSettings {
        val d = ReminderSettings()
        fun occasion(on: DsPreferences.Key<Boolean>, days: DsPreferences.Key<String>, default: OccasionReminders) =
            OccasionReminders(p[on] ?: default.on, p[days]?.let(ReminderDays::parse) ?: default.daysBefore)
        return ReminderSettings(
            enabled = p[K.enabled] ?: d.enabled,
            anniversary = occasion(K.anniversaryOn, K.anniversaryDays, d.anniversary),
            partnerBirthday = occasion(K.partnerOn, K.partnerDays, d.partnerBirthday),
            myBirthday = occasion(K.mineOn, K.mineDays, d.myBirthday),
            nextVisit = occasion(K.visitOn, K.visitDays, d.nextVisit),
            milestones = occasion(K.milestonesOn, K.milestonesDays, d.milestones),
            allDayTime = p[K.allDayTime]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.allDayTime,
            partnerMidnight = p[K.partnerMidnight] ?: d.partnerMidnight,
            since = p[K.since]?.let(Instant::ofEpochMilli),
        )
    }

    suspend fun current(): ReminderSettings = settings.first()

    /**
     * Saves what the settings sheet changed. Turning reminders back on starts afresh from now, so
     * what was missed while they were off does not arrive in a burst.
     */
    suspend fun update(now: Instant = Instant.now(), transform: (ReminderSettings) -> ReminderSettings) {
        context.reminderDataStore.edit { p ->
            val before = read(p)
            var after = transform(before)
            if (!before.enabled && after.enabled) after = after.copy(since = now)
            write(p, after)
        }
    }

    /** The first time reminders run on this phone (or for this couple): nothing older is ever posted. */
    suspend fun startIfNeeded(now: Instant) {
        context.reminderDataStore.edit { p -> if (p[K.since] == null) p[K.since] = now.toEpochMilli() }
    }

    /** Sign-out or unlink: the next couple's calendar starts afresh. The choices themselves stay. */
    suspend fun forgetStart() {
        context.reminderDataStore.edit { it.remove(K.since) }
    }

    private fun write(p: MutablePreferences, s: ReminderSettings) {
        p[K.enabled] = s.enabled
        p[K.anniversaryOn] = s.anniversary.on; p[K.anniversaryDays] = ReminderDays.format(s.anniversary.daysBefore)
        p[K.partnerOn] = s.partnerBirthday.on; p[K.partnerDays] = ReminderDays.format(s.partnerBirthday.daysBefore)
        p[K.mineOn] = s.myBirthday.on; p[K.mineDays] = ReminderDays.format(s.myBirthday.daysBefore)
        p[K.visitOn] = s.nextVisit.on; p[K.visitDays] = ReminderDays.format(s.nextVisit.daysBefore)
        p[K.milestonesOn] = s.milestones.on; p[K.milestonesDays] = ReminderDays.format(s.milestones.daysBefore)
        p[K.allDayTime] = s.allDayTime.toString()
        p[K.partnerMidnight] = s.partnerMidnight
        s.since?.let { p[K.since] = it.toEpochMilli() } ?: p.remove(K.since)
    }
}

/** "7,1,0" and back. Pure (ReminderDaysTest): a damaged value reads as the defaults, never a crash. */
object ReminderDays {
    /** At most 4 choices from 0 to 28 days before, largest first. */
    fun parse(s: String): List<Int>? =
        if (s.isBlank()) emptyList()
        else s.split(',').map { it.trim().toIntOrNull() ?: return null }.filter { it in 0..28 }.distinct().sortedDescending().take(4)

    fun format(days: List<Int>): String = days.filter { it in 0..28 }.distinct().sortedDescending().take(4).joinToString(",")
}
