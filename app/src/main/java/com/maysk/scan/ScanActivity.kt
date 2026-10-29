package com.maysk.scan

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Сканер документов: камера Google находит границы листа, выпрямляет и чистит его,
 * дальше — фильтры, поворот, сохранение в PDF/JPG, распознавание текста.
 */
class ScanActivity : BaseActivity() {

    private class Page(val original: File, var filter: Filter, var rotation: Int = 0, var result: File? = null)

    private val pages = ArrayList<Page>()
    private var current = 0
    private var applyToAll = false

    private lateinit var preview: ImageView
    private lateinit var thumbs: LinearLayout
    private lateinit var filters: LinearLayout
    private lateinit var banner: LinearLayout
    private lateinit var pageLabel: android.widget.TextView
    private var busy = false

    private val scanner = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val r = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val uris = r?.pages?.map { it.imageUri } ?: emptyList()
            addUris(uris, Filter.ORIGINAL)
        } else if (pages.isEmpty()) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = screen("Результат сканирования", scroll = false)
        addTopAction(R.drawable.ic_add) { addMore() }

        banner = hbox(12).apply { background = cardBg(16); visibility = View.GONE }
        banner.addView(iconTile(R.drawable.ic_check, R.color.tile_green, 36))
        val bt = vbox().apply { setPadding(dp(12), 0, 0, 0) }
        bt.addView(text("Готово!", 16f, bold = true))
        bt.addView(text("Документ успешно обработан", 12.5f, R.color.text2))
        banner.addView(bt)
        body.addView(banner, lp().apply { bottomMargin = dp(10) })

        val frame = FrameLayout(this).apply { background = cardBg(18) }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        frame.addView(preview, FrameLayout.LayoutParams(MATCH, MATCH))
        pageLabel = text("", 12f, R.color.white_fixed, bold = true).apply {
            background = rounded(0x99000000.toInt(), 10)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        frame.addView(pageLabel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(10), 0) })
        body.addView(frame, lp(MATCH, 0, 1f))

        // Фильтры
        val fsc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        filters = hbox()
        fsc.addView(filters)
        body.addView(fsc, lp().apply { topMargin = dp(10) })

        // Миниатюры страниц
        val tsc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        thumbs = hbox()
        tsc.addView(thumbs)
        body.addView(tsc, lp().apply { topMargin = dp(10) })

        // Действия со страницей
        body.addView(actionRow(
            actionButton(R.drawable.ic_rotate, "Повернуть") { rotateCurrent() },
            actionButton(R.drawable.ic_delete, "Удалить") { deleteCurrent() },
            actionButton(R.drawable.ic_ocr, "Текст") { toOcr() },
            actionButton(R.drawable.ic_share, "Поделиться") { shareAll() },
        ), lp().apply { topMargin = dp(10) })
        body.addView(actionRow(
            actionButton(R.drawable.ic_image, "Сохранить JPG") { saveImages() },
            actionButton(R.drawable.ic_pdf, "Сохранить PDF", highlighted = true) { savePdf() },
        ), lp().apply { topMargin = dp(8) })

        val given = intent.getStringArrayExtra("images")
        val givenUris = intent.getParcelableArrayListExtra<Uri>("uris")
        when {
            given != null -> {
                pages.addAll(given.map { Page(File(it), Filter.ORIGINAL) })
                titleView.text = "Изображение"
                refresh()
            }
            givenUris != null -> addUris(givenUris, if (Prefs(this).autoEnhance) Filter.ENHANCE else Filter.ORIGINAL)
            savedInstanceState == null -> startScanner()
        }
    }

    private fun startScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(60)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender -> scanner.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { fallback() }
    }

    /** Если сканер Google недоступен (нет сервисов Google) — снимок камерой или фото из галереи. */
    private fun fallback() {
        AlertDialog.Builder(this)
            .setTitle("Сканер")
            .setMessage("Автоматический сканер недоступен на этом телефоне. Сфотографируйте документ или выберите фото — приложение само уберёт тени и выровняет фон.")
            .setPositiveButton("Камера") { _, _ -> photo { uri -> if (uri != null) addUris(listOf(uri), Filter.ENHANCE) else if (pages.isEmpty()) finish() } }
            .setNeutralButton("Галерея") { _, _ -> pick(arrayOf("image/*"), true) { addUris(it, Filter.ENHANCE) } }
            .setNegativeButton("Отмена") { _, _ -> if (pages.isEmpty()) finish() }
            .setCancelable(false)
            .show()
    }

    private fun addMore() {
        choose("Добавить страницы", listOf("Сканировать ещё", "Сфотографировать", "Из галереи")) { i ->
            when (i) {
                0 -> startScanner()
                1 -> photo { uri -> if (uri != null) addUris(listOf(uri), Filter.ENHANCE) }
                2 -> pick(arrayOf("image/*"), true) { addUris(it, Filter.ENHANCE) }
            }
        }
    }

    private fun addUris(uris: List<Uri>, filter: Filter) {
        if (uris.isEmpty()) { if (pages.isEmpty()) finish(); return }
        lifecycleScope.launch {
            showProgress("Обработка…")
            try {
                val added = withContext(Dispatchers.IO) {
                    uris.mapIndexed { i, u ->
                        val bmp = ImageTools.decode(this@ScanActivity, u, 2600)
                        val f = File(cacheDir, "scan_${System.nanoTime()}_$i.jpg")
                        ImageTools.saveJpeg(bmp, f, 92)
                        bmp.recycle()
                        Page(f, filter)
                    }
                }
                val first = pages.size
                pages.addAll(added)
                current = first
                withContext(Dispatchers.IO) { added.forEach { process(it) } }
                hideProgress()
                banner.visibility = View.VISIBLE
                refresh()
            } catch (e: Throwable) {
                fail(e)
                if (pages.isEmpty()) finish()
            }
        }
    }

    /** Готовит итоговую картинку страницы с фильтром и поворотом. */
    private fun process(p: Page) {
        if (p.filter == Filter.ORIGINAL && p.rotation == 0) { p.result = null; return }
        val bmp = ImageTools.decodeFile(p.original, 2600)
        val rotated = ImageTools.rotate(bmp, p.rotation)
        val out = ImageTools.apply(rotated, p.filter)
        val f = File(cacheDir, "res_${System.nanoTime()}.jpg")
        ImageTools.saveJpeg(out, f, 90)
        rotated.recycle()
        out.recycle()
        p.result?.delete()
        p.result = f
    }

    private fun finalFile(p: Page): File = p.result ?: p.original

    private fun refresh() {
        if (pages.isEmpty()) { preview.setImageDrawable(null); thumbs.removeAllViews(); filters.removeAllViews(); return }
        current = current.coerceIn(0, pages.size - 1)
        val p = pages[current]
        preview.setImageBitmap(load(finalFile(p), 1600))
        pageLabel.text = "${current + 1} / ${pages.size}"

        filters.removeAllViews()
        for (f in listOf(Filter.ORIGINAL, Filter.ENHANCE, Filter.BW, Filter.CONTRAST, Filter.GRAY, Filter.PHOTO)) {
            filters.addView(chip(f.title, p.filter == f) { setFilter(f) }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        if (pages.size > 1) {
            filters.addView(chip(if (applyToAll) "✓ Ко всем" else "Ко всем страницам", applyToAll) {
                applyToAll = !applyToAll
                if (applyToAll) setFilter(p.filter) else refresh()
            })
        }

        thumbs.removeAllViews()
        pages.forEachIndexed { i, pg ->
            val box = FrameLayout(this).apply {
                background = if (i == current) rounded(col(R.color.card), 10, col(R.color.accent), 2) else rounded(col(R.color.card), 10, col(R.color.stroke))
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setOnClickListener { current = i; refresh() }
            }
            box.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(load(finalFile(pg), 200))
            }, FrameLayout.LayoutParams(dp(54), dp(72)))
            thumbs.addView(box, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
    }

    private fun load(f: File, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun setFilter(f: Filter) {
        if (busy) return
        val targets = if (applyToAll) pages.toList() else listOf(pages[current])
        targets.forEach { it.filter = f }
        rerun(targets)
    }

    private fun rotateCurrent() {
        if (busy || pages.isEmpty()) return
        val p = pages[current]
        p.rotation = (p.rotation + 90) % 360
        rerun(listOf(p))
    }

    private fun rerun(targets: List<Page>) {
        busy = true
        lifecycleScope.launch {
            if (targets.size > 1) showProgress("Применяю к ${targets.size} страницам…")
            try {
                withContext(Dispatchers.IO) { targets.forEach { process(it) } }
            } catch (e: Throwable) {
                fail(e)
            }
            hideProgress()
            busy = false
            refresh()
        }
    }

    private fun deleteCurrent() {
        if (pages.isEmpty()) return
        confirm("Удалить страницу?", "Страница ${current + 1} будет убрана из скана.", "Удалить") {
            pages.removeAt(current)
            if (pages.isEmpty()) finish() else refresh()
        }
    }

    private fun defaultName() = "Скан ${Files.stamp().replace('_', ' ')}"

    private fun savePdf() {
        if (pages.isEmpty()) return
        choose("Сохранить PDF", listOf("Обычный PDF", "PDF с текстом (можно искать и копировать)")) { mode ->
            ask("Название документа", "Название", defaultName()) { name ->
                val base = name.ifBlank { defaultName() }
                lifecycleScope.launch {
                    showProgress(if (mode == 1) "Распознаю текст…" else "Создаю PDF…")
                    try {
                        val files = pages.map { finalFile(it) }
                        val out = Files.newDoc(this@ScanActivity, base, "pdf")
                        withContext(Dispatchers.IO) {
                            if (mode == 1) {
                                Ocr.searchablePdf(this@ScanActivity, files, Prefs(this@ScanActivity).ocrLang, out) { n ->
                                    runOnUiThread { showProgress("Распознаю страницу $n из ${files.size}…") }
                                }
                            } else {
                                PdfTools.imagesToPdf(files, out, fitA4 = false)
                            }
                            Files.exportCopy(this@ScanActivity, out)
                        }
                        History.add(this@ScanActivity, Kind.SCAN, out, out.name)
                        hideProgress()
                        done(out)
                    } catch (e: Throwable) {
                        fail(e)
                    }
                }
            }
        }
    }

    private fun saveImages() {
        if (pages.isEmpty()) return
        lifecycleScope.launch {
            showProgress("Сохраняю…")
            try {
                val base = defaultName()
                val saved = withContext(Dispatchers.IO) {
                    pages.mapIndexed { i, p ->
                        val out = Files.newDoc(this@ScanActivity, if (pages.size > 1) "$base-${i + 1}" else base, "jpg")
                        finalFile(p).copyTo(out, true)
                        Files.exportCopy(this@ScanActivity, out)
                        out
                    }
                }
                saved.forEach { History.add(this@ScanActivity, Kind.IMAGE, it) }
                hideProgress()
                toast("Сохранено изображений: ${saved.size}")
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    private fun shareAll() {
        if (pages.isEmpty()) return
        choose("Поделиться", listOf("Как PDF", "Как изображения")) { i ->
            if (i == 1) { share(pages.map { finalFile(it) }, "image/jpeg"); return@choose }
            lifecycleScope.launch {
                showProgress("Создаю PDF…")
                try {
                    val out = File(cacheDir, Files.sanitize(defaultName()) + ".pdf")
                    withContext(Dispatchers.IO) { PdfTools.imagesToPdf(pages.map { finalFile(it) }, out, fitA4 = false) }
                    hideProgress()
                    share(listOf(out), "application/pdf")
                } catch (e: Throwable) {
                    fail(e)
                }
            }
        }
    }

    private fun toOcr() {
        if (pages.isEmpty()) return
        startActivity(Intent(this, OcrActivity::class.java).putExtra("images", pages.map { finalFile(it).absolutePath }.toTypedArray()))
    }

    private fun done(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Готово!")
            .setMessage("«${f.name}» сохранён (${formatSize(f.length())}). Он есть в Истории.")
            .setPositiveButton("Открыть") { _, _ -> startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, f.absolutePath)) }
            .setNeutralButton("Поделиться") { _, _ -> share(listOf(f), "application/pdf") }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
