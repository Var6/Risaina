package com.risaina.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.risaina.app.core.model.Profile
import com.risaina.app.core.model.Session
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * Holds the signed-in session. Lives in app-private storage and is excluded from cloud backup
 * and device transfer (res/xml/backup_rules.xml). Keystore-backed encryption is tracked as
 * open item O-03 in docs/COUNCIL_DECISIONS.md.
 */
@Singleton
class SessionStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val userId = stringPreferencesKey("user_id")
        val access = stringPreferencesKey("access_token")
        val accessExp = longPreferencesKey("access_expires_at")
        val refresh = stringPreferencesKey("refresh_token")
        val refreshExp = longPreferencesKey("refresh_expires_at")
        val deviceId = stringPreferencesKey("device_id")
        val lastEventSeq = longPreferencesKey("last_event_seq")
        val activeCoupleId = stringPreferencesKey("active_couple_id")
        val lastPingSentAt = longPreferencesKey("last_ping_sent_at")
        val displayName = stringPreferencesKey("display_name")
        val avatarMediaId = stringPreferencesKey("avatar_media_id")
        val prefsDirty = stringPreferencesKey("prefs_dirty")
        val emailVerified = stringPreferencesKey("email_verified")
        val birthday = stringPreferencesKey("birthday")
        val hasPassword = stringPreferencesKey("has_password")
        val sharedTimeZone = stringPreferencesKey("shared_time_zone")
        val cityJson = stringPreferencesKey("city")
        /** Events up to this seq were history when this phone started from cursor 0 (see ReplayGuard). */
        val bootstrapUntilSeq = longPreferencesKey("bootstrap_until_seq")
    }

    val session: Flow<Session?> = context.sessionDataStore.data.map { p ->
        val userId = p[Keys.userId] ?: return@map null
        val access = p[Keys.access] ?: return@map null
        val refresh = p[Keys.refresh] ?: return@map null
        Session(
            userId = userId,
            accessToken = access,
            accessExpiresAt = Instant.ofEpochMilli(p[Keys.accessExp] ?: 0L),
            refreshToken = refresh,
            refreshExpiresAt = Instant.ofEpochMilli(p[Keys.refreshExp] ?: 0L),
            deviceId = p[Keys.deviceId] ?: "",
        )
    }

    val lastEventSeq: Flow<Long> = context.sessionDataStore.data.map { it[Keys.lastEventSeq] ?: 0L }
    val bootstrapUntilSeq: Flow<Long> = context.sessionDataStore.data.map { it[Keys.bootstrapUntilSeq] ?: 0L }
    val activeCoupleId: Flow<String?> = context.sessionDataStore.data.map { it[Keys.activeCoupleId] }
    val lastPingSentAt: Flow<Instant?> = context.sessionDataStore.data.map { p -> p[Keys.lastPingSentAt]?.let(Instant::ofEpochMilli) }

    val profile: Flow<Profile?> = context.sessionDataStore.data.map { p ->
        val userId = p[Keys.userId] ?: return@map null
        Profile(userId, p[Keys.displayName] ?: "", p[Keys.avatarMediaId], p[Keys.emailVerified] != "0", p[Keys.birthday], p[Keys.hasPassword] != "0",
            p[Keys.cityJson]?.split("|")?.takeIf { it.size == 3 }?.let { com.risaina.app.core.model.City(it[0], it[1].toDouble(), it[2].toDouble()) })
    }
    val prefsDirty: Flow<Boolean> = context.sessionDataStore.data.map { it[Keys.prefsDirty] == "1" }

    suspend fun current(): Session? = session.first()

    suspend fun saveProfile(displayName: String, avatarMediaId: String?, emailVerified: Boolean? = null) {
        context.sessionDataStore.edit { p ->
            if (emailVerified != null) p[Keys.emailVerified] = if (emailVerified) "1" else "0"
            p[Keys.displayName] = displayName
            if (avatarMediaId == null) p.remove(Keys.avatarMediaId) else p[Keys.avatarMediaId] = avatarMediaId
        }
    }

    suspend fun saveExtras(birthday: String?, hasPassword: Boolean, sharedTimeZone: String?) {
        context.sessionDataStore.edit { p ->
            if (birthday == null) p.remove(Keys.birthday) else p[Keys.birthday] = birthday
            p[Keys.hasPassword] = if (hasPassword) "1" else "0"
            if (sharedTimeZone == null) p.remove(Keys.sharedTimeZone) else p[Keys.sharedTimeZone] = sharedTimeZone
        }
    }

    suspend fun saveCity(city: com.risaina.app.core.model.City?) {
        context.sessionDataStore.edit { p -> if (city == null) p.remove(Keys.cityJson) else p[Keys.cityJson] = "${city.name.replace("|", " ")}|${city.latitude}|${city.longitude}" }
    }

    /** The zone last reported to the server, so we only PATCH when it changes (travel, DST rules). */
    suspend fun sharedTimeZone(): String? = context.sessionDataStore.data.first()[Keys.sharedTimeZone]

    suspend fun setPrefsDirty(dirty: Boolean) {
        context.sessionDataStore.edit { p -> if (dirty) p[Keys.prefsDirty] = "1" else p.remove(Keys.prefsDirty) }
    }

    /** Stable per-install device identifier used for push token and session bookkeeping. */
    suspend fun deviceId(): String {
        val existing = context.sessionDataStore.data.first()[Keys.deviceId]
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        context.sessionDataStore.edit { it[Keys.deviceId] = fresh }
        return fresh
    }

    suspend fun save(session: Session) {
        context.sessionDataStore.edit { p ->
            p[Keys.userId] = session.userId
            p[Keys.access] = session.accessToken
            p[Keys.accessExp] = session.accessExpiresAt.toEpochMilli()
            p[Keys.refresh] = session.refreshToken
            p[Keys.refreshExp] = session.refreshExpiresAt.toEpochMilli()
            p[Keys.deviceId] = session.deviceId
        }
    }

    suspend fun updateTokens(access: String, accessExp: Instant, refresh: String, refreshExp: Instant) {
        context.sessionDataStore.edit { p ->
            p[Keys.access] = access
            p[Keys.accessExp] = accessExp.toEpochMilli()
            p[Keys.refresh] = refresh
            p[Keys.refreshExp] = refreshExp.toEpochMilli()
        }
    }

    /**
     * Starting from cursor 0 (fresh sign-in or a new pairing), everything up to [latestSeq] is
     * history: it is applied, but must not notify, reveal or buzz as if it were new. See [markBootstrap].
     */
    suspend fun setBootstrapUntilSeqIfFresh(latestSeq: Long) {
        context.sessionDataStore.edit { p -> markBootstrap(p, latestSeq) }
    }

    suspend fun setLastEventSeq(seq: Long) {
        context.sessionDataStore.edit { p -> advanceCursor(p, seq) }
    }

    /**
     * Event numbers start at 0 for every couple, so pairing with a different couple must restart
     * the cursor; otherwise the new couple's first events look "already applied" and are skipped.
     * Unlinking keeps the cursor, so the old couple's `couple.unlinked` is not replayed.
     */
    suspend fun setActiveCoupleId(id: String?) {
        context.sessionDataStore.edit { p -> bindCouple(p, id) }
    }

    /**
     * Records the couple whose events this phone is applying right now (EventReducer, on an active
     * `couple.updated`), keeping the cursor and the replay mark. See [adoptCouple].
     */
    suspend fun adoptActiveCoupleId(id: String) {
        context.sessionDataStore.edit { p -> adoptCouple(p, id) }
    }

    suspend fun setLastPingSentAt(at: Instant) {
        context.sessionDataStore.edit { it[Keys.lastPingSentAt] = at.toEpochMilli() }
    }

    /** Clears everything except the device id, which stays stable across sign-ins. */
    suspend fun clear() {
        context.sessionDataStore.edit { p ->
            val device = p[Keys.deviceId]
            p.clear()
            if (device != null) p[Keys.deviceId] = device
        }
    }

    /** The cursor rules as plain edits of the stored preferences, so they are unit-tested (SessionCursorTest). */
    internal companion object {
        fun advanceCursor(p: MutablePreferences, seq: Long) {
            if (seq > (p[Keys.lastEventSeq] ?: 0L)) p[Keys.lastEventSeq] = seq
        }

        /**
         * The mark is written once per cursor-0 epoch: the first of the socket's Ready and a sync's
         * first page wins. Overwriting it (with a later, higher latestSeq while the cursor is still 0)
         * would turn events created after the first snapshot into "history", and a ping the partner
         * sent at that moment would never buzz or get its delivered receipt. A new epoch starts with
         * no mark: [bindCouple] and [clear] remove it together with the cursor.
         */
        fun markBootstrap(p: MutablePreferences, latestSeq: Long) {
            if ((p[Keys.lastEventSeq] ?: 0L) == 0L && p[Keys.bootstrapUntilSeq] == null) p[Keys.bootstrapUntilSeq] = latestSeq
        }

        fun bindCouple(p: MutablePreferences, id: String?) {
            if (id == null) { p.remove(Keys.activeCoupleId); return }
            if (p[Keys.activeCoupleId] != id) { p[Keys.lastEventSeq] = 0L; p.remove(Keys.bootstrapUntilSeq) }
            p[Keys.activeCoupleId] = id
        }

        /**
         * The server only serves the active couple's events, so while the phone applies an active
         * `couple.updated` its cursor (and replay mark) already belong to that couple: store the
         * couple without restarting them. Only fills an empty slot. This used to happen later, in
         * refreshMe through [bindCouple], which saw "no couple → this couple", reset the cursor to 0
         * after the sync had moved it, and made the next push's events count as history (no
         * notification, no delivered tick). A different stored couple is left to refreshMe, which
         * wipes the old couple's data and restarts the cursor on purpose.
         */
        fun adoptCouple(p: MutablePreferences, id: String) {
            if (p[Keys.activeCoupleId] == null) p[Keys.activeCoupleId] = id
        }
    }
}
