package com.maysk.scan

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.googlecode.leptonica.android.ReadFile
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessPdfRenderer
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Распознавание текста на телефоне (Tesseract), 8 языков, без интернета.
 * Языковые файлы лежат в приложении и при первом запуске копируются в его память.
 */
object Ocr {

    data class Lang(val code: String, val name: String, val mlkit: String)

    val LANGS = listOf(
        Lang("rus", "Русский", "ru"),
        Lang("kaz", "Қазақша", "kk"),
        Lang("eng", "English", "en"),
        Lang("ukr", "Українська", "uk"),
        Lang("deu", "Deutsch", "de"),
        Lang("fra", "Français", "fr"),
        Lang("spa", "Español", "es"),
        Lang("tur", "Türkçe", "tr"),
    )

    fun nameOf(spec: String): String =
        if (spec == "auto") "Автоопределение"
        else spec.split('+').joinToString(" + ") { c -> LANGS.firstOrNull { it.code == c }?.name ?: c }

    private fun dataDir(ctx: Context) = File(ctx.filesDir, "tesseract")

    /** Копирует языковые файлы из приложения в память телефона (один раз на версию). */
    @Synchronized
    fun ensureData(ctx: Context) {
        val prefs = Prefs(ctx)
        val dir = File(dataDir(ctx), "tessdata").apply { mkdirs() }
        val ver = BuildConfig.VERSION_CODE
        val assets = ctx.assets.list("tessdata") ?: emptyArray()
        if (prefs.tessDataVersion == ver && assets.all { File(dir, it).exists() }) return
        for (name in assets) {
            val out = File(dir, name)
            ctx.assets.open("tessdata/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        prefs.tessDataVersion = ver
    }

    fun available(ctx: Context): Set<String> {
        val dir = File(dataDir(ctx), "tessdata")
        return (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".traineddata") }.map { it.name.removeSuffix(".traineddata") }.toSet()
    }

    class Result(val text: String, val langs: String, val confidence: Int)

    /**
     * Распознаёт текст. spec — "auto" или "rus+eng".
     * В режиме «авто» сначала читаем как русский+английский, затем определяем язык по тексту
     * и при необходимости перечитываем на нужном языке.
     */
    suspend fun recognize(ctx: Context, bmp: Bitmap, spec: String, enhance: Boolean, onProgress: (Int) -> Unit = {}): Result {
        ensureData(ctx)
        val img = if (enhance) ImageTools.forOcr(bmp) else bmp
        try {
            if (spec != "auto") return run(ctx, img, spec, onProgress)
            val first = run(ctx, img, "rus+eng", onProgress)
            val lang = detect(first.text)
            val better = when (lang) {
                "kk" -> "kaz+rus"
                "uk" -> "ukr+rus"
                "de" -> "deu+eng"
                "fr" -> "fra+eng"
                "es" -> "spa+eng"
                "tr" -> "tur+eng"
                else -> null
            }
            if (better == null) return first
            val second = run(ctx, img, better, onProgress)
            return if (second.confidence + 3 >= first.confidence) second else first
        } finally {
            if (img !== bmp) img.recycle()
        }
    }

    private fun run(ctx: Context, img: Bitmap, langs: String, onProgress: (Int) -> Unit): Result {
        val have = available(ctx)
        val use = langs.split('+').filter { it in have }.ifEmpty { listOf("eng") }.joinToString("+")
        val tess = TessBaseAPI { p -> onProgress(p.percent) }
        try {
            if (!tess.init(dataDir(ctx).absolutePath, use)) throw IllegalStateException("Не удалось загрузить язык распознавания")
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess.setImage(img)
            tess.getHOCRText(0) // распознавание с отчётом о прогрессе
            val text = tess.getUTF8Text() ?: ""
            return Result(cleanup(text), use, tess.meanConfidence())
        } finally {
            tess.recycle()
        }
    }

    /** Определение языка текста (модель встроена, без интернета). Возвращает код вроде "ru", "kk", "und". */
    suspend fun detect(text: String): String {
        if (text.isBlank()) return "und"
        return try {
            LanguageIdentification.getClient().identifyLanguage(text.take(2000)).await()
        } catch (e: Exception) {
            "und"
        }
    }

    /** Убирает лишние пустые строки и переносы внутри абзацев. */
    fun cleanup(raw: String): String {
        val lines = raw.replace("\r", "").split('\n').map { it.trimEnd() }
        val out = StringBuilder()
        var blank = 0
        for (l in lines) {
            if (l.isBlank()) {
                blank++
                continue
            }
            if (out.isNotEmpty()) {
                if (blank > 0) out.append("\n\n")
                else if (out.endsWith("-") && l.first().isLowerCase()) out.setLength(out.length - 1) // перенос слова
                else out.append('\n')
            }
            out.append(l)
            blank = 0
        }
        return out.toString().trim()
    }

    /**
     * PDF с текстовым слоем: картинка страницы + невидимый текст поверх.
     * Такой PDF можно искать и копировать из него текст.
     */
    fun searchablePdf(ctx: Context, images: List<File>, spec: String, out: File, onPage: (Int) -> Unit = {}): File {
        ensureData(ctx)
        val have = available(ctx)
        val langs = (if (spec == "auto") "rus+eng" else spec).split('+').filter { it in have }.ifEmpty { listOf("eng") }.joinToString("+")
        val tess = TessBaseAPI()
        if (!tess.init(dataDir(ctx).absolutePath, langs)) {
            tess.recycle()
            throw IllegalStateException("Не удалось загрузить язык распознавания")
        }
        // Tesseract сам добавляет ".pdf" к имени
        val base = File(out.parentFile, out.name.removeSuffix(".pdf"))
        val renderer = TessPdfRenderer(tess, base.absolutePath)
        try {
            tess.beginDocument(renderer, out.nameWithoutExtension)
            images.forEachIndexed { i, f ->
                onPage(i + 1)
                val bmp = ImageTools.decodeFile(f, 2400)
                val gray = ImageTools.forOcr(bmp)
                val pix = ReadFile.readBitmap(gray)
                tess.addPageToDocument(pix, f.absolutePath, renderer)
                pix.recycle()
                gray.recycle()
                bmp.recycle()
            }
            tess.endDocument(renderer)
        } finally {
            renderer.recycle()
            tess.recycle()
        }
        val produced = File(base.absolutePath + ".pdf")
        if (produced.absolutePath != out.absolutePath && produced.exists()) produced.renameTo(out)
        if (!out.exists()) throw IllegalStateException("PDF не создан")
        return out
    }
}
