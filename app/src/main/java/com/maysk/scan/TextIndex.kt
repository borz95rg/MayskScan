package com.maysk.scan

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * Текст всех документов из Истории для поиска «по словам внутри».
 * Индекс строится в фоне по одному файлу, без интернета: из текста PDF/Word/Excel,
 * а для сканов и фото — распознаванием (до 10 страниц).
 */
object TextIndex {
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private const val MAX_PAGES = 10
    private const val MAX_CHARS = 200_000

    private fun dir(ctx: Context) = File(ctx.filesDir, "index").apply { mkdirs() }
    private fun file(ctx: Context, e: Entry) = File(dir(ctx), "${e.id}.txt")

    /** Текст документа (lowercase не делаем — для показа фрагмента), null — ещё не готов. */
    fun get(ctx: Context, e: Entry): String? {
        if (e.kind == Kind.QR) return e.extra
        val f = file(ctx, e)
        return if (f.exists()) try { f.readText() } catch (x: Exception) { null } else null
    }

    fun delete(ctx: Context, e: Entry) { file(ctx, e).delete() }

    fun schedule(ctx: Context, e: Entry) {
        val app = ctx.applicationContext
        scope.launch { try { build(app, e) } catch (t: Throwable) {} }
    }

    /** Достраивает индекс для старых записей (вызывается при запуске). */
    fun scheduleMissing(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            for (e in History.all(app)) {
                if (e.kind == Kind.QR || file(app, e).exists()) continue
                try { build(app, e) } catch (t: Throwable) {}
            }
        }
    }

    private suspend fun build(ctx: Context, e: Entry) {
        val out = file(ctx, e)
        if (out.exists()) return
        val f = e.file
        if (!f.exists()) return
        val ext = f.extension.lowercase()
        val text = when (ext) {
            "txt", "md", "csv", "html", "rtf" -> f.readText().take(MAX_CHARS)
            "docx" -> OfficeReader.docxText(f)
            "xlsx" -> OfficeReader.xlsx(f).joinToString("\n") { s -> s.rows.joinToString("\n") { it.joinToString(" ") } }
            "pdf" -> pdfText(ctx, f)
            "jpg", "jpeg", "png", "webp" -> ocrBitmap(ctx, ImageTools.decodeFile(f, 2200))
            else -> ""
        }
        out.writeText(text.take(MAX_CHARS))
    }

    private suspend fun pdfText(ctx: Context, f: File): String {
        val layer = try { PdfTools.pageTexts(f).joinToString("\n") } catch (e: Exception) { "" }
        if (layer.count { it.isLetterOrDigit() } > 30) return layer
        // Скан без текстового слоя — распознаём страницы
        val sb = StringBuilder()
        PdfRender(f).use { r ->
            for (i in 0 until minOf(r.pageCount, MAX_PAGES)) {
                sb.append(ocrBitmap(ctx, r.render(i, 1700))).append('\n')
            }
        }
        return sb.toString()
    }

    private suspend fun ocrBitmap(ctx: Context, bmp: Bitmap): String = try {
        Ocr.recognize(ctx, bmp, Prefs(ctx).ocrLang, true).text
    } finally {
        bmp.recycle()
    }

    /** Фрагмент текста вокруг найденного слова. */
    fun snippet(text: String, query: String): String? {
        val i = text.indexOf(query, ignoreCase = true)
        if (i < 0) return null
        val start = maxOf(0, i - 40)
        val end = minOf(text.length, i + query.length + 60)
        return (if (start > 0) "…" else "") + text.substring(start, end).replace('\n', ' ').trim() + (if (end < text.length) "…" else "")
    }
}
