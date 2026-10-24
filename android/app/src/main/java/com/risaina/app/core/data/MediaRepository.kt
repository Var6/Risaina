package com.risaina.app.core.data

import android.content.Context
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.common.onFailure
import com.risaina.app.core.common.onSuccess
import com.risaina.app.core.database.MediaDao
import com.risaina.app.core.model.Media
import com.risaina.app.core.model.MediaCategory
import com.risaina.app.core.network.CreateUploadRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.apiCall
import com.risaina.app.core.network.apiOrThrow
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Media upload/download via the backend's short-lived URLs. The client never knows bucket names
 * or object keys. Downloaded files live in the app's private cache so widgets and playback work
 * offline; the cache is excluded from backups.
 */
@Singleton
class MediaRepository @Inject constructor(
    private val api: RisainaApi,
    private val mediaDao: MediaDao,
    @Named("plain") private val plain: HttpClient,
    @ApplicationContext private val context: Context,
) {
    private data class CachedUrl(val url: String, val expiresAt: Instant)
    private val urlCache = HashMap<String, CachedUrl>()
    private val cacheMutex = Mutex()

    val cacheDir: File get() = File(context.filesDir, "media_cache").apply { mkdirs() }

    /** Full flow: authorize → PUT → complete → persist. Progress is 0..1. */
    suspend fun upload(
        category: MediaCategory,
        file: File,
        mimeType: String,
        durationMs: Long? = null,
        width: Int? = null,
        height: Int? = null,
        onProgress: (Float) -> Unit = {},
    ): AppResult<Media> = apiCall {
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val auth = apiOrThrow { api.createUpload(CreateUploadRequest(category.wire, mimeType, bytes.size.toLong(), durationMs, width, height)) }
        onProgress(0.02f)
        apiOrThrow {
            api.uploadToPresigned(auth.upload.url, auth.upload.headers, bytes, mimeType) { sent, total ->
                if (total > 0) onProgress(0.02f + 0.9f * (sent.toFloat() / total))
            }
        }
        val completed = apiOrThrow { api.completeUpload(auth.media.id) }
        // Keep the local copy under the media id so we never re-download what we just sent.
        val local = File(cacheDir, "${completed.media.id}.${extensionFor(mimeType)}")
        withContext(Dispatchers.IO) { file.copyTo(local, overwrite = true) }
        mediaDao.upsert(completed.media.toEntity(localPath = local.absolutePath))
        onProgress(1f)
        completed.media.toEntity(local.absolutePath).toDomain()
    }

    suspend fun resolveUrl(mediaId: String): AppResult<String> {
        cacheMutex.withLock {
            urlCache[mediaId]?.let { if (it.expiresAt.isAfter(Instant.now().plusSeconds(30))) return AppResult.Success(it.url) }
        }
        return apiCall { api.mediaUrl(mediaId) }.map { r ->
            cacheMutex.withLock { urlCache[mediaId] = CachedUrl(r.url, Instant.parse(r.expiresAt)) }
            r.url
        }
    }

    suspend fun localFile(mediaId: String): File? {
        val row = mediaDao.byId(mediaId)
        val path = row?.localPath ?: return null
        return File(path).takeIf { it.exists() }
    }

    /** Download to the private cache if not already present. Used for voice notes and widget photos. */
    suspend fun ensureCached(mediaId: String, mimeHint: String? = null): AppResult<File> {
        localFile(mediaId)?.let { return AppResult.Success(it) }
        val url = when (val r = resolveUrl(mediaId)) { is AppResult.Success -> r.value; is AppResult.Failure -> return r }
        return apiCall {
            val ext = extensionFor(mimeHint ?: mediaDao.byId(mediaId)?.mimeType ?: "application/octet-stream")
            val target = File(cacheDir, "$mediaId.$ext")
            val tmp = File(cacheDir, "$mediaId.$ext.part")
            withContext(Dispatchers.IO) {
                val response = plain.get(url)
                tmp.outputStream().use { out -> response.bodyAsChannel().copyTo(out) }
                if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            }
            if (mediaDao.byId(mediaId) != null) mediaDao.setLocalPath(mediaId, target.absolutePath)
            target
        }
    }

    suspend fun delete(mediaId: String): AppResult<Unit> {
        val res = apiCall { api.deleteMedia(mediaId) }
        if (res is AppResult.Success) {
            localFile(mediaId)?.delete()
            mediaDao.delete(mediaId)
        }
        return res
    }

    suspend fun observe(mediaId: String) = mediaDao.observe(mediaId)
    suspend fun get(mediaId: String): Media? = mediaDao.byId(mediaId)?.toDomain()
    suspend fun remember(media: com.risaina.app.core.network.MediaDto) { mediaDao.upsert(media.toEntity(localFile(media.id)?.absolutePath)) }

    fun extensionFor(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "audio/mp4", "audio/aac", "audio/m4a" -> "m4a"
        "audio/ogg" -> "ogg"
        else -> "bin"
    }

    fun sizeError(category: MediaCategory, sizeBytes: Long): AppError? {
        val limit = when (category) {
            MediaCategory.PHOTO -> 10L * 1024 * 1024
            MediaCategory.VOICE -> 8L * 1024 * 1024
            MediaCategory.AVATAR -> 3L * 1024 * 1024
            MediaCategory.WALLPAPER -> 12L * 1024 * 1024
        }
        return if (sizeBytes > limit) AppError.Validation("That file is too large (max ${limit / 1024 / 1024} MB)") else null
    }
}
