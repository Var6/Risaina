package com.risaina.app.core.data

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.database.RisainaDatabase
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.datastore.WidgetSnapshotStore
import com.risaina.app.core.model.Couple
import com.risaina.app.core.model.CoupleStatus
import com.risaina.app.core.model.InvitationCreated
import com.risaina.app.core.model.InvitationSummary
import com.risaina.app.core.model.Partner
import com.risaina.app.core.model.Profile
import com.risaina.app.core.network.MeResponse
import com.risaina.app.core.network.PatchCoupleRequest
import com.risaina.app.core.network.PatchMeRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PairingState {
    data object Loading : PairingState
    data object Unpaired : PairingState
    /** A couple exists in `pending` state; we are waiting for the partner to accept. */
    data class Waiting(val invitation: InvitationSummary?) : PairingState
    data class Paired(val couple: Couple, val partner: Partner) : PairingState
    data object Ended : PairingState
}

@Singleton
class CoupleRepository @Inject constructor(
    private val api: RisainaApi,
    private val coupleDao: CoupleDao,
    private val sessionStore: SessionStore,
    private val preferencesStore: PreferencesStore,
    private val widgetSnapshotStore: WidgetSnapshotStore,
    private val db: RisainaDatabase,
) {
    private val invitation = MutableStateFlow<InvitationSummary?>(null)
    private val loaded = MutableStateFlow(false)

    val profile: Flow<Profile?> = sessionStore.profile

    val pairingState: Flow<PairingState> = combine(
        coupleDao.observeCouple(), coupleDao.observePartner(), invitation, loaded,
    ) { couple, partner, inv, isLoaded ->
        when {
            !isLoaded && couple == null -> PairingState.Loading
            couple == null -> PairingState.Unpaired
            couple.status.equals("active", true) && partner != null -> PairingState.Paired(couple.toDomain(), partner.toDomain())
            couple.status.equals("ended", true) -> PairingState.Ended
            else -> PairingState.Waiting(inv)
        }
    }

    val couple: Flow<Couple?> = combine(coupleDao.observeCouple(), loaded) { c, _ -> c?.toDomain() }
    val partner: Flow<Partner?> = combine(coupleDao.observePartner(), loaded) { p, _ -> p?.toDomain() }

    /** Pull `/me`; the single place where server truth about the relationship enters local storage. */
    suspend fun refreshMe(): AppResult<MeResponse> {
        val res = apiCall { api.me() }
        if (res is AppResult.Success) {
            val me = res.value
            sessionStore.saveProfile(me.user.displayName, me.user.avatarMediaId, me.user.emailVerified)
            sessionStore.saveExtras(me.user.birthday, me.user.hasPassword, me.user.timeZone)
            syncTimeZone(me.user.timeZone, me.user.shareLocalTime)
            coupleDao.replace(me.couple?.toEntity(), me.partner?.toEntity())
            sessionStore.setActiveCoupleId(me.couple?.takeIf { it.status.equals("active", true) }?.id)
            if (sessionStore.prefsDirty.firstValue().not()) {
                preferencesStore.update { local -> me.preferences.toDomain(local) }
            }
            if (me.couple != null && !me.couple.status.equals("active", true)) refreshInvitation()
        }
        loaded.value = true
        return res
    }

    suspend fun refreshInvitation() {
        apiCall { api.currentInvitation() }.onSuccess { r ->
            invitation.value = r.invitation?.let { InvitationSummary(it.id, Instant.parse(it.expiresAt), Instant.parse(it.createdAt)) }
        }
    }

    suspend fun createInvitation(): AppResult<InvitationCreated> {
        val res = apiCall { api.createInvitation() }.map { r ->
            InvitationCreated(r.invitation.id, r.invitation.code, Instant.parse(r.invitation.expiresAt))
        }
        if (res is AppResult.Success) {
            invitation.value = InvitationSummary(res.value.id, res.value.expiresAt, Instant.now())
            refreshMe()
        }
        return res
    }

    suspend fun revokeInvitation(id: String): AppResult<Unit> {
        val res = apiCall { api.revokeInvitation(id) }
        if (res is AppResult.Success) { invitation.value = null; refreshMe() }
        return res
    }

    suspend fun acceptInvitation(code: String): AppResult<Unit> {
        val res = apiCall { api.acceptInvitation(code.trim().uppercase().replace(" ", "").replace("-", "")) }
        if (res is AppResult.Success) refreshMe()
        return res.map { }
    }

    suspend fun updateCouple(start: LocalDate?, nextMeeting: LocalDate?, label: String?): AppResult<Couple> {
        val res = apiCall {
            api.patchCouple(PatchCoupleRequest(start?.toString(), nextMeeting?.toString(), label))
        }
        if (res is AppResult.Success) {
            val partner = coupleDao.partner()
            coupleDao.replace(res.value.couple.toEntity(), partner)
        }
        return res.map { it.couple.toDomain() }
    }

    /** Keep the partner's "your local time" accurate when this phone changes zone. */
    private suspend fun syncTimeZone(serverZone: String?, share: Boolean) {
        if (!share) return
        val here = java.time.ZoneId.systemDefault().id
        if (here != serverZone) apiCall { api.patchMe(PatchMeRequest(timeZone = here)) }
    }

    suspend fun updateBirthday(birthday: String?): AppResult<Unit> {
        val body = kotlinx.serialization.json.buildJsonObject {
            if (birthday == null) put("birthday", kotlinx.serialization.json.JsonNull) else put("birthday", kotlinx.serialization.json.JsonPrimitive(birthday))
        }
        val res = apiCall { api.patchMeRaw(body) }
        if (res is AppResult.Success) sessionStore.saveExtras(res.value.user.birthday, res.value.user.hasPassword, res.value.user.timeZone)
        return res.map { }
    }

    suspend fun setShareLocalTime(share: Boolean): AppResult<Unit> =
        apiCall { api.patchMe(PatchMeRequest(shareLocalTime = share, timeZone = if (share) java.time.ZoneId.systemDefault().id else null)) }.map { }

    suspend fun updateProfile(displayName: String?, avatarMediaId: String?): AppResult<Unit> {
        val res = apiCall { api.patchMe(PatchMeRequest(displayName?.trim(), avatarMediaId)) }
        if (res is AppResult.Success) sessionStore.saveProfile(res.value.user.displayName, res.value.user.avatarMediaId, res.value.user.emailVerified)
        return res.map { }
    }

    /** Ends the relationship for both people. Shared content is removed locally; the server schedules media deletion. */
    suspend fun unlink(): AppResult<Unit> {
        val res = apiCall { api.unlink() }
        if (res is AppResult.Success) onUnlinked()
        return res
    }

    suspend fun onUnlinked() {
        db.clearAllContent()
        widgetSnapshotStore.clear()
        sessionStore.setActiveCoupleId(null)
        invitation.value = null
        refreshMe()
    }

    fun isActive(status: CoupleStatus) = status == CoupleStatus.ACTIVE
}

private suspend fun Flow<Boolean>.firstValue(): Boolean = this.first()
