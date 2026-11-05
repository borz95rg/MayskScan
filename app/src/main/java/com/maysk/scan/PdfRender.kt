package com.maysk.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Быстрый показ страниц PDF средствами Android. */
class PdfRender(val file: File) : Closeable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(fd)
    } catch (e: Exception) {
        fd.close()
        throw e
    }

    val pageCount: Int get() = renderer.pageCount

    /** Размер страницы в пунктах. */
    @Synchronized
    fun pageSize(index: Int): Pair<Int, Int> = renderer.openPage(index).use { it.width to it.height }

    @Synchronized
    fun render(index: Int, widthPx: Int, night: Boolean = false): Bitmap {
        renderer.openPage(index).use { page ->
            val w = max(1, widthPx)
            val h = max(1, (widthPx.toFloat() * page.height / page.width).roundToInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return if (night) invert(bmp) else bmp
        }
    }

    /**
     * Только часть страницы — для чёткого текста при сильном увеличении.
     * [pxPerPoint] — пикселей на пункт страницы; [left]/[top] — смещение фрагмента
     * от левого верхнего угла страницы в пикселях при этом масштабе.
     */
    @Synchronized
    fun renderRegion(index: Int, pxPerPoint: Float, left: Float, top: Float, w: Int, h: Int, night: Boolean = false): Bitmap {
        renderer.openPage(index).use { page ->
            val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix()
            m.setScale(pxPerPoint, pxPerPoint)
            m.postTranslate(-left, -top)
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return if (night) invert(bmp) else bmp
        }
    }

    // Синхронно с render: не закрываем рендерер, пока страница ещё рисуется в другом потоке.
    @Synchronized
    override fun close() {
        try { renderer.close() } catch (e: Exception) {}
        try { fd.close() } catch (e: Exception) {}
    }

    companion object {
        /** Ночной режим: светлый текст на тёмном фоне. */
        fun invert(src: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val cm = ColorMatrix(floatArrayOf(
                -0.9f, 0f, 0f, 0f, 240f,
                0f, -0.9f, 0f, 0f, 240f,
                0f, 0f, -0.85f, 0f, 245f,
                0f, 0f, 0f, 1f, 0f,
            ))
            Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
            src.recycle()
            return out
        }

        /** Пробует открыть; если файл защищён паролем — вернёт null. */
        fun tryOpen(f: File): PdfRender? = try {
            PdfRender(f)
        } catch (e: SecurityException) {
            null
        }
    }
}
