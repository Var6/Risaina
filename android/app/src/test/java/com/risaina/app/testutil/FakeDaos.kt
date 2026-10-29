package com.risaina.app.testutil

import com.risaina.app.core.database.CalendarDao
import com.risaina.app.core.database.CalendarEntryEntity
import com.risaina.app.core.database.ListDao
import com.risaina.app.core.database.ListDraftDao
import com.risaina.app.core.database.ListDraftEntity
import com.risaina.app.core.database.ListEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory DAOs for JVM tests. Only the queries are faked: the DAOs' own default methods
 * (upsertIfNewer, syncFromServer) run as written, so tests exercise the real rules.
 */
class FakeListDao : ListDao {
    val rows = MutableStateFlow<Map<String, ListEntity>>(emptyMap())

    override fun all(): Flow<List<ListEntity>> = rows.map { m -> m.values.sortedWith(compareByDescending<ListEntity> { it.pinned }.thenByDescending { it.updatedAt }) }
    override fun observe(id: String): Flow<ListEntity?> = rows.map { it[id] }
    override suspend fun byId(id: String): ListEntity? = rows.value[id]
    override suspend fun upsert(e: ListEntity) { rows.value = rows.value + (e.id to e) }
    override suspend fun versionOf(id: String): Int? = rows.value[id]?.version
    override suspend fun idsSyncedBefore(before: Long): List<String> = rows.value.values.filter { it.syncedAt < before }.map { it.id }
    override suspend fun delete(id: String) { rows.value = rows.value - id }
    override suspend fun clear() { rows.value = emptyMap() }
}

class FakeListDraftDao : ListDraftDao {
    val rows = MutableStateFlow<Map<String, ListDraftEntity>>(emptyMap())

    override suspend fun get(listId: String): ListDraftEntity? = rows.value[listId]
    override fun observe(listId: String): Flow<ListDraftEntity?> = rows.map { it[listId] }
    override suspend fun all(): List<ListDraftEntity> = rows.value.values.sortedBy { it.editedAt }
    override fun observeAll(): Flow<List<ListDraftEntity>> = rows.map { m -> m.values.sortedBy { it.editedAt } }
    override suspend fun upsert(e: ListDraftEntity) { rows.value = rows.value + (e.listId to e) }
    override suspend fun delete(listId: String) { rows.value = rows.value - listId }
    override suspend fun clear() { rows.value = emptyMap() }
}

class FakeCalendarDao : CalendarDao {
    val rows = MutableStateFlow<Map<String, CalendarEntryEntity>>(emptyMap())

    private fun sorted(l: Collection<CalendarEntryEntity>) = l.sortedWith(compareBy<CalendarEntryEntity> { it.date }.thenBy { it.time ?: "" })
    override fun all(): Flow<List<CalendarEntryEntity>> = rows.map { sorted(it.values) }
    override suspend fun byId(id: String): CalendarEntryEntity? = rows.value[id]
    override fun observe(id: String): Flow<CalendarEntryEntity?> = rows.map { it[id] }
    override fun forMessages(messageIds: List<String>): Flow<List<CalendarEntryEntity>> = rows.map { m -> sorted(m.values.filter { it.sourceMessageId in messageIds }) }
    override fun forList(listId: String): Flow<List<CalendarEntryEntity>> = rows.map { m -> sorted(m.values.filter { it.sourceListId == listId }) }
    override suspend fun versionOf(id: String): Int? = rows.value[id]?.version
    override suspend fun idsSyncedBefore(before: Long): List<String> = rows.value.values.filter { it.syncedAt < before }.map { it.id }
    override suspend fun upsert(e: CalendarEntryEntity) { rows.value = rows.value + (e.id to e) }
    override suspend fun markDone(id: String, done: Boolean, doneAt: Long?, doneBy: String?) {
        rows.value[id]?.let { rows.value = rows.value + (id to it.copy(done = done, doneAt = doneAt, doneBy = doneBy)) }
    }
    override suspend fun delete(id: String) { rows.value = rows.value - id }
    override suspend fun clear() { rows.value = emptyMap() }
}
