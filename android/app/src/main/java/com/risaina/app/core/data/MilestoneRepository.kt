package com.risaina.app.core.data

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.database.MilestoneDao
import com.risaina.app.core.model.Milestone
import com.risaina.app.core.model.MilestoneKind
import com.risaina.app.core.network.CreateMilestoneRequest
import com.risaina.app.core.network.PatchMilestoneRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MilestoneRepository @Inject constructor(
    private val api: RisainaApi,
    private val dao: MilestoneDao,
) {
    val all: Flow<List<Milestone>> = dao.all().map { l -> l.map { it.toDomain() } }

    suspend fun create(title: String, date: LocalDate, kind: MilestoneKind): AppResult<Milestone> =
        apiCall { api.createMilestone(CreateMilestoneRequest(title.trim(), date.toString(), kind.wire)) }
            .onSuccess { dao.upsert(it.milestone.toEntity()) }.map { it.milestone.toEntity().toDomain() }

    suspend fun update(id: String, title: String?, date: LocalDate?, kind: MilestoneKind?): AppResult<Milestone> =
        apiCall { api.patchMilestone(id, PatchMilestoneRequest(title?.trim(), date?.toString(), kind?.wire)) }
            .onSuccess { dao.upsert(it.milestone.toEntity()) }.map { it.milestone.toEntity().toDomain() }

    suspend fun delete(id: String): AppResult<Unit> = apiCall { api.deleteMilestone(id) }.onSuccess { dao.delete(id) }

    suspend fun refresh(): AppResult<Unit> = apiCall { api.milestones() }.onSuccess { r -> dao.upsertAll(r.items.map { it.toEntity() }) }.map { }
}
