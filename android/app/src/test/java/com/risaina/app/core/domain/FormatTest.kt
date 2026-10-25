package com.risaina.app.core.domain

import com.risaina.app.feature.common.Format
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class FormatTest {
    @Test fun `greeting punctuation reads naturally`() {
        assertEquals("Good morning, Asha", Format.greetingFor("Asha", LocalTime.of(8, 0)))
        assertEquals("Good evening, Asha", Format.greetingFor("Asha", LocalTime.of(19, 0)))
        assertEquals("Still up, Asha?", Format.greetingFor("Asha", LocalTime.of(1, 30)))
        assertEquals("Still up, you?", Format.greetingFor("", LocalTime.of(23, 0)))
    }

    @Test fun `durations format as minutes and seconds`() {
        assertEquals("0:00", Format.duration(0))
        assertEquals("1:05", Format.duration(65_000))
    }
}
