package com.maysk.scan

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Конвертер: из PDF в Word/Excel/PowerPoint/картинки/текст и обратно — всё на телефоне. */
class ConvertActivity : BaseActivity() {

    private var given: File? = null

    private enum class Op(val title: String, val icon: Int, val color: Int) {
        PDF_DOCX("Word", R.drawable.ic_doc, R.color.tile_blue),
        PDF_XLSX("Excel", R.drawable.ic_grid, R.color.tile_green),
        PDF_PPTX("PPTX", R.drawable.ic_image, R.color.tile_red),
        PDF_JPG("JPG", R.drawable.ic_image, R.color.tile_purple),
        PDF_PNG("PNG", R.drawable.ic_image, R.color.tile_blue),
        PDF_WEBP("WEBP", R.drawable.ic_image, R.color.tile_teal),
        PDF_TXT("TXT", R.drawable.ic_ocr, R.color.tile_orange),
        DOCX_PDF("Word", R.drawable.ic_doc, R.color.tile_blue),
        XLSX_PDF("Excel", R.drawable.ic_grid, R.color.tile_green),
        IMG_PDF("JPG / PNG", R.drawable.ic_image, R.color.tile_red),
        TXT_PDF("TXT", R.drawable.ic_ocr, R.color.tile_orange),
        IMG_DOCX("Фото → Word", R.drawable.ic_doc, R.color.tile_blue),
        IMG_TXT("Фото → Текст", R.drawable.ic_ocr, R.color.tile_teal),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        given = intent.getStringExtra(EXTRA_FILE)?.let { File(it) }
        val body = screen("Конвертация")

        body.addView(sectionTitle("ИЗ PDF"))
        addGrid(body, listOf(Op.PDF_DOCX, Op.PDF_XLSX, Op.PDF_PPTX, Op.PDF_JPG, Op.PDF_PNG, Op.PDF_TXT, Op.PDF_WEBP))
        if (given == null) {
            body.addView(sectionTitle("В PDF"))
            addGrid(body, listOf(Op.DOCX_PDF, Op.XLSX_PDF, Op.IMG_PDF, Op.TXT_PDF))
            body.addView(sectionTitle("ИЗ ФОТО"))
            addGrid(body, listOf(Op.IMG_DOCX, Op.IMG_TXT))
        } else {
            body.addView(text("Файл: ${given!!.name}", 13f, R.color.text2).apply { setPadding(dp(4), dp(14), 0, 0) })
        }
        body.addView(text("Word и Excel переводятся в PDF с простой вёрсткой: текст, заголовки и таблицы. Картинки внутри документов Word пока не переносятся.", 12f, R.color.text2).apply {
            setPadding(dp(4), dp(18), dp(4), 0)
        })
        if (intent.getStringExtra("preset") == "img2pdf") run(Op.IMG_PDF)
    }

