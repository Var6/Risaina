package com.risaina.app.core.domain

import com.risaina.app.core.model.QuietHours
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class QuietHoursPolicyTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, zone: ZoneId = kolkata): Instant =
        ZonedDateTime.of(2026, 10, 25, h, m, 0, 0, zone).toInstant()

    @Test
    fun `disabled quiet hours never quiet`() {
        val q = QuietHours(enabled = false, start = LocalTime.of(22, 0), end = LocalTime.of(7, 0), zone = kolkata)
        assertFalse(QuietHoursPolicy.isQuiet(q, at(23, 0)))
    }

    @Test
    fun `window crossing midnight is quiet late at night and early morning`() {
        val q = QuietHours(true, LocalTime.of(22, 30), LocalTime.of(7, 30), kolkata)
        assertTrue(QuietHoursPolicy.isQuiet(q, at(23, 0)))
        assertTrue(QuietHoursPolicy.isQuiet(q, at(2, 15)))
        assertTrue(QuietHoursPolicy.isQuiet(q, at(22, 30)))   // inclusive start
        assertFalse(QuietHoursPolicy.isQuiet(q, at(7, 30)))   // exclusive end
        assertFalse(QuietHoursPolicy.isQuiet(q, at(12, 0)))
    }

    @Test
    fun `same day window`() {
        val q = QuietHours(true, LocalTime.of(13, 0), LocalTime.of(15, 0), kolkata)
        assertTrue(QuietHoursPolicy.isQuiet(q, at(14, 0)))
        assertFalse(QuietHoursPolicy.isQuiet(q, at(15, 0)))
        assertFalse(QuietHoursPolicy.isQuiet(q, at(9, 0)))
    }

    @Test
    fun `evaluates in the configured zone not the device zone`() {
        val q = QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0), ZoneId.of("Europe/London"))
        // 23:00 in London on 2026-10-25 (BST ended, so GMT) == 04:30 next day in Kolkata
        val londonLate = ZonedDateTime.of(2026, 10, 25, 23, 0, 0, 0, ZoneId.of("Europe/London")).toInstant()
        assertTrue(QuietHoursPolicy.isQuiet(q, londonLate))
        val londonNoon = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneId.of("Europe/London")).toInstant()
        assertFalse(QuietHoursPolicy.isQuiet(q, londonNoon))
    }

    @Test
    fun `equal start and end is treated as disabled`() {
        val q = QuietHours(true, LocalTime.of(9, 0), LocalTime.of(9, 0), kolkata)
        assertFalse(QuietHoursPolicy.isQuiet(q, at(9, 0)))
    }

    @Test
    fun `shouldAlert respects allow override`() {
        val q = QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0), kolkata)
        assertFalse(QuietHoursPolicy.shouldAlert(q, allowInQuiet = false, at = at(23, 0)))
        assertTrue(QuietHoursPolicy.shouldAlert(q, allowInQuiet = true, at = at(23, 0)))
        assertTrue(QuietHoursPolicy.shouldAlert(q, allowInQuiet = false, at = at(12, 0)))
    }
}
