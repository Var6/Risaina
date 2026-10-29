package com.risaina.app.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room 5 → 6 (D2). If this migration were missing or wrong, Room would fall back to wiping the
 * database, and with it the outbox of unsent messages and the unsent list drafts.
 *
 * Why this is not a MigrationTestHelper test: on Android, room-testing's helper needs an
 * Instrumentation and the platform's SQLite, which JVM unit tests do not have (there is no
 * Robolectric here, and a bundled SQLite driver would be a new dependency). That full test, which
 * really migrates a version-5 file with rows in it, is androidTest/.../RisainaMigrationTest. Here
 * the generated migration runs against a recording connection, and the exported schemas are
 * compared: version 6 must be version 5 plus exactly the D2 additions.
 */
class Migration5To6Test {
    private val schemas = File("schemas/com.risaina.app.core.database.RisainaDatabase")

    private fun tables(version: Int): Map<String, JsonObject> =
        Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .associate { it.jsonObject["tableName"]!!.jsonPrimitive.content to it.jsonObject }

    private fun JsonObject.columns(): Map<String, JsonObject> =
        this["fields"]!!.jsonArray.associate { it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject }

    @Test fun `the generated migration adds the list columns with defaults and creates the three tables`() {
        val sql = mutableListOf<String>()
        val connection = mockk<SQLiteConnection>()
        every { connection.prepare(any()) } answers { sql += firstArg<String>(); mockk<SQLiteStatement>(relaxed = true) }

        RisainaDatabase_AutoMigration_5_6_Impl().migrate(connection)

        assertTrue(sql.contains("ALTER TABLE `lists` ADD COLUMN `marker` TEXT NOT NULL DEFAULT 'none'"))
        assertTrue(sql.contains("ALTER TABLE `lists` ADD COLUMN `checkboxes` INTEGER NOT NULL DEFAULT 1"))
        assertTrue(sql.contains("ALTER TABLE `lists` ADD COLUMN `syncedAt` INTEGER NOT NULL DEFAULT 0"))
        for (table in listOf("list_drafts", "calendar_entries", "reminder_log")) {
            assertTrue("creates $table", sql.any { it.startsWith("CREATE TABLE IF NOT EXISTS `$table`") })
        }
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS `index_calendar_entries_sourceMessageId` ON `calendar_entries` (`sourceMessageId`)"))
        // Nothing is dropped, renamed or copied: no table is rebuilt, so no row can be lost.
        assertTrue(sql.none { it.startsWith("DROP") || it.contains("RENAME") || it.startsWith("INSERT") })
    }

    @Test fun `schema 6 is schema 5 plus the D2 additions and nothing else`() {
        val v5 = tables(5)
        val v6 = tables(6)
        assertEquals(v5.keys + setOf("list_drafts", "calendar_entries", "reminder_log"), v6.keys)
        for ((name, table) in v5) {
            val before = table.columns()
            val after = v6.getValue(name).columns()
            val added = if (name == "lists") setOf("marker", "checkboxes", "syncedAt") else emptySet()
            assertEquals("columns of $name", before.keys + added, after.keys)
            before.forEach { (col, field) -> assertEquals("$name.$col unchanged", field, after.getValue(col)) }
        }
        val lists = v6.getValue("lists").columns()
        assertEquals("'none'", lists.getValue("marker")["defaultValue"]!!.jsonPrimitive.content)
        assertEquals("1", lists.getValue("checkboxes")["defaultValue"]!!.jsonPrimitive.content)
        assertEquals("0", lists.getValue("syncedAt")["defaultValue"]!!.jsonPrimitive.content)
    }

    @Test fun `the new tables have the columns the feature packages rely on`() {
        val v6 = tables(6)
        assertEquals(
            setOf("listId", "baseVersion", "baseJson", "mineJson", "editedAt", "state", "lastError", "attempts"),
            v6.getValue("list_drafts").columns().keys,
        )
        assertEquals(setOf("key", "firedAt"), v6.getValue("reminder_log").columns().keys)
        val calendar = v6.getValue("calendar_entries").columns().keys
        for (col in listOf("id", "kind", "date", "time", "timeZone", "repeat", "reminders", "remindUserIds", "done", "sourceMessageId", "sourceListId", "version")) {
            assertTrue("calendar_entries.$col", col in calendar)
        }
    }
}
