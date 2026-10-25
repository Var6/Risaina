package com.risaina.app.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.WidgetBoardDto
import com.risaina.app.core.network.apiCall
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.boardStore: DataStore<Preferences> by preferencesDataStore(name = "widget_board")

/**
 * The "widget board": each partner chooses what the OTHER sees on their widgets (a photo, a note,
 * a mood, an emoji). `forMe` drives this phone's widgets; `mine` is what I've set for my partner.
 */
@Singleton
class WidgetBoardRepository @Inject constructor(
    private val api: RisainaApi,
    private val json: Json,
    @ApplicationContext private val context: Context,
) {
    private val kMine = stringPreferencesKey("mine")
    private val kForMe = stringPreferencesKey("for_me")

    private fun decode(s: String?) = s?.let { runCatching { json.decodeFromString(WidgetBoardDto.serializer(), it) }.getOrNull() }

    val mine: Flow<WidgetBoardDto?> = context.boardStore.data.map { decode(it[kMine]) }
    val forMe: Flow<WidgetBoardDto?> = context.boardStore.data.map { decode(it[kForMe]) }
    suspend fun forMeNow(): WidgetBoardDto? = forMe.first()

    suspend fun store(mine: WidgetBoardDto?, forMe: WidgetBoardDto?, replaceMine: Boolean = true, replaceForMe: Boolean = true) {
        context.boardStore.edit { p ->
            if (replaceMine) { if (mine == null) p.remove(kMine) else p[kMine] = json.encodeToString(WidgetBoardDto.serializer(), mine) }
            if (replaceForMe) { if (forMe == null) p.remove(kForMe) else p[kForMe] = json.encodeToString(WidgetBoardDto.serializer(), forMe) }
        }
    }

    suspend fun refresh(): AppResult<Unit> = apiCall { api.widgetBoard() }.onSuccess { store(it.mine, it.forMe) }.map { }

    /** Sends what my partner's widgets should show. A null value clears that part. */
    suspend fun publish(note: String?, emoji: String?, mood: String?, photoMediaId: String?): AppResult<WidgetBoardDto> {
        fun v(s: String?) = if (s.isNullOrBlank()) JsonNull else JsonPrimitive(s.trim())
        val body = buildJsonObject { put("note", v(note)); put("emoji", v(emoji)); put("mood", v(mood)); put("photoMediaId", v(photoMediaId)) }
        return apiCall { api.putWidgetBoard(body) }.onSuccess { store(it.board, null, replaceMine = true, replaceForMe = false) }.map { it.board }
    }

    suspend fun clear() { context.boardStore.edit { it.clear() } }
}
