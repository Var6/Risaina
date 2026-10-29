package com.risaina.app.core.notifications

import com.risaina.app.testutil.calendarEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class CalendarNoticeTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val london = ZoneId.of("Europe/London")

    @Test fun `the title names the partner by first name, and the entry only with previews on`() {
        assertEquals("Bea added “Dinner” to your calendar", CalendarNotice.title("Bea Rao", "Dinner", updated = false, preview = true))
        assertEquals("Bea changed “Dinner” in your calendar", CalendarNotice.title("Bea", "Dinner", updated = true, preview = true))
        assertEquals("Bea added something to your calendar", CalendarNotice.title("Bea", "Dinner", updated = true, preview = false))
        assertEquals("Your partner added “Dinner” to your calendar", CalendarNotice.title("  ", "Dinner", updated = false, preview = true))
    }

    @Test fun `the line is this phone's day and time, with the partner's clock when it differs`() {
        val dinner = calendarEntry(date = LocalDate.of(2026, 11, 14), time = LocalTime.of(20, 0), timeZone = "Asia/Kolkata")
        assertEquals("Sat 14 Nov, 8:00 pm (2:30 pm for them)", CalendarNotice.line(dinner, kolkata, london, Locale.UK))
        assertEquals("Sat 14 Nov, 8:00 pm", CalendarNotice.line(dinner, kolkata, null, Locale.UK))
        // An all-day entry floats: the same date on both phones, no clock.
        assertEquals("Sat 14 Nov", CalendarNotice.line(calendarEntry(date = LocalDate.of(2026, 11, 14)), kolkata, london, Locale.UK))
    }
}
