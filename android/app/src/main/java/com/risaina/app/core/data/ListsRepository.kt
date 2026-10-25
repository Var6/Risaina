package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.database.ListDao
import com.risaina.app.core.database.ListEntity
import com.risaina.app.core.model.ListItem
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.SharedList
import com.risaina.app.core.network.CreateListRequest
import com.risaina.app.core.network.ListItemDto
import com.risaina.app.core.network.PatchListRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SharedListDto
import com.risaina.app.core.network.apiCall
import com.risaina.app.core.network.toAppError
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

fun SharedListDto.toEntity(json: Json) = ListEntity(
    id = id, title = title, kind = kind, body = body,
    itemsJson = json.encodeToString(ListSerializer(ListItemDto.serializer()), items),
    pinned = pinned, version = version, createdBy = createdBy, updatedBy = updatedBy,
    createdAt = Instant.parse(createdAt).toEpochMilli(), updatedAt = Instant.parse(updatedAt).toEpochMilli(),
)

/** Shared notes and checklists. Both partners edit; the server's version number detects clashes. */
@Singleton
class ListsRepository @Inject constructor(
    private val api: RisainaApi,
    private val dao: ListDao,
    private val json: Json,
) {
    private fun ListEntity.toDomain(): SharedList {
        val items = runCatching { json.decodeFromString(ListSerializer(ListItemDto.serializer()), itemsJson) }.getOrDefault(emptyList())
        return SharedList(
            id = id, title = title, kind = ListKind.fromWire(kind), body = body,
            items = items.map { ListItem(it.id, it.text, it.done, it.doneBy, it.doneAt?.let(Instant::parse)) },
            pinned = pinned, version = version, createdBy = createdBy, updatedBy = updatedBy, updatedAt = Instant.ofEpochMilli(updatedAt),
        )
    }

    val all: Flow<List<SharedList>> = dao.all().map { l -> l.map { it.toDomain() } }
    fun observe(id: String): Flow<SharedList?> = dao.observe(id).map { it?.toDomain() }

    suspend fun refresh(): AppResult<Unit> =
        apiCall { api.lists() }.onSuccess { r -> dao.replaceAll(r.items.map { it.toEntity(json) }) }.map { }

    suspend fun create(title: String, kind: ListKind, body: String = "", items: List<String> = emptyList()): AppResult<SharedList> =
        apiCall { api.createList(CreateListRequest(title.trim(), kind.wire, body, items.map { ListItemDto(text = it) })) }
            .onSuccess { dao.upsert(it.list.toEntity(json)) }
            .map { it.list.toEntity(json).toDomain() }

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
            dao.upsert(r.list.toEntity(json))
            AppResult.Success(r.list.toEntity(json).toDomain())
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
}
