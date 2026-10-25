package com.risaina.app.core.domain

import com.risaina.app.feature.settings.BirthdayInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BirthdayInputTest {
    private val today = LocalDate.of(2026, 10, 25)
    private fun ok(d: String, m: Int, y: String) = (BirthdayInput.validate(d, m, y, today) as BirthdayInput.Result.Ok).wire

    @Test fun `full date and private year`() {
        assertEquals("1999-03-14", ok("14", 3, "1999"))
        assertEquals("--03-14", ok("14", 3, ""))
    }
    @Test fun `leap day only valid in leap years or without a year`() {
        assertEquals("2000-02-29", ok("29", 2, "2000"))
        assertEquals("--02-29", ok("29", 2, ""))
        assertTrue(BirthdayInput.validate("29", 2, "1999", today) is BirthdayInput.Result.Invalid)
    }
    @Test fun `rejects impossible and future dates`() {
        assertTrue(BirthdayInput.validate("31", 4, "1999", today) is BirthdayInput.Result.Invalid)
        assertTrue(BirthdayInput.validate("1", 1, "2030", today) is BirthdayInput.Result.Invalid)
        assertTrue(BirthdayInput.validate("", 1, "", today) is BirthdayInput.Result.Invalid)
        assertTrue(BirthdayInput.validate("5", 0, "", today) is BirthdayInput.Result.Invalid)
    }
}