    private fun addGrid(body: LinearLayout, ops: List<Op>) {
        ops.chunked(3).forEach { chunk ->
            val row = hbox()
            chunk.forEachIndexed { i, op ->
                val card = vbox(12).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    background = clickable(cardBg(18), 18)
                    setOnClickListener { run(op) }
                }
                card.addView(iconTile(op.icon, op.color, 44))
                card.addView(text(op.title, 13f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
                row.addView(card, lp(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(10) })
            }
            repeat(3 - chunk.size) { row.addView(View(this), lp(0, 1, 1f).apply { leftMargin = dp(10) }) }
            body.addView(row, lp().apply { bottomMargin = dp(10) })
        }
    }

    private fun run(op: Op) {
        when (op) {
            Op.PDF_DOCX, Op.PDF_XLSX, Op.PDF_PPTX, Op.PDF_JPG, Op.PDF_PNG, Op.PDF_WEBP, Op.PDF_TXT -> {
                val g = given
                if (g != null) fromPdf(op, g) else pickCopy(arrayOf("application/pdf")) { fromPdf(op, it[0]) }
            }
            Op.DOCX_PDF -> pickCopy(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword")) { files ->
                job("Создаю PDF…") {
                    val f = files[0]
                    if (!f.name.endsWith(".docx", true)) throw IllegalStateException("Поддерживается формат .docx (Word 2007 и новее). Старый .doc откройте в Word и сохраните как .docx.")
                    val out = Files.newDoc(this, Files.baseName(f.name), "pdf")
                    TextPdf.fromDocx(OfficeReader.docx(f), out)
                }
            }
            Op.XLSX_PDF -> pickCopy(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")) { files ->
                job("Создаю PDF…") {
                    val f = files[0]
                    if (!f.name.endsWith(".xlsx", true)) throw IllegalStateException("Поддерживается формат .xlsx (Excel 2007 и новее).")
                    val out = Files.newDoc(this, Files.baseName(f.name), "pdf")
                    TextPdf.fromSheets(OfficeReader.xlsx(f), out)
                }
            }
            Op.IMG_PDF -> pickCopy(arrayOf("image/*"), multiple = true) { files ->
                job("Создаю PDF…") {
                    val out = Files.newDoc(this, if (files.size == 1) Files.baseName(files[0].name) else "Фото ${Files.stamp()}", "pdf")
                    PdfTools.imagesToPdf(files, out, fitA4 = true)
                    out
                }
            }
            Op.TXT_PDF -> pickCopy(arrayOf("text/plain")) { files ->
                job("Создаю PDF…") {
                    val out = Files.newDoc(this, Files.baseName(files[0].name), "pdf")
                    TextPdf.fromText(files[0].readText(), out)
                }
            }
            Op.IMG_DOCX -> pickCopy(arrayOf("image/*"), multiple = true) { files ->
                job("Распознаю текст…") {
                    val text = ocrFiles(files)
                    val out = Files.newDoc(this, "Документ ${Files.stamp()}", "docx")
                    out.outputStream().use { OfficeWriter.docx(text, it) }
                    out
                }
            }
            Op.IMG_TXT -> startActivity(Intent(this, OcrActivity::class.java))
        }
    }

    private fun pickCopy(mimes: Array<String>, multiple: Boolean = false, cb: (List<File>) -> Unit) {
        pick(mimes, multiple) { uris ->
            lifecycleScope.launch {
                try {
                    val files = withContext(Dispatchers.IO) { uris.map { Files.copyToCache(this@ConvertActivity, it) } }
                    cb(files)
                } catch (e: Throwable) { fail(e) }
            }
        }
    }

    /** Запускает конвертацию в фоне, сохраняет результат в Историю и показывает итог. */
    private fun job(msg: String, work: suspend () -> File) {
        lifecycleScope.launch {
            showProgress(msg)
            try {
                val out = withContext(Dispatchers.IO) { work().also { Files.exportCopy(this@ConvertActivity, it) } }
                History.add(this@ConvertActivity, if (out.name.endsWith(".pdf")) Kind.PDF else Kind.CONVERT, out)
                hideProgress()
                done(out)
            } catch (e: Throwable) { fail(e) }
        }
    }

    private fun done(out: File) {
        if (out.name.endsWith(".zip") && lastImages.isNotEmpty()) {
            val imgs = lastImages
            lastImages = emptyList()
            TrDialog(this).setTitle("Готово")
                .setMessage("Страниц: ${imgs.size}. Картинки сохранены одним ZIP-архивом в Истории.")
                .setPositiveButton("Отправить картинки") { _, _ -> share(imgs, Files.mimeOf(imgs[0].name)) }
                .setNeutralButton("Отправить ZIP") { _, _ -> share(listOf(out), "application/zip") }
                .setNegativeButton("На телефон…") { _, _ -> saveAs(out, "application/zip") }
                .show()
            return
        }
        val b = TrDialog(this).setTitle("Готово")
            .setMessage("«${out.name}» (${formatSize(out.length())}) сохранён в Истории.")
            .setPositiveButton("Открыть") { _, _ ->
                if (out.name.endsWith(".pdf")) startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, out.absolutePath))
                else Files.openWith(this, out)
            }
            .setNeutralButton("Поделиться") { _, _ -> share(listOf(out), Files.mimeOf(out.name)) }
            .setNegativeButton("На телефон…") { _, _ -> saveAs(out, Files.mimeOf(out.name)) }
        b.show()
    }

    private suspend fun ocrFiles(files: List<File>): String {
        val prefs = Prefs(this)
        val parts = ArrayList<String>()
        for ((i, f) in files.withIndex()) {
            runOnUiThread { showProgress("Распознаю ${i + 1} из ${files.size}…") }
            val bmp = ImageTools.decodeFile(f, 2600)
            parts.add(Ocr.recognize(this, bmp, prefs.ocrLang, prefs.autoEnhance).text)
            bmp.recycle()
        }
        return parts.joinToString(OfficeWriter.PAGE_BREAK.toString())
    }

    /** Текст PDF по страницам; если это скан — распознаём. */
    private suspend fun pdfText(src: File, render: PdfRender): String {
        val texts = PdfTools.pageTexts(src)
        val empty = texts.count { it.isBlank() }
        if (empty <= texts.size / 2) return texts.joinToString(OfficeWriter.PAGE_BREAK.toString()) { it.trim() }
        // Скан — распознаём страницы (до 30)
        val prefs = Prefs(this)
        val parts = ArrayList<String>()
        val n = minOf(render.pageCount, 30)
        for (i in 0 until n) {
            runOnUiThread { showProgress("Это скан — распознаю страницу ${i + 1} из $n…") }
            val bmp = render.render(i, 2000)
            parts.add(Ocr.recognize(this, bmp, prefs.ocrLang, prefs.autoEnhance).text)
            bmp.recycle()
        }
        return parts.joinToString(OfficeWriter.PAGE_BREAK.toString())
    }

    private fun fromPdf(op: Op, src: File) {
        val base = Files.baseName(src.name)
        job("Конвертирую…") {
            val render = PdfRender.tryOpen(src) ?: throw IllegalStateException("PDF защищён паролем. Сначала снимите пароль: «Защита» → «Снять пароль».")
            try {
                when (op) {
                    Op.PDF_DOCX -> {
                        val out = Files.newDoc(this, base, "docx")
                        val text = pdfText(src, render)
                        out.outputStream().use { OfficeWriter.docx(text, it) }
                        out
                    }
                    Op.PDF_XLSX -> {
                        val out = Files.newDoc(this, base, "xlsx")
                        val pages = pdfText(src, render).split(OfficeWriter.PAGE_BREAK)
                        val sheets = if (pages.size <= 20) pages.mapIndexed { i, t -> "Стр. ${i + 1}" to OfficeWriter.textToRows(t) }
                        else listOf(base to OfficeWriter.textToRows(pages.joinToString("\n")))
                        out.outputStream().use { OfficeWriter.xlsx(sheets, it) }
                        out
                    }
                    Op.PDF_PPTX -> {
                        val out = Files.newDoc(this, base, "pptx")
                        val slides = ArrayList<OfficeWriter.Slide>()
                        for (i in 0 until render.pageCount) {
                            runOnUiThread { showProgress("Слайд ${i + 1} из ${render.pageCount}…") }
                            val bmp = render.render(i, 1600)
                            val bos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                            slides.add(OfficeWriter.Slide(bos.toByteArray(), bmp.width, bmp.height))
                            bmp.recycle()
                        }
                        out.outputStream().use { OfficeWriter.pptx(slides, it) }
                        out
                    }
                    Op.PDF_TXT -> {
                        val out = Files.newDoc(this, base, "txt")
                        out.writeText(pdfText(src, render).replace(OfficeWriter.PAGE_BREAK.toString(), "\n\n"))
                        out
                    }
                    else -> {
                        val ext = when (op) { Op.PDF_PNG -> "png"; Op.PDF_WEBP -> "webp"; else -> "jpg" }
                        val images = ArrayList<File>()
                        val dir = File(cacheDir, "pages_${System.nanoTime()}").apply { mkdirs() }
                        for (i in 0 until render.pageCount) {
                            runOnUiThread { showProgress("Страница ${i + 1} из ${render.pageCount}…") }
                            val bmp = render.render(i, 1654)
                            val f = File(dir, "${Files.sanitize(base)}-${i + 1}.$ext")
                            when (ext) {
                                "png" -> ImageTools.savePng(bmp, f)
                                "webp" -> ImageTools.saveWebp(bmp, f, 90)
                                else -> ImageTools.saveJpeg(bmp, f, 90)
                            }
                            bmp.recycle()
                            images.add(f)
                        }
                        if (images.size == 1) {
                            val out = Files.newDoc(this, base, ext)
                            images[0].copyTo(out, true)
                            out
                        } else {
                            // Много страниц — один ZIP-архив
                            val out = Files.newDoc(this, "$base ($ext)", "zip")
                            ZipOutputStream(out.outputStream()).use { z ->
                                images.forEach { f ->
                                    z.putNextEntry(ZipEntry(f.name))
                                    f.inputStream().use { it.copyTo(z) }
                                    z.closeEntry()
                                }
                            }
                            lastImages = images
                            out
                        }
                    }
                }
            } finally {
                render.close()
            }
        }
    }

    @Volatile
    private var lastImages: List<File> = emptyList()
}
