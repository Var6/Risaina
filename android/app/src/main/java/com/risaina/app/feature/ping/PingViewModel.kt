package com.risaina.app.feature.ping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.PingRepository
import com.risaina.app.core.model.Ping
import com.risaina.app.core.model.PingType
import com.risaina.app.core.notifications.Haptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** History + detail. Sending lives in HomeViewModel so the home button owns its animation state. */
@HiltViewModel
class PingViewModel @Inject constructor(
    private val pingRepository: PingRepository,
    private val haptics: Haptics,
    sessionStore: com.risaina.app.core.datastore.SessionStore,
    coupleRepository: com.risaina.app.core.data.CoupleRepository,
) : ViewModel() {
    val history: StateFlow<List<Ping>> = pingRepository.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val myId: StateFlow<String?> = sessionStore.session.map { it?.userId }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val partnerName: StateFlow<String?> = coupleRepository.partner.map { it?.displayName }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { viewModelScope.launch { pingRepository.refresh() } }

    fun retry(localKey: String) { viewModelScope.launch { pingRepository.retry(localKey) } }
    fun discard(localKey: String) { viewModelScope.launch { pingRepository.discardFailed(localKey) } }
    fun markSeen(id: String) { viewModelScope.launch { pingRepository.markSeen(id) } }

    fun replyWith(type: PingType, onResult: (AppResult<Ping>) -> Unit) {
        viewModelScope.launch {
            haptics.tap()
            val r = pingRepository.send(type, type.defaultEmoji, null)
            if (r is AppResult.Success) haptics.confirm() else if ((r as AppResult.Failure).error !is AppError.RateLimited) haptics.error()
            onResult(r)
        }
    }
}
