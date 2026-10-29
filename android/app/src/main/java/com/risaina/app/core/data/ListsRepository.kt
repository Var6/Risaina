package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.database.ListDao
import com.risaina.app.core.database.ListEntity
import com.risaina.app.core.model.ListItem
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.ListLimits
import com.risaina.app.core.model.ListMarker
import com.risaina.app.core.model.SharedList
import com.risaina.app.core.network.ConvertListRequest
import com.risaina.app.core.network.CreateListRequest
import com.risaina.app.core.network.ListItemDto
import com.risaina.app.core.network.PatchListRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SharedListDto
import com.risaina.app.core.network.apiCall
import com.risaina.app.core.network.list
import com.risaina.app.core.network.toAppError
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** [syncedAt]: this phone's clock now; ListDao.syncFromServer compares it with when its GET started. */
fun SharedListDto.toEntity(json: Json, syncedAt: Long = System.currentTimeMillis()) = ListEntity(
    id = id, title = title, kind = kind, body = body,
    itemsJson = json.encodeToString(ListSerializer(ListItemDto.serializer()), items),
    pinned = pinned, version = version, createdBy = createdBy, updatedBy = updatedBy,
    createdAt = createdAt.toInstantOrNull()?.toEpochMilli() ?: 0L, updatedAt = updatedAt.toInstantOrNull()?.toEpochMilli() ?: 0L,
    marker = marker, checkboxes = checkboxes, syncedAt = syncedAt,
)

fun ListItemDto.toDomain() = ListItem(id, text, done, doneBy, doneAt.toInstantOrNull(), addedBy, addedAt.toInstantOrNull())

fun ListEntity.toDomain(json: Json): SharedList {
    val items = runCatching { json.decodeFromString(ListSerializer(ListItemDto.serializer()), itemsJson) }.getOrDefault(emptyList())
    return SharedList(
        id = id, title = title, kind = ListKind.fromWire(kind), body = body, items = items.map { it.toDomain() },
        pinned = pinned, version = version, createdBy = createdBy, updatedBy = updatedBy, updatedAt = Instant.ofEpochMilli(updatedAt),
        marker = ListMarker.fromWire(marker), checkboxes = checkboxes,
    )
}

/**
 * Shared notes and checklists. Both partners edit; the server's version number detects clashes.
 * Every copy from the server goes into Room through ListDao.upsertIfNewer, so an older copy (a slow
 * reply, a replayed event) never replaces a newer one.
 */
