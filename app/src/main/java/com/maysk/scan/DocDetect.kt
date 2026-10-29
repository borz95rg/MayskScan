package com.maysk.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Для фото, снятых не через сканер Google:
 * — находит светлый лист на тёмном фоне и вырезает его с выпрямлением перспективы;
 * — выравнивает наклон строк текста.
 * Всё работает на телефоне, без интернета.
 */
object DocDetect {

    /** Вырезает лист. null — лист не найден уверенно (оставляем фото как есть). */
    fun autoCrop(src: Bitmap): Bitmap? {
        val quad = findQuad(src) ?: return null
        return warp(src, quad)
    }

    /** Углы листа на исходном фото: tl, tr, br, bl (x, y по очереди). */
    fun findQuad(src: Bitmap): FloatArray? {
        val k = 480f / max(src.width, src.height)
        val w = max(8, (src.width * k).roundToInt())
        val h = max(8, (src.height * k).roundToInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        val gray = IntArray(w * h) { i -> val c = px[i]; (((c shr 16) and 255) * 30 + ((c shr 8) and 255) * 59 + (c and 255) * 11) / 100 }
        val t = otsu(gray)
        val mask = BooleanArray(w * h) { gray[it] > t }

        // Самая большая светлая область — лист
        val label = IntArray(w * h)
        val queue = IntArray(w * h)
        var best = 0
        var bestArea = 0
        var cur = 0
        for (start in 0 until w * h) {
            if (!mask[start] || label[start] != 0) continue
            cur++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = cur
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                if (x > 0) { val q = p - 1; if (mask[q] && label[q] == 0) { label[q] = cur; queue[tail++] = q } }
                if (x < w - 1) { val q = p + 1; if (mask[q] && label[q] == 0) { label[q] = cur; queue[tail++] = q } }
                if (y > 0) { val q = p - w; if (mask[q] && label[q] == 0) { label[q] = cur; queue[tail++] = q } }
                if (y < h - 1) { val q = p + w; if (mask[q] && label[q] == 0) { label[q] = cur; queue[tail++] = q } }
            }
            if (tail > bestArea) { bestArea = tail; best = cur }
        }
        val n = w * h
        if (bestArea < n * 0.15f || bestArea > n * 0.93f) return null

        // Углы — крайние точки по диагоналям
        var tlV = Int.MAX_VALUE; var brV = Int.MIN_VALUE; var trV = Int.MIN_VALUE; var blV = Int.MAX_VALUE
        var tl = 0; var br = 0; var tr = 0; var bl = 0
        for (p in 0 until n) {
            if (label[p] != best) continue
            val x = p % w
            val y = p / w
            val s = x + y
            val d = x - y
            if (s < tlV) { tlV = s; tl = p }
            if (s > brV) { brV = s; br = p }
            if (d > trV) { trV = d; tr = p }
            if (d < blV) { blV = d; bl = p }
        }
        val q = floatArrayOf(
            (tl % w).toFloat(), (tl / w).toFloat(), (tr % w).toFloat(), (tr / w).toFloat(),
            (br % w).toFloat(), (br / w).toFloat(), (bl % w).toFloat(), (bl / w).toFloat(),
        )
        // Четырёхугольник должен покрывать почти всю область, иначе это не лист
        val area = abs(
            (q[0] * q[3] - q[2] * q[1]) + (q[2] * q[5] - q[4] * q[3]) + (q[4] * q[7] - q[6] * q[5]) + (q[6] * q[1] - q[0] * q[7])
        ) / 2f
        if (area < bestArea * 0.85f || area > bestArea * 1.25f) return null
        // Лист уже почти на весь кадр — обрезать нечего
        if (area > n * 0.9f) return null
        val back = 1f / k
        return FloatArray(8) { q[it] * back }
    }

