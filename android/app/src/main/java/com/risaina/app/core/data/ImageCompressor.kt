package com.risaina.app.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class CompressedImage(val file: File, val width: Int, val height: Int, val mimeType: String = "image/jpeg")

/**
 * Downsamples and re-encodes photos before upload. Long edge ≤ [maxEdge], JPEG at [quality].
 * EXIF orientation is applied and all other metadata (including GPS) is dropped.
 */
@Singleton
class ImageCompressor @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun compress(uri: Uri, maxEdge: Int = 2048, quality: Int = 85): CompressedImage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > maxEdge * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("Could not read image")

        val rotation = resolver.openInputStream(uri)?.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        val scale = minOf(1f, maxEdge.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply { postScale(scale, scale); if (rotation != 0f) postRotate(rotation) }
        val finalBitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (finalBitmap !== decoded) decoded.recycle()

        val out = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
        out.outputStream().use { finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        val result = CompressedImage(out, finalBitmap.width, finalBitmap.height)
        finalBitmap.recycle()
        result
    }
}
