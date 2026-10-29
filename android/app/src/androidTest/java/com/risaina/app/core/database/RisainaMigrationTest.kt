package com.risaina.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrates a real version-5 database file to 6 on a device (the Room Gradle plugin copies
 * app/schemas into this test's assets). Rows written at 5 must still be there: the outbox holds
 * unsent messages. The JVM side of this is Migration5To6Test.
 */
@RunWith(AndroidJUnit4::class)
class RisainaMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RisainaDatabase::class.java)

    @Test
    fun migrate5To6KeepsRowsAndAddsTheNewColumnsAndTables() {
        helper.createDatabase(DB, 5).apply {
            execSQL(
                "INSERT INTO lists (id, title, kind, body, itemsJson, pinned, version, createdBy, updatedBy, createdAt, updatedAt) " +
                    "VALUES ('l1', 'Trip', 'checklist', '', '[]', 1, 3, 'me', NULL, 1, 2)",
            )
            execSQL("INSERT INTO outbox (localKey, kind, payloadJson, attempts, lastError, createdAt) VALUES ('k1', 'message', '{}', 0, NULL, 5)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 6, true)

        db.query("SELECT title, version, marker, checkboxes, syncedAt FROM lists WHERE id = 'l1'").use { c ->
            c.moveToFirst()
            assertEquals("Trip", c.getString(0))
            assertEquals(3, c.getInt(1))
            assertEquals("none", c.getString(2))
            assertEquals(1, c.getInt(3))
            assertEquals(0L, c.getLong(4))
        }
        db.query("SELECT COUNT(*) FROM outbox").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        for (table in listOf("list_drafts", "calendar_entries", "reminder_log")) {
            db.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
        db.close()
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
