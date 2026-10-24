package com.risaina.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Period

class CountdownCalculatorTest {
    private val today = LocalDate.of(2026, 10, 25)

    @Test fun `future date counts days`() {
        val c = CountdownCalculator.until(LocalDate.of(2026, 12, 24), today)
        assertEquals(60, c.days); assertFalse(c.isToday); assertFalse(c.isPast); assertEquals("60 days", c.headline)
    }

    @Test fun `tomorrow and today headlines`() {
        assertEquals("Tomorrow", CountdownCalculator.until(today.plusDays(1), today).headline)
        val t = CountdownCalculator.until(today, today)
        assertTrue(t.isToday); assertEquals("Today", t.headline)
    }

    @Test fun `past date clamps to zero and flags past`() {
        val c = CountdownCalculator.until(today.minusDays(3), today)
        assertEquals(0, c.days); assertTrue(c.isPast); assertEquals("Passed", c.headline)
    }

    @Test fun `together period and formatting`() {
        val p = CountdownCalculator.together(LocalDate.of(2024, 7, 14), today)
        assertEquals(Period.of(2, 3, 11), p)
        assertEquals("2 years, 3 months, 11 days", CountdownCalculator.formatTogether(p))
        assertEquals("1 day", CountdownCalculator.formatTogether(Period.ofDays(1)))
        assertEquals("0 days", CountdownCalculator.formatTogether(Period.ZERO))
    }

    @Test fun `start date in the future yields zero period`() {
        assertEquals(Period.ZERO, CountdownCalculator.together(today.plusDays(10), today))
    }

    @Test fun `next anniversary rolls to next year when passed`() {
        assertEquals(LocalDate.of(2027, 7, 14), CountdownCalculator.nextAnniversary(LocalDate.of(2024, 7, 14), today))
        assertEquals(LocalDate.of(2026, 12, 1), CountdownCalculator.nextAnniversary(LocalDate.of(2020, 12, 1), today))
        assertEquals(today, CountdownCalculator.nextAnniversary(LocalDate.of(2019, 10, 25), today))
    }
}
