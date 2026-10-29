package com.risaina.app.feature.delivery

import org.junit.Assert.assertEquals
import org.junit.Test

/** Delivery health: how many phones get this account's pushes, and which "Remove the others" removes. */
class PhonesLineTest {
    @Test fun oneOrManyPhones() {
        assertEquals("1 phone is registered on your account.", phonesLine(1))
        assertEquals("3 phones are registered on your account.", phonesLine(3))
    }

    @Test fun removesEveryPhoneButThisOne() {
        assertEquals(listOf("old1", "old2"), otherPhones(listOf("old1", "me", "old2"), "me"))
        assertEquals(emptyList<String>(), otherPhones(listOf("me"), "me"))
    }

    @Test fun neverRemovesAnythingWhenThisPhoneIsNotInTheList() {
        // Not registered yet, or under an id the server no longer has: removing "the others" would
        // leave the account with no phone at all.
        assertEquals(emptyList<String>(), otherPhones(listOf("a", "b"), "me"))
        assertEquals(emptyList<String>(), otherPhones(listOf("a", "b"), null))
        assertEquals(emptyList<String>(), otherPhones(listOf("a", "b"), ""))
    }

    @Test fun skipsRowsWithoutAnId() {
        assertEquals(listOf("old"), otherPhones(listOf(null, "", "old", "me", "old"), "me"))
    }
}
