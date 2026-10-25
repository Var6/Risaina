package com.risaina.app.core.data

import com.risaina.app.core.model.City
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.roundToInt

data class Weather(val temperatureC: Int, val emoji: String, val label: String, val isDay: Boolean)

@Serializable private data class Forecast(val current: Current? = null)
@Serializable private data class Current(
    @SerialName("temperature_2m") val temperature: Double,
    @SerialName("weather_code") val code: Int,
    @SerialName("is_day") val isDay: Int = 1,
)
@Serializable private data class GeoResults(val results: List<GeoResult> = emptyList())
@Serializable private data class GeoResult(val name: String, val latitude: Double, val longitude: Double, val country: String? = null, val admin1: String? = null)

/**
 * Partner weather from Open-Meteo (free, no API key, no account). Only the shared city's rounded
 * coordinates are sent. Results are cached for 30 minutes so Home doesn't hit the network on
 * every visit.
 */
@Singleton
class WeatherRepository @Inject constructor(@Named("plain") private val http: HttpClient) {
    private val cache = HashMap<String, Pair<Instant, Weather>>()
    private val mutex = Mutex()

    suspend fun current(city: City): Weather? {
        val key = "%.2f,%.2f".format(city.latitude, city.longitude)
        mutex.withLock { cache[key]?.let { (at, w) -> if (at.isAfter(Instant.now().minusSeconds(1800))) return w } }
        val w = runCatching {
            http.get("https://api.open-meteo.com/v1/forecast") {
                parameter("latitude", city.latitude); parameter("longitude", city.longitude)
                parameter("current", "temperature_2m,weather_code,is_day")
            }.body<Forecast>().current
        }.getOrNull()?.let { c -> describe(c.code, c.isDay == 1).let { (e, l) -> Weather(c.temperature.roundToInt(), e, l, c.isDay == 1) } } ?: return null
        mutex.withLock { cache[key] = Instant.now() to w }
        return w
    }

    /** City search for "My city" in the profile. */
    suspend fun searchCities(query: String): List<City> {
        if (query.trim().length < 2) return emptyList()
        return runCatching {
            http.get("https://geocoding-api.open-meteo.com/v1/search") {
                parameter("name", query.trim()); parameter("count", 6); parameter("language", "en")
            }.body<GeoResults>().results.map { r ->
                City(listOfNotNull(r.name, r.admin1?.takeIf { it != r.name }, r.country).joinToString(", "), r.latitude, r.longitude)
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** WMO weather codes → emoji + words. */
        fun describe(code: Int, day: Boolean): Pair<String, String> = when (code) {
            0 -> (if (day) "☀️" else "🌙") to "clear"
            1, 2 -> (if (day) "🌤️" else "🌙") to "mostly clear"
            3 -> "☁️" to "cloudy"
            45, 48 -> "🌫️" to "foggy"
            51, 53, 55, 56, 57 -> "🌦️" to "drizzle"
            61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️" to "rain"
            71, 73, 75, 77, 85, 86 -> "❄️" to "snow"
            95, 96, 99 -> "⛈️" to "thunderstorm"
            else -> "🌡️" to "now"
        }
    }
}
