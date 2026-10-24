package com.risaina.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetDataStore: DataStore<Preferences> by preferencesDataStore(name = "widgets")

/**
 * What the home-screen widgets are allowed to show. Written by the sync layer *after* applying
 * the user's widget privacy settings, so Glance never has to make privacy decisions itself and
 * never needs the network. Photo is a local file path inside the app's media cache.
 */
data class WidgetSnapshot(
    val partnerName: String? = null,
    val latestNoteText: String? = null,
    val latestNoteAtMillis: Long? = null,
    val latestNoteFromPartner: Boolean = false,
    val latestEmoji: String? = null,
    val latestEmojiAtMillis: Long? = null,
    val latestEmojiLabel: String? = null,
    val photoPath: String? = null,
    val photoCaption: String? = null,
    val countdownLabel: String? = null,
    val countdownDateIso: String? = null,
    val latestVoiceNoteId: String? = null,
    val latestVoiceDurationMs: Long? = null,
    val latestVoiceAtMillis: Long? = null,
    val paired: Boolean = false,
)

@Singleton
class WidgetSnapshotStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val partnerName = stringPreferencesKey("partner_name")
        val noteText = stringPreferencesKey("note_text")
        val noteAt = longPreferencesKey("note_at")
        val noteFromPartner = stringPreferencesKey("note_from_partner")
        val emoji = stringPreferencesKey("emoji")
        val emojiAt = longPreferencesKey("emoji_at")
        val emojiLabel = stringPreferencesKey("emoji_label")
        val photoPath = stringPreferencesKey("photo_path")
        val photoCaption = stringPreferencesKey("photo_caption")
        val countdownLabel = stringPreferencesKey("countdown_label")
        val countdownDate = stringPreferencesKey("countdown_date")
        val voiceId = stringPreferencesKey("voice_id")
        val voiceDuration = longPreferencesKey("voice_duration")
        val voiceAt = longPreferencesKey("voice_at")
        val paired = stringPreferencesKey("paired")
    }

    val snapshot: Flow<WidgetSnapshot> = context.widgetDataStore.data.map { p ->
        WidgetSnapshot(
            partnerName = p[K.partnerName],
            latestNoteText = p[K.noteText],
            latestNoteAtMillis = p[K.noteAt],
            latestNoteFromPartner = p[K.noteFromPartner] == "1",
            latestEmoji = p[K.emoji],
            latestEmojiAtMillis = p[K.emojiAt],
            latestEmojiLabel = p[K.emojiLabel],
            photoPath = p[K.photoPath],
            photoCaption = p[K.photoCaption],
            countdownLabel = p[K.countdownLabel],
            countdownDateIso = p[K.countdownDate],
            latestVoiceNoteId = p[K.voiceId],
            latestVoiceDurationMs = p[K.voiceDuration],
            latestVoiceAtMillis = p[K.voiceAt],
            paired = p[K.paired] == "1",
        )
    }

    suspend fun current(): WidgetSnapshot = snapshot.first()

    suspend fun update(transform: (WidgetSnapshot) -> WidgetSnapshot) {
        val s = transform(current())
        context.widgetDataStore.edit { p ->
            fun put(k: Preferences.Key<String>, v: String?) { if (v == null) p.remove(k) else p[k] = v }
            fun putL(k: Preferences.Key<Long>, v: Long?) { if (v == null) p.remove(k) else p[k] = v }
            put(K.partnerName, s.partnerName)
            put(K.noteText, s.latestNoteText); putL(K.noteAt, s.latestNoteAtMillis); put(K.noteFromPartner, if (s.latestNoteFromPartner) "1" else "0")
            put(K.emoji, s.latestEmoji); putL(K.emojiAt, s.latestEmojiAtMillis); put(K.emojiLabel, s.latestEmojiLabel)
            put(K.photoPath, s.photoPath); put(K.photoCaption, s.photoCaption)
            put(K.countdownLabel, s.countdownLabel); put(K.countdownDate, s.countdownDateIso)
            put(K.voiceId, s.latestVoiceNoteId); putL(K.voiceDuration, s.latestVoiceDurationMs); putL(K.voiceAt, s.latestVoiceAtMillis)
            put(K.paired, if (s.paired) "1" else "0")
        }
    }

    suspend fun clear() { context.widgetDataStore.edit { it.clear() } }
}