@Singleton
class ListsRepository @Inject constructor(
    private val api: RisainaApi,
    private val dao: ListDao,
    private val json: Json,
    private val time: TimeProvider,
) {
    val all: Flow<List<SharedList>> = dao.all().map { l -> l.map { it.toDomain(json) } }
    fun observe(id: String): Flow<SharedList?> = dao.observe(id).map { it?.toDomain(json) }

    private fun SharedListDto.entity() = toEntity(json, syncedAt = time.now().toEpochMilli())

    suspend fun refresh(): AppResult<Unit> {
        val requestedAt = time.now().toEpochMilli()
        return apiCall { api.lists() }.onSuccess { r -> dao.syncFromServer(r.items.map { it.entity() }, requestedAt) }.map { }
    }

    /**
     * [clientKey] makes a retry return the list the first try created instead of a second one; the
     * server before the format round refuses it (and [marker], [checkboxes]) as unknown keys.
     */
    suspend fun create(
        title: String, kind: ListKind, body: String = "", items: List<String> = emptyList(),
        marker: ListMarker? = null, checkboxes: Boolean? = null, pinned: Boolean? = null, clientKey: String? = null,
    ): AppResult<SharedList> =
        apiCall {
            api.createList(
                CreateListRequest(
                    title.trim(), kind.wire, body, items.map { ListItemDto(text = it) }, pinned,
                    marker = marker?.takeIf { it != ListMarker.UNKNOWN }?.wire, checkboxes = checkboxes, clientKey = clientKey,
                ),
            )
        }
            .onSuccess { dao.upsertIfNewer(it.list.entity()) }
            .map { it.list.entity().toDomain(json) }

    /**
     * Saves the whole list. On a version clash the server's copy wins locally and the caller is told,
     * so the user re-applies their change on top of their partner's instead of silently overwriting it.
     */
    suspend fun save(list: SharedList, title: String = list.title, body: String = list.body, items: List<ListItem> = list.items, pinned: Boolean = list.pinned): AppResult<SharedList> {
        val req = PatchListRequest(
            title = title.trim(), body = body, pinned = pinned, expectedVersion = list.version,
            items = items.map { ListItemDto(id = it.id, text = it.text.trim(), done = it.done) },
        )
        return try {
            val r = api.patchList(list.id, req)
            dao.upsertIfNewer(r.list.entity())
            AppResult.Success(r.list.entity().toDomain(json))
        } catch (e: ClientRequestException) {
            if (e.response.status.value == 409) { refresh(); AppResult.Failure(AppError.Conflict("version_conflict", "Your partner just changed this. Showing their latest version.")) }
            else AppResult.Failure(e.toAppError())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppResult.Failure(e.toAppError())
        }
    }

    suspend fun delete(id: String): AppResult<Unit> = apiCall { api.deleteList(id) }.onSuccess { dao.delete(id) }

    /**
     * One list from the server (opening it from a link, or checking it after a conflict). Returns the
     * newest copy this phone has. A 404 leaves Room alone: it can also mean a server older than the route.
     */
    suspend fun getById(id: String): AppResult<SharedList> =
        apiCall { api.list(id) }.map { r -> dao.upsertIfNewer(r.list.entity()); (dao.byId(id) ?: r.list.entity()).toDomain(json) }

    /**
     * Turns a note into a checklist (its lines become items) or a checklist into a note, on the server
     * so both phones see the same result. A stale [expectedVersion] gives [AppError.VersionConflict]
     * once the server's current copy is in Room.
     */
    suspend fun convert(id: String, to: ListKind, marker: ListMarker? = null, checkboxes: Boolean? = null, expectedVersion: Int? = null): AppResult<SharedList> {
        val req = ConvertListRequest(to.wire, marker?.takeIf { it != ListMarker.UNKNOWN }?.wire, checkboxes, expectedVersion)
        return when (val r = apiCall { api.convertList(id, req) }) {
            is AppResult.Success -> { dao.upsertIfNewer(r.value.list.entity()); AppResult.Success(r.value.list.entity().toDomain(json)) }
            is AppResult.Failure -> { (r.error as? AppError.VersionConflict)?.list(json)?.let { dao.upsertIfNewer(it.entity()) }; r }
        }
    }

    /**
     * Adds [texts] as unticked items at the end of a checklist (to-dos from the chat). Returns the new
     * items' ids. A partner's save in between (409) is taken from the error, or re-read, and the
     * append tried again on top of it, up to [APPEND_RETRIES] times.
     */
    suspend fun appendItems(listId: String, texts: List<String>): AppResult<List<String>> {
        val added = TodoAppend.newItems(texts)
        if (added.isEmpty()) return AppResult.Success(emptyList())
        var current = dao.byId(listId)?.toDomain(json) ?: when (val r = getById(listId)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return AppResult.Failure(r.error.forAppend())
        }
        repeat(APPEND_RETRIES + 1) {
            if (current.kind != ListKind.CHECKLIST) return AppResult.Failure(AppError.Validation("That's a note. Pick a list instead"))
            val items = TodoAppend.merge(current.items, added) ?: return AppResult.Failure(AppError.Validation("That list is full"))
            val req = PatchListRequest(items = items.map { ListItemDto(id = it.id, text = it.text, done = it.done) }, expectedVersion = current.version)
            when (val r = apiCall { api.patchList(listId, req) }) {
                is AppResult.Success -> {
                    dao.upsertIfNewer(r.value.list.entity())
                    return AppResult.Success(added.mapNotNull { it.id })
                }
                is AppResult.Failure -> {
                    val e = r.error as? AppError.VersionConflict ?: return AppResult.Failure(r.error.forAppend())
                    current = e.list(json)?.let { theirs -> dao.upsertIfNewer(theirs.entity()); theirs.entity().toDomain(json) }
                        ?: getById(listId).getOrNull() ?: return AppResult.Failure(e)
                }
            }
        }
        return AppResult.Failure(AppError.VersionConflict("Your partner is busy with this list. Try again in a moment."))
    }

    /**
     * The pinned shared "To-dos" checklist that undated to-dos from the chat go into: the one this
     * phone or the server already has, or a new one. Looking on the server first keeps both phones on
     * the same list.
     */
    suspend fun ensureTodoList(): AppResult<SharedList> {
        findTodoList()?.let { return AppResult.Success(it) }
        refresh().errorOrNull()?.let { return AppResult.Failure(it) }
        findTodoList()?.let { return AppResult.Success(it) }
        return create(ListLimits.TODO_LIST_TITLE, ListKind.CHECKLIST, pinned = true, marker = ListMarker.NONE, checkboxes = true)
    }

    /** A checklist titled "To-dos" (any case); a pinned one first, then the oldest. */
    private suspend fun findTodoList(): SharedList? = dao.all().first()
        .filter { it.kind == ListKind.CHECKLIST.wire && it.title.trim().equals(ListLimits.TODO_LIST_TITLE, ignoreCase = true) }
        .sortedWith(compareByDescending<ListEntity> { it.pinned }.thenBy { it.createdAt })
        .firstOrNull()?.toDomain(json)

    private fun AppError.forAppend(): AppError = if (this is AppError.NotFound) AppError.NotFound("That list was deleted") else this

    companion object {
        /** Retries of an append after a 409; each one re-reads the partner's newer copy. */
        const val APPEND_RETRIES = 3
    }
}
