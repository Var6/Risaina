package com.risaina.app.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTilesTest {
    @Test
    fun `home has no widget tiles, they live in Settings`() {
        listOf(true, false).forEach { calendar ->
            val labels = HomeTiles.of(calendar).map { it.label }
            assertFalse(labels.contains("Widgets"))
            assertFalse(labels.contains("Their widget"))
        }
    }

    @Test
    fun `six tiles in two even rows of three`() {
        listOf(true, false).forEach { calendar ->
            val rows = HomeTiles.rows(calendar)
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.size == HomeTiles.PER_ROW })
        }
    }

    @Test
    fun `calendar tile when the server has it, Timeline on an older server`() {
        assertEquals(
            listOf(HomeTile.MESSAGES, HomeTile.LISTS, HomeTile.MEMORIES, HomeTile.CALENDAR, HomeTile.GIFTS, HomeTile.WAKE),
            HomeTiles.of(calendar = true),
        )
        assertEquals(
            listOf(HomeTile.MESSAGES, HomeTile.LISTS, HomeTile.MEMORIES, HomeTile.TIMELINE, HomeTile.GIFTS, HomeTile.WAKE),
            HomeTiles.of(calendar = false),
        )
    }

    @Test
    fun `every tile appears once`() {
        listOf(true, false).forEach { calendar ->
            val tiles = HomeTiles.of(calendar)
            assertEquals(tiles.size, tiles.toSet().size)
        }
    }
}
