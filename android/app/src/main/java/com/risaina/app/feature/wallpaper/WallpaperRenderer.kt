package com.risaina.app.feature.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.risaina.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

data class WallpaperSpec(
    val photo: File?,
    val note: String?,
    val emoji: String?,
    val dateText: String?,
    val darken: Float = 0.45f,
)

/**
 * Composes a wallpaper bitmap: photo (centre-cropped) or the brand gradient, a readability scrim,
 * a serif note, an emoji and a date in the lower third, and the two-discs mark. Pure
 * android.graphics so the same code powers preview, save and apply.
 */
@Singleton
class WallpaperRenderer @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun render(spec: WallpaperSpec, width: Int, height: Int): Bitmap = withContext(Dispatchers.Default) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val plum = 0xFF1B111B.toInt(); val wine = 0xFF51263D.toInt(); val ivory = 0xFFFFF8F2.toInt(); val blush = 0xFFE6BFCB.toInt(); val champagne = 0xFFCDB38A.toInt()

        // Background
        val bg = Paint().apply { shader = RadialGradient(width * 0.5f, height * 0.35f, max(width, height) * 0.9f, intArrayOf(wine, plum), null, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)

        // Photo
        spec.photo?.let { file -> decodeFor(file, width, height)?.let { photo ->
            val scale = max(width.toFloat() / photo.width, height.toFloat() / photo.height)
            val m = Matrix().apply { postScale(scale, scale); postTranslate((width - photo.width * scale) / 2f, (height - photo.height * scale) / 2f) }
            c.drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG))
            photo.recycle()
        } }

        // Scrim for text readability (bottom 55 %)
        val scrim = Paint().apply {
            shader = LinearGradient(0f, height * 0.4f, 0f, height.toFloat(), intArrayOf(Color.TRANSPARENT, Color.argb((255 * spec.darken).toInt(), 27, 17, 27), Color.argb((255 * min(1f, spec.darken + 0.35f)).toInt(), 27, 17, 27)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, height * 0.4f, width.toFloat(), height.toFloat(), scrim)

        val serif = runCatching { ResourcesCompat.getFont(context, R.font.fraunces_variable) }.getOrNull()
        val sans = runCatching { ResourcesCompat.getFont(context, R.font.inter_variable) }.getOrNull()
        val margin = width * 0.09f
        var y = height * 0.80f

        // Date (bottom), then mark
        spec.dateText?.let { date ->
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = champagne; textSize = width * 0.028f; typeface = sans; letterSpacing = 0.12f }
            c.drawText(date.uppercase(), margin, height * 0.86f, p)
        }
        drawMark(c, width - margin - width * 0.06f, height * 0.845f, width * 0.022f, blush, champagne, ivory)

        // Note (serif, wrapped, bottom-aligned above the date)
        spec.note?.takeIf { it.isNotBlank() }?.let { note ->
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ivory; textSize = width * (if (note.length > 90) 0.052f else 0.064f); typeface = serif }
            val layout = StaticLayout.Builder.obtain(note, 0, note.length, p, (width - 2 * margin).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.12f).setMaxLines(6).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            y = height * 0.82f - layout.height
            c.save(); c.translate(margin, y); layout.draw(c); c.restore()
        }

        // Emoji above the note
        spec.emoji?.takeIf { it.isNotBlank() }?.let { e ->
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = width * 0.11f }
            c.drawText(e, margin, y - height * 0.025f, p)
        }
        bmp
    }

    private fun decodeFor(file: File, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > width * 1.5 && bounds.outHeight / sample > height * 1.5) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun drawMark(c: Canvas, cx: Float, cy: Float, r: Float, blush: Int, champagne: Int, ivory: Int) {
        val d = r * 0.94f
        val a = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blush; alpha = 230 }
        val b = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = champagne; alpha = 230 }
        c.drawCircle(cx - d / 2, cy, r, a); c.drawCircle(cx + d / 2, cy, r, b)
        val lens = android.graphics.Path()
        val half = Math.sqrt((r * r - (d / 2) * (d / 2)).toDouble()).toFloat()
        lens.moveTo(cx, cy - half)
        lens.arcTo(cx - d / 2 - r, cy - r, cx - d / 2 + r, cy + r, -62f, 124f, false)
        lens.arcTo(cx + d / 2 - r, cy - r, cx + d / 2 + r, cy + r, 118f, 124f, false)
        lens.close()
        c.drawPath(lens, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ivory })
    }
}
