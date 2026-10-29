package com.maysk.scan

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Пакетная обработка: десятки фото за раз → один PDF, PDF с текстом или текст всех страниц. */
class BatchActivity : BaseActivity() {

    private val uris = ArrayList<Uri>()
    private var filter = Filter.ENHANCE
    private var crop = true
    private lateinit var countText: TextView
    private lateinit var thumbs: LinearLayout
    private lateinit var filterRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crop = Prefs(this).autoCrop
        val body = screen("Пакетная обработка")
        body.addView(text("Выберите сразу много фото документов — приложение обработает их все и соберёт в один файл.", 13.5f, R.color.text2))

        countText = text("Фото не выбраны", 16f, bold = true)
        body.addView(countText, lp().apply { topMargin = dp(14) })
        val tsc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        thumbs = hbox()
        tsc.addView(thumbs)
        body.addView(tsc, lp().apply { topMargin = dp(8) })
        body.addView(secondaryButton("Выбрать фото", R.drawable.ic_image) { pickImages() }, lp().apply { topMargin = dp(10) })

        body.addView(sectionTitle("ОБРАБОТКА"))
        val (sc, row) = chipRow()
        filterRow = row
        body.addView(sc)
        body.addView(listRow(R.drawable.ic_scan, "Обрезка и выравнивание", "Найти край листа и выпрямить строки", switchView(crop) { crop = it }), lp().apply { topMargin = dp(10) })

        body.addView(sectionTitle("ЧТО СДЕЛАТЬ"))
        body.addView(primaryButton("Собрать в один PDF", R.drawable.ic_pdf) { run(0) })
        body.addView(secondaryButton("PDF с текстом (поиск и копирование)", R.drawable.ic_ocr) { run(1) }, lp().apply { topMargin = dp(10) })
        body.addView(secondaryButton("Распознать текст всех фото", R.drawable.ic_text) { run(2) }, lp().apply { topMargin = dp(10) })
        renderFilters()

        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>("uris")?.let { uris.addAll(it); renderThumbs() }
        intent.getStringArrayExtra("images")?.let { arr -> uris.addAll(arr.map { Uri.fromFile(File(it)) }); renderThumbs() }
        if (uris.isEmpty() && savedInstanceState == null) pickImages()
    }

    private fun pickImages() = pick(arrayOf("image/*"), true) { uris.addAll(it); renderThumbs() }

    private fun renderFilters() {
        filterRow.removeAllViews()
        for (f in listOf(Filter.ENHANCE, Filter.ORIGINAL, Filter.BW, Filter.GRAY, Filter.CONTRAST)) {
            filterRow.addView(chip(f.title, f == filter) { filter = f; renderFilters() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
    }

    private fun renderThumbs() {
        countText.text = if (uris.isEmpty()) "Фото не выбраны" else "Выбрано фото: ${uris.size}"
        thumbs.removeAllViews()
        lifecycleScope.launch {
            for (u in uris.take(30)) {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 16 }) }
                    } catch (e: Exception) { null }
                }
                thumbs.addView(ImageView(this@BatchActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(col(R.color.card), 10, col(R.color.stroke))
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    setImageBitmap(bmp)
                }, LinearLayout.LayoutParams(dp(54), dp(72)).apply { rightMargin = dp(6) })
            }
        }
    }

    /** mode: 0 — PDF, 1 — PDF с текстом, 2 — текст. */
    private fun run(mode: Int) {
        if (uris.isEmpty()) { pickImages(); return }
        lifecycleScope.launch {
            showProgress("Обрабатываю фото…")
            val files = ArrayList<File>()
            try {
                // 1. Подготовка страниц
                withContext(Dispatchers.IO) {
                    uris.forEachIndexed { i, u ->
                        runOnUiThread { showProgress("Обрабатываю фото ${i + 1} из ${uris.size}…") }
                        try {
                            val raw = ImageTools.decode(this@BatchActivity, u, 2600)
                            val straight = if (crop) DocDetect.straighten(raw) else raw
                            if (straight !== raw) raw.recycle()
                            val out = ImageTools.apply(straight, filter)
                            if (out !== straight) straight.recycle()
                            val f = File(cacheDir, "batch_${System.nanoTime()}_$i.jpg")
                            ImageTools.saveJpeg(out, f, 88)
                            out.recycle()
                            files.add(f)
                        } catch (e: OutOfMemoryError) {
                            System.gc()
                        } catch (e: Exception) {
                            // фото не открылось — пропускаем
                        }
                    }
                }
                if (files.isEmpty()) { hideProgress(); toast("Не удалось открыть фото"); return@launch }

                // 2. Результат
                when (mode) {
                    0, 1 -> {
                        val name = if (Prefs(this@BatchActivity).autoName) {
                            showProgress("Придумываю название по тексту…")
                            try { Ocr.quickTitle(this@BatchActivity, files[0]) } catch (e: Throwable) { null }
                        } else null
                        val out = Files.newDoc(this@BatchActivity, name ?: "Пакет ${Files.stamp()}", "pdf")
                        withContext(Dispatchers.IO) {
                            if (mode == 1) Ocr.searchablePdf(this@BatchActivity, files, Prefs(this@BatchActivity).ocrLang, out) { n ->
                                runOnUiThread { showProgress("Распознаю страницу $n из ${files.size}…") }
                            } else PdfTools.imagesToPdf(files, out, fitA4 = false) { n ->
                                runOnUiThread { showProgress("Собираю PDF: страница $n из ${files.size}…") }
                            }
                            Files.exportCopy(this@BatchActivity, out)
                        }
                        History.add(this@BatchActivity, Kind.SCAN, out)
                        hideProgress()
                        TrDialog(this@BatchActivity).setTitle("Готово!")
                            .setMessage("«${out.name}» (${formatSize(out.length())}) сохранён в Истории.")
                            .setPositiveButton("Открыть") { _, _ -> startActivity(Intent(this@BatchActivity, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, out.absolutePath)) }
                            .setNeutralButton("Поделиться") { _, _ -> share(listOf(out), "application/pdf") }
                            .setNegativeButton("Закрыть", null).show()
                    }
                    else -> {
                        val lang = Prefs(this@BatchActivity).ocrLang
                        val enhance = Prefs(this@BatchActivity).autoEnhance
                        val parts = ArrayList<String>()
                        for ((i, f) in files.withIndex()) {
                            showProgress("Распознаю фото ${i + 1} из ${files.size}…")
                            val r = withContext(Dispatchers.Default) {
                                val bmp = ImageTools.decodeFile(f, 2600)
                                try { Ocr.recognize(this@BatchActivity, bmp, lang, enhance) } finally { bmp.recycle() }
                            }
                            parts.add(r.text)
                        }
                        val text = parts.joinToString("\n\n")
                        val base = Ocr.titleFrom(parts.firstOrNull() ?: "") ?: "Текст ${Files.stamp()}"
                        val out = Files.newDoc(this@BatchActivity, base, "txt")
                        withContext(Dispatchers.IO) { out.writeText(text) }
                        History.add(this@BatchActivity, Kind.OCR, out)
                        hideProgress()
                        // Дальше — правка, перевод и экспорт в Word/PDF на экране текста
                        startActivity(Intent(this@BatchActivity, OcrActivity::class.java).putExtra("text_file", out.absolutePath))
                    }
                }
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }
}
