package com.risaina.app.core.domain

import com.risaina.app.core.model.Couple
import com.risaina.app.core.model.CoupleStatus
import com.risaina.app.core.model.Milestone
import com.risaina.app.core.model.MilestoneKind
import com.risaina.app.core.model.Partner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The occasions worked out from the couple, both profiles and the milestones (calendar.md §5). */
class CalendarOccasionsTest {
    private val today = LocalDate.of(2026, 10, 29)

    private fun couple(start: LocalDate? = LocalDate.of(2023, 11, 14), meeting: LocalDate? = null, label: String? = null) =
        Couple("c", CoupleStatus.ACTIVE, listOf("me", "bea"), start, meeting, label, Instant.EPOCH)

    private fun partner(birthday: String?) = Partner("bea", "Bea", null, birthday = birthday)
    private fun milestone(id: String, date: LocalDate, kind: MilestoneKind, title: String = "First trip") = Milestone(id, "c", "me", title, date, kind, Instant.EPOCH)

    @Test fun `the anniversary comes from the start date, counted`() {
        val o = CalendarOccasions.derive(couple(), "me", null, null, emptyList()).single()
        assertEquals("occ:anniversary", o.key)
        val next = CalendarOccasions.next(o, today)!!
        assertEquals(LocalDate.of(2026, 11, 14), next.date)
        assertEquals("Our 3rd anniversary", next.title)
        assertEquals(3, next.years)
    }

    @Test fun `the partner's birthday says the age only when the year is known`() {
        val withYear = CalendarOccasions.derive(null, "me", null, partner("1999-03-05"), emptyList()).single()
        assertEquals("occ:birthday:bea", withYear.key)
        assertEquals("Bea's birthday · turning 28", CalendarOccasions.next(withYear, today)!!.title)
        val noYear = CalendarOccasions.derive(null, "me", null, partner("--03-05"), emptyList()).single()
        assertEquals("Bea's birthday", CalendarOccasions.next(noYear, today)!!.title)
    }

    @Test fun `a 29 February birthday is the 28th in other years and back on the 29th in leap years`() {
        val o = CalendarOccasions.derive(null, "me", null, partner("--02-29"), emptyList()).single()
        assertEquals(LocalDate.of(2027, 2, 28), CalendarOccasions.next(o, today)!!.date)
        assertEquals(LocalDate.of(2028, 2, 29), CalendarOccasions.next(o, LocalDate.of(2027, 3, 1))!!.date)
        assertEquals(LocalDate.of(2028, 2, 29), CalendarOccasions.on(o, LocalDate.of(2028, 2, 29))!!.date)
        assertNull(CalendarOccasions.on(o, LocalDate.of(2028, 2, 28)))
    }

    @Test fun `my birthday and the next visit`() {
        val all = CalendarOccasions.derive(couple(start = null, meeting = LocalDate.of(2026, 12, 20), label = "Goa, finally"), "me", "1998-07-01", null, emptyList())
        assertEquals(listOf("occ:birthday:me", "occ:meeting"), all.map { it.key })
        assertEquals("Your birthday", CalendarOccasions.next(all[0], today)!!.title)
        assertEquals("Goa, finally", CalendarOccasions.next(all[1], today)!!.title)
        // A visit that has happened is no longer coming up.
        assertNull(CalendarOccasions.next(all[1], LocalDate.of(2026, 12, 21)))
        val unnamed = CalendarOccasions.derive(couple(start = null, meeting = today), "me", null, null, emptyList()).single()
        assertEquals("Next time together", unnamed.label)
    }

    @Test fun `an anniversary milestone on the start date is not shown twice`() {
        val start = LocalDate.of(2023, 11, 14)
        val all = CalendarOccasions.derive(
            couple(start), "me", null, null,
            listOf(milestone("m1", start, MilestoneKind.ANNIVERSARY, "Us"), milestone("m2", LocalDate.of(2024, 6, 2), MilestoneKind.ANNIVERSARY, "First kiss")),
        )
        assertEquals(listOf("occ:anniversary", "occ:milestone:m2"), all.map { it.key })
        assertEquals("First kiss · 2 years", CalendarOccasions.next(all[1], LocalDate.of(2026, 1, 1))!!.title)
    }

    @Test fun `one-off milestones come up once, yearly ones every year`() {
        val all = CalendarOccasions.derive(
            null, "me", null, null,
            listOf(milestone("trip", LocalDate.of(2026, 12, 1), MilestoneKind.CUSTOM), milestone("old", LocalDate.of(2025, 1, 1), MilestoneKind.MEETING)),
        )
        assertEquals(LocalDate.of(2026, 12, 1), CalendarOccasions.next(all[0], today)!!.date)
        assertNull(CalendarOccasions.next(all[1], today))
    }

    @Test fun `between lists every day in the window across years`() {
        val all = CalendarOccasions.derive(couple(), "me", null, partner("--01-02"), emptyList())
        val days = CalendarOccasions.between(all, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 12, 31))
        assertEquals(
            listOf(LocalDate.of(2026, 11, 14), LocalDate.of(2027, 1, 2), LocalDate.of(2027, 11, 14)),
            days.map { it.date },
        )
        assertEquals("Our 4th anniversary", days.last().title)
    }

    @Test fun `a yearly date is not shown before the year it began`() {
        val o = CalendarOccasions.derive(couple(start = LocalDate.of(2027, 5, 1)), "me", null, null, emptyList()).single()
        assertNull(CalendarOccasions.on(o, LocalDate.of(2026, 5, 1)))
        assertEquals(LocalDate.of(2027, 5, 1), CalendarOccasions.next(o, today)!!.date)
        assertEquals("The day it began", CalendarOccasions.next(o, today)!!.title)
    }

    @Test fun `Home's birthday chips count the days`() {
        val all = CalendarOccasions.derive(null, "me", "2000-10-29", partner("1999-11-08"), emptyList())
        val partner = CalendarOccasions.upcomingBirthday(all, OccasionKind.PARTNER_BIRTHDAY, today)!!
        assertEquals(10L, partner.daysLeft)
        assertEquals(27, partner.turning)
        val mine = CalendarOccasions.upcomingBirthday(all, OccasionKind.MY_BIRTHDAY, today)!!
        assertEquals(0L, mine.daysLeft)
        assertNull(CalendarOccasions.upcomingBirthday(emptyList(), OccasionKind.MY_BIRTHDAY, today))
    }

    @Test fun `the same dates as the timeline's old coming-up list`() {
        // Regression: the dates Timeline listed before CalendarOccasions, for the same couple.
        val all = CalendarOccasions.derive(
            couple(meeting = LocalDate.of(2026, 11, 20)), "me", "--04-10", partner("1999-03-05"),
            listOf(milestone("m", LocalDate.of(2026, 12, 24), MilestoneKind.CUSTOM, "Christmas Eve")),
        )
        val upcoming = all.mapNotNull { CalendarOccasions.next(it, today) }.sortedBy { it.date }.map { it.date }
        assertEquals(
            listOf(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 24), LocalDate.of(2027, 3, 5), LocalDate.of(2027, 4, 10)),
            upcoming,
        )
        assertTrue(all.all { it.key.startsWith("occ:") })
    }
}