    fun warp(src: Bitmap, q: FloatArray): Bitmap {
        val wTop = hypot(q[2] - q[0], q[3] - q[1])
        val wBot = hypot(q[4] - q[6], q[5] - q[7])
        val hL = hypot(q[6] - q[0], q[7] - q[1])
        val hR = hypot(q[4] - q[2], q[5] - q[3])
        var ow = max(wTop, wBot)
        var oh = max(hL, hR)
        val cap = 3000f / max(ow, oh)
        if (cap < 1f) { ow *= cap; oh *= cap }
        val W = max(16, ow.roundToInt())
        val H = max(16, oh.roundToInt())
        val m = Matrix()
        m.setPolyToPoly(q, 0, floatArrayOf(0f, 0f, W.toFloat(), 0f, W.toFloat(), H.toFloat(), 0f, H.toFloat()), 0, 4)
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Наклон строк текста в градусах (по часовой стрелке), 0 — ровно или не удалось определить. */
    fun skewAngle(src: Bitmap): Float {
        val k = min(1f, 900f / max(src.width, src.height))
        val w = max(8, (src.width * k).roundToInt())
        val h = max(8, (src.height * k).roundToInt())
        val small = if (k < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        val gray = IntArray(w * h) { i -> val c = px[i]; (((c shr 16) and 255) * 30 + ((c shr 8) and 255) * 59 + (c and 255) * 11) / 100 }
        val t = otsu(gray)
        // Тёмные точки (буквы), не больше 40 000
        var count = 0
        for (g in gray) if (g < t) count++
        if (count < 300 || count > gray.size / 2) return 0f
        val step = max(1, count / 40000)
        val xs = IntArray(count / step + 1)
        val ys = IntArray(count / step + 1)
        var m = 0
        var seen = 0
        for (i in gray.indices) {
            if (gray[i] >= t) continue
            if (seen++ % step != 0) continue
            if (m >= xs.size) break
            xs[m] = i % w; ys[m] = i / w; m++
        }
        val diag = (w + h) * 2
        val bins = IntArray(diag)
        var bestA = 0f
        var bestScore = -1.0
        var a = -10f
        while (a <= 10.001f) {
            val r = Math.toRadians(a.toDouble())
            val ca = cos(r)
            val sa = sin(r)
            java.util.Arrays.fill(bins, 0)
            for (j in 0 until m) {
                val y = (ys[j] * ca - xs[j] * sa).roundToInt() + w
                if (y in 0 until diag) bins[y]++
            }
            var score = 0.0
            for (b in bins) score += b.toDouble() * b
            if (score > bestScore) { bestScore = score; bestA = a }
            a += 0.25f
        }
        return bestA
    }

    /** Выравнивает наклон строк (если он больше полградуса). */
    fun deskew(src: Bitmap): Bitmap {
        val a = try { skewAngle(src) } catch (e: Exception) { 0f }
        if (abs(a) < 0.5f) return src
        return rotateFree(src, -a)
    }

    fun rotateFree(src: Bitmap, deg: Float): Bitmap {
        val r = Math.toRadians(deg.toDouble())
        val c = abs(cos(r))
        val s = abs(sin(r))
        val W = (src.width * c + src.height * s).roundToInt()
        val H = (src.width * s + src.height * c).roundToInt()
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val m = Matrix()
        m.postTranslate(-src.width / 2f, -src.height / 2f)
        m.postRotate(deg)
        m.postTranslate(W / 2f, H / 2f)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Подготовка фото «как со сканера»: обрезка листа + выравнивание. Исходник не трогает. */
    fun straighten(src: Bitmap): Bitmap {
        val cropped = try { autoCrop(src) } catch (e: Throwable) { null } ?: src
        val straight = deskew(cropped)
        if (straight !== cropped && cropped !== src) cropped.recycle()
        return straight
    }

    private fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        for (g in gray) hist[g.coerceIn(0, 255)]++
        val total = gray.size
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var t = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; t = i }
        }
        return t
    }
}
