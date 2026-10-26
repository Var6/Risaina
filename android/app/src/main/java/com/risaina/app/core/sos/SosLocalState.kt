package com.risaina.app.core.sos

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this phone already did about each SOS, kept across process deaths. The in-memory SOS list is
 * empty in a cold process started by a push, so it cannot stop a re-push or a replay from ringing
 * again; this can. Keyed by SOS id and by the `sos.raised` event seq (the outbox's push only knows
 * the seq), linked once both are known.
 *
 * Also holds the alarm-volume bookkeeping, so a crash mid-ring still restores the user's volume on
 * the next start. SharedPreferences: the alarm service reads it synchronously before ringing.
 */
@Singleton
class SosLocalState @Inject constructor(@ApplicationContext context: Context) {
    @Serializable
    data class Entry(
        val sosId: String? = null,
        val seq: Long? = null,
        val rangAt: Long? = null,
        val ackedAt: Long? = null,
        val ackSent: Boolean = false,
        val resolvedAt: Long? = null,
        val deliveredSent: Boolean = false,
        val touchedAt: Long = 0,
    )

    private val prefs = context.getSharedPreferences("sos_local_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private fun load(): MutableList<Entry> = runCatching {
        json.decodeFromString<List<Entry>>(prefs.getString(K_ENTRIES, "[]") ?: "[]").toMutableList()
    }.getOrDefault(mutableListOf())

    private fun save(entries: List<Entry>) {
        // A week of history is plenty; bounded so the file never grows.
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3_600_000
        val kept = entries.filter { it.touchedAt >= cutoff }.sortedByDescending { it.touchedAt }.take(MAX_ENTRIES)
        prefs.edit { putString(K_ENTRIES, json.encodeToString(kept)) }
    }

    private fun find(entries: List<Entry>, sosId: String?, seq: Long?): Entry? =
        entries.firstOrNull { (sosId != null && it.sosId == sosId) || (seq != null && it.seq == seq) }

    fun entry(sosId: String?, seq: Long?): Entry? = synchronized(lock) { find(load(), sosId, seq) }

    /** Acknowledged here or elsewhere, or resolved by the sender: never ring for it again. */
    fun isClosed(sosId: String?, seq: Long?): Boolean = entry(sosId, seq)?.let { it.ackedAt != null || it.resolvedAt != null } == true

    /** Closed, or it already rang on this phone (a re-push or replay must not ring it twice). */
    fun isHandled(sosId: String?, seq: Long?): Boolean = entry(sosId, seq)?.let { it.ackedAt != null || it.resolvedAt != null || it.rangAt != null } == true

    fun sosIdForSeq(seq: Long): String? = entry(null, seq)?.sosId

    private fun upsert(sosId: String?, seq: Long?, change: (Entry) -> Entry) = synchronized(lock) {
        if (sosId == null && seq == null) return@synchronized
        val entries = load()
        val existing = find(entries, sosId, seq)
        val base = existing ?: Entry()
        val merged = change(base).copy(sosId = sosId ?: base.sosId, seq = seq ?: base.seq, touchedAt = System.currentTimeMillis())
        if (existing != null) entries.remove(existing)
        entries.add(merged)
        save(entries)
    }

    fun link(seq: Long, sosId: String) = upsert(sosId, seq) { it }
    fun markRang(sosId: String?, seq: Long?) = upsert(sosId, seq) { it.copy(rangAt = it.rangAt ?: System.currentTimeMillis()) }
    fun markAcked(sosId: String?, seq: Long?) = upsert(sosId, seq) { it.copy(ackedAt = it.ackedAt ?: System.currentTimeMillis()) }
    fun markAckSent(sosId: String) = upsert(sosId, null) { it.copy(ackSent = true) }
    fun markResolved(sosId: String) = upsert(sosId, null) { it.copy(resolvedAt = it.resolvedAt ?: System.currentTimeMillis()) }
    fun markDeliveredSent(sosId: String) = upsert(sosId, null) { it.copy(deliveredSent = true) }

    // --- alarm volume bookkeeping -------------------------------------------------------------

    /** The user's alarm volume before an SOS raised it, or -1. Saved only once per ring (a second SOS must not overwrite it with max). */
    var savedAlarmVolume: Int
        get() = prefs.getInt(K_SAVED_VOLUME, -1)
        set(v) = prefs.edit { putInt(K_SAVED_VOLUME, v) }

    /** The volume the alarm set, so it is restored only if nobody changed it meanwhile. */
    var raisedAlarmVolume: Int
        get() = prefs.getInt(K_RAISED_VOLUME, -1)
        set(v) = prefs.edit { putInt(K_RAISED_VOLUME, v) }

    /** Wall-clock end of the current ring (0 when silent). In the past on start = the ring died with the process. */
    var ringingUntil: Long
        get() = prefs.getLong(K_RINGING_UNTIL, 0L)
        set(v) = prefs.edit { putLong(K_RINGING_UNTIL, v) }

    fun clear() = synchronized(lock) { prefs.edit { remove(K_ENTRIES) } }

    private companion object {
        const val K_ENTRIES = "entries"
        const val K_SAVED_VOLUME = "saved_alarm_volume"
        const val K_RAISED_VOLUME = "raised_alarm_volume"
        const val K_RINGING_UNTIL = "ringing_until"
        const val MAX_ENTRIES = 60
    }
}
