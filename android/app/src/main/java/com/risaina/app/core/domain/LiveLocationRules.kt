package com.risaina.app.core.domain

import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Pure rules for live location sharing (unit-tested; no Android types). */
object LiveLocationRules {
    enum class Mode { MANUAL, PANIC }

    const val DEFAULT_MINUTES = 60
    const val LONG_MINUTES = 480
    /** "Until I turn it off". */
    const val UNTIL_OFF = -1
    const val PANIC_MINUTES = 120
    val MANUAL_CHOICES = listOf(DEFAULT_MINUTES, LONG_MINUTES, UNTIL_OFF)

    /** The server refuses fixes older than this (it would show the partner a stale place as live). */
    const val MAX_FIX_AGE_MS = 10 * 60_000L
    /** Re-upload with no movement, so the partner can tell "still, but sharing" from "phone off". */
    const val HEARTBEAT_MANUAL_MS = 5 * 60_000L
    const val HEARTBEAT_PANIC_MS = 30_000L
    const val PANIC_MIN_GAP_MS = 10_000L
    /** Unknown accuracy (`!location.hasAccuracy()`) is sent as "approximate", never as a perfect 0 m. */
    const val UNKNOWN_ACCURACY_M = 5_000.0

    data class Sample(val lat: Double, val lng: Double, val accuracyM: Double)
    data class Uploaded(val lat: Double, val lng: Double, val accuracyM: Double, val atElapsedMs: Long)

    fun heartbeatMs(mode: Mode): Long = if (mode == Mode.PANIC) HEARTBEAT_PANIC_MS else HEARTBEAT_MANUAL_MS

    /**
     * Whether a new fix is worth uploading: the first one; in panic every 10 s; real movement (25 m,
     * or half the fix's own accuracy when that is larger); a much better accuracy; or the heartbeat.
     */
    fun shouldUpload(last: Uploaded?, next: Sample, mode: Mode, nowElapsedMs: Long): Boolean {
        if (last == null) return true
        val since = nowElapsedMs - last.atElapsedMs
        if (mode == Mode.PANIC && since >= PANIC_MIN_GAP_MS) return true
        if (distanceMeters(last.lat, last.lng, next.lat, next.lng) >= max(25.0, 0.5 * next.accuracyM)) return true
        if (last.accuracyM > 50 && next.accuracyM < 0.5 * last.accuracyM) return true
        return since >= heartbeatMs(mode)
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Age of a fix from the monotonic clock (immune to a wrong wall clock), clamped to what the server takes. */
    fun ageMs(nowElapsedNanos: Long, fixElapsedNanos: Long): Long =
        ((nowElapsedNanos - fixElapsedNanos) / 1_000_000L).coerceIn(0L, MAX_FIX_AGE_MS)

    fun batteryPct(raw: Int?): Int? = raw?.takeIf { it in 0..100 }

    enum class Freshness { WAITING, LIVE, STALE, OLD }

    /**
     * Liveness comes from the last check-in (`lastSeenAt`), not the fix time: a sharer who is sitting
     * still keeps checking in with the same fix and must still look live.
     */
    fun freshness(lastSeenAt: Instant?, hasFix: Boolean, panic: Boolean, now: Instant): Freshness {
        if (lastSeenAt == null) return if (hasFix) Freshness.OLD else Freshness.WAITING
        val age = Duration.between(lastSeenAt, now)
        val liveWindow = if (panic) Duration.ofSeconds(90) else Duration.ofMinutes(7)
        return when {
            !hasFix && age <= liveWindow -> Freshness.WAITING
            age <= liveWindow -> Freshness.LIVE
            age <= Duration.ofMinutes(30) -> Freshness.STALE
            else -> Freshness.OLD
        }
    }

    fun accuracyLabel(accuracyM: Double): String = when {
        accuracyM < 1_000 -> "±${max(1, accuracyM.roundToInt())} m"
        else -> {
            val km = accuracyM / 1000.0
            val text = if (km < 10) String.format(Locale.US, "%.1f", km).removeSuffix(".0") else km.roundToInt().toString()
            "±$text km"
        }
    }

    fun isApproximate(accuracyM: Double): Boolean = accuracyM > 500

    fun isActive(expiresAt: Instant?, now: Instant): Boolean = expiresAt == null || now.isBefore(expiresAt)

    /** Always '.' as the decimal mark: a German or French locale must not produce "geo:25,59…". */
    fun geoUri(lat: Double, lng: Double, label: String): String =
        String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lng, lat, lng, URLEncoder.encode(label, "UTF-8").replace("+", "%20"))

    fun mapsWebUrl(lat: Double, lng: Double): String =
        String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", lat, lng)

    /** Text for the "share my location" fallback when the SOS could not be sent. */
    fun troubleText(message: String?, lat: Double?, lng: Double?): String {
        val base = message?.trim()?.takeIf { it.isNotEmpty() } ?: "I'm in trouble"
        return if (lat != null && lng != null) "$base. My location: ${mapsWebUrl(lat, lng)}" else "$base. Please call me."
    }

    /**
     * A session id made on the phone (MongoDB ObjectId shape: 4-byte time + 8 random bytes, 24 hex) so
     * a stop can name the session even while its start is still on the way to the server.
     */
    fun newSessionId(nowEpochSeconds: Long, random: Random = Random.Default): String {
        val time = String.format(Locale.US, "%08x", nowEpochSeconds and 0xffffffffL)
        val rest = (0 until 8).joinToString("") { String.format(Locale.US, "%02x", random.nextInt(256)) }
        return time + rest
    }

    fun minutesLabel(minutes: Int): String = when (minutes) {
        UNTIL_OFF -> "Until I turn it off"
        DEFAULT_MINUTES -> "1 hour"
        LONG_MINUTES -> "8 hours"
        PANIC_MINUTES -> "2 hours"
        else -> if (minutes % 60 == 0) "${minutes / 60} hours" else "$minutes min"
    }

    /** "just now", "4 min ago", "2 h ago". */
    fun ago(at: Instant, now: Instant): String {
        val s = Duration.between(at, now).seconds.coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3_600 -> "${s / 60} min ago"
            else -> "${s / 3_600} h ago"
        }
    }
}
