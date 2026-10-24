package com.risaina.app.core.data

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.database.MemoryDao
import com.risaina.app.core.model.Memory
import com.risaina.app.core.network.CreateMemoryRequest
import com.risaina.app.core.network.PatchMemoryRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val api: RisainaApi,
    private val memoryDao: MemoryDao,
) {
    val all: Flow<List<Memory>> = memoryDao.all().map { l -> l.map { it.toDomain() } }
    val favourite: Flow<Memory?> = memoryDao.favourite().map { it?.toDomain() }
    fun observe(id: String): Flow<Memory?> = memoryDao.observe(id).map { it?.toDomain() }

    suspend fun create(mediaId: String, caption: String?, takenAt: Instant?): AppResult<Memory> =
        apiCall { api.createMemory(CreateMemoryRequest(mediaId, caption?.trim()?.takeIf { it.isNotEmpty() }, takenAt?.toString())) }
            .onSuccess { memoryDao.upsert(it.memory.toEntity()) }
            .map { it.memory.toEntity().toDomain() }

    suspend fun update(id: String, caption: String? = null, pinned: Boolean? = null): AppResult<Memory> =
        apiCall { api.patchMemory(id, PatchMemoryRequest(caption, pinned)) }
            .onSuccess { memoryDao.upsert(it.memory.toEntity()) }
            .map { it.memory.toEntity().toDomain() }

    suspend fun delete(id: String): AppResult<Unit> =
        apiCall { api.deleteMemory(id) }.onSuccess { memoryDao.markDeleted(id, Instant.now().toEpochMilli()) }

    suspend fun refresh(): AppResult<Unit> =
        apiCall { api.memories(limit = 60) }.onSuccess { page -> memoryDao.upsertAll(page.items.map { it.toEntity() }) }.map { }
}
