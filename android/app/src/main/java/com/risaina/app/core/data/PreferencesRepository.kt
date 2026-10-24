package com.risaina.app.core.data

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.model.Preferences
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences are applied locally first (so quiet hours work offline) and pushed to the server,
 * which needs them to decide whether to send a push. If the push fails the dirty flag makes the
 * next sync retry; local state is never rolled back.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    private val store: PreferencesStore,
    private val sessionStore: SessionStore,
    private val api: RisainaApi,
) {
    val preferences: Flow<Preferences> = store.preferences

    suspend fun current(): Preferences = store.current()

    suspend fun update(transform: (Preferences) -> Preferences): AppResult<Unit> {
        store.update(transform)
        return push()
    }

    suspend fun push(): AppResult<Unit> {
        if (sessionStore.current() == null) return AppResult.Success(Unit)
        val prefs = store.current()
        val res = apiCall { api.putPreferences(prefs.toDto()) }
        sessionStore.setPrefsDirty(res !is AppResult.Success)
        return res.map { }
    }

    suspend fun pushIfDirty() { if (sessionStore.prefsDirty.first()) push() }
}
