package com.risaina.app.core.domain

import com.risaina.app.core.data.WeatherRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherCodeTest {
    @Test fun `wmo codes map to emoji and words`() {
        assertEquals("☀️" to "clear", WeatherRepository.describe(0, true))
        assertEquals("🌙" to "clear", WeatherRepository.describe(0, false))
        assertEquals("🌧️" to "rain", WeatherRepository.describe(63, true))
        assertEquals("⛈️" to "thunderstorm", WeatherRepository.describe(95, true))
        assertEquals("❄️" to "snow", WeatherRepository.describe(73, true))
    }
}
