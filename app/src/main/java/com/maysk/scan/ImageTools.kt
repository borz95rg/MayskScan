package com.maysk.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Фильтры для скана — как в дизайне: Оригинал, Улучшить, Ч/Б, Контраст, Фото. */
enum class Filter(val title: String) {
    ORIGINAL("Оригинал"), ENHANCE("Улучшить"), BW("Ч/Б"), CONTRAST("Контраст"), PHOTO("Фото"), GRAY("Серый")
}

/**
 * Обработка изображений прямо на телефоне, без интернета:
 * убирает тени, выравнивает фон до белого, повышает резкость и контраст.
 */
object ImageTools {

    /** Загружает картинку с учётом поворота камеры, уменьшая до maxSide по длинной стороне. */
    fun decode(ctx: Context, uri: Uri, maxSide: Int = 2600): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Не удалось открыть изображение")
        val rotation = try {
            ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (e: Exception) {
            0
        }
        return scaleDown(rotate(bmp, rotation), maxSide)
    }

    fun decodeFile(f: File, maxSide: Int = 2600): Bitmap = decode(DummyCtx.get(), Uri.fromFile(f), maxSide)

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out != src) src.recycle()
        return out
    }

    fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxSide) return src
        val k = maxSide.toFloat() / m
        val out = Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)
        if (out != src) src.recycle()
        return out
    }

    fun saveJpeg(bmp: Bitmap, f: File, quality: Int = 90): File {
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return f
    }

    fun savePng(bmp: Bitmap, f: File): File {
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    @Suppress("DEPRECATION")
    fun saveWebp(bmp: Bitmap, f: File, quality: Int = 90): File {
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.WEBP, quality, it) }
        return f
    }

    fun apply(src: Bitmap, filter: Filter): Bitmap = when (filter) {
        Filter.ORIGINAL -> src.copy(Bitmap.Config.ARGB_8888, true)
        Filter.ENHANCE -> enhance(src, color = true)
        Filter.GRAY -> enhance(src, color = false)
        Filter.BW -> blackWhite(src)
        Filter.CONTRAST -> contrast(src, 1.45f)
        Filter.PHOTO -> photo(src)
    }

    /**
     * «Улучшить документ»: делим каждый пиксель на оценку фона (размытая копия) —
     * тени и желтизна уходят, бумага становится белой. Потом подтягиваем уровни и резкость.
     */
    fun enhance(src: Bitmap, color: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val bg = background(src, w, h)

        val out = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val b = bg[i]
            val bgR = max(((b shr 16) and 0xFF), 40)
            val bgG = max(((b shr 8) and 0xFF), 40)
            val bgB = max((b and 0xFF), 40)
            var r = ((p shr 16) and 0xFF) * 255 / bgR
            var g = ((p shr 8) and 0xFF) * 255 / bgG
            var bl = (p and 0xFF) * 255 / bgB
            if (!color) {
                val y = (r * 299 + g * 587 + bl * 114) / 1000
                r = y; g = y; bl = y
            }
            out[i] = (0xFF shl 24) or (levels(r) shl 16) or (levels(g) shl 8) or levels(bl)
        }
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(sharpen(out, w, h, 0.6f), 0, w, 0, 0, w, h)
        return res
    }

    /** Чёрно-белый документ: улучшение + порог Оцу. */
    fun blackWhite(src: Bitmap): Bitmap {
        val g = enhance(src, color = false)
        val w = g.width
        val h = g.height
        val px = IntArray(w * h)
        g.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        for (p in px) hist[p and 0xFF]++
        val t = otsu(hist, px.size)
        for (i in px.indices) px[i] = if ((px[i] and 0xFF) > t) -0x1 else -0x1000000
        g.setPixels(px, 0, w, 0, 0, w, h)
        return g
    }

    fun contrast(src: Bitmap, k: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val t = (1f - k) * 128f
        val cm = ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, t,
            0f, k, 0f, 0f, t,
            0f, 0f, k, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    fun photo(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(1.3f) }
        cm.postConcat(ColorMatrix(floatArrayOf(
            1.1f, 0f, 0f, 0f, -8f,
            0f, 1.1f, 0f, 0f, -8f,
            0f, 0f, 1.1f, 0f, -8f,
            0f, 0f, 0f, 1f, 0f,
        )))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /** Изображение, подготовленное для распознавания текста: серое, без теней, контрастное. */
    fun forOcr(src: Bitmap): Bitmap {
        val scaled = scaleKeep(src, 2400)
        val straight = DocDetect.deskew(scaled) // ровные строки распознаются точнее
        val out = enhance(straight, color = false)
        if (straight !== scaled && straight !== out) straight.recycle()
        if (scaled !== src && scaled !== out) scaled.recycle()
        return out
    }

    private fun scaleKeep(src: Bitmap, maxSide: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxSide) return src
        val k = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)
    }

    /** Фон страницы: сильно уменьшаем, размываем «максимумом» (убираем буквы) и увеличиваем обратно. */
    private fun background(src: Bitmap, w: Int, h: Int): IntArray {
        val small = 64
        val k = small.toFloat() / max(w, h)
        val sw = max(4, (w * k).roundToInt())
        val sh = max(4, (h * k).roundToInt())
        val s = Bitmap.createScaledBitmap(src, sw, sh, true)
        val sp = IntArray(sw * sh)
        s.getPixels(sp, 0, sw, 0, 0, sw, sh)
        s.recycle()
        // «Расширение» светлого: текст тёмный и тонкий, поэтому максимум в окне — это бумага
        val dil = IntArray(sp.size)
        val rad = 2
        for (y in 0 until sh) for (x in 0 until sw) {
            var mr = 0; var mg = 0; var mb = 0
            for (dy in -rad..rad) for (dx in -rad..rad) {
                val xx = min(sw - 1, max(0, x + dx))
                val yy = min(sh - 1, max(0, y + dy))
                val p = sp[yy * sw + xx]
                mr = max(mr, (p shr 16) and 0xFF); mg = max(mg, (p shr 8) and 0xFF); mb = max(mb, p and 0xFF)
            }
            dil[y * sw + x] = (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
        }
        val d = Bitmap.createBitmap(dil, sw, sh, Bitmap.Config.ARGB_8888)
        // Сглаживание: ещё раз уменьшить-увеличить
        val blur = Bitmap.createScaledBitmap(d, max(2, sw / 2), max(2, sh / 2), true)
        val big = Bitmap.createScaledBitmap(blur, w, h, true)
        val out = IntArray(w * h)
        big.getPixels(out, 0, w, 0, 0, w, h)
        d.recycle(); blur.recycle(); big.recycle()
        return out
    }

    private fun levels(v: Int): Int {
        // Чёрная точка 35, белая 235, плюс лёгкое затемнение текста
        val x = ((v - 35) * 255 / 200).coerceIn(0, 255)
        return if (x < 200) (x * x / 200) else x
    }

    private fun sharpen(px: IntArray, w: Int, h: Int, amount: Float): IntArray {
        if (w < 3 || h < 3) return px
        val out = px.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = px[i]
                val n = px[i - w]; val s = px[i + w]; val e = px[i + 1]; val wv = px[i - 1]
                var res = 0xFF shl 24
                var sh = 16
                while (sh >= 0) {
                    val cv = (c shr sh) and 0xFF
                    val avg = (((n shr sh) and 0xFF) + ((s shr sh) and 0xFF) + ((e shr sh) and 0xFF) + ((wv shr sh) and 0xFF)) shr 2
                    val v = (cv + (cv - avg) * amount).toInt().coerceIn(0, 255)
                    res = res or (v shl sh)
                    sh -= 8
                }
                out[i] = res
            }
        }
        return out
    }

    private fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var t = 160
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = i }
        }
        return t
    }
}

/** Доступ к контексту приложения там, где его неудобно передавать. */
object DummyCtx {
    lateinit var app: Context
    fun get(): Context = app
}
