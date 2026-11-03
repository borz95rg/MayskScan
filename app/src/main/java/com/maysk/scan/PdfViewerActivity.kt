package com.maysk.scan

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Просмотр PDF: страница целиком на экране, сверху — стрелки и все функции,
 * масштаб — жестом двумя пальцами, кнопками «+ / −», ползунком и режимами «По ширине» / «Вся страница».
 */
class PdfViewerActivity : BaseActivity() {

    private lateinit var file: File
    private var viewFile: File? = null   // расшифрованная копия, если PDF с паролем
    private var password: String? = null
    private var render: PdfRender? = null
    private lateinit var pdfView: PdfZoomView
    private lateinit var indicator: TextView
    private lateinit var zoomChip: TextView
    private lateinit var zoomPanel: View
    private lateinit var zoomSlider: SeekBar
    private lateinit var fitWidthChip: TextView
    private lateinit var fitPageChip: TextView
    private var night = false
    private var texts: List<String>? = null
    private lateinit var prefs: Prefs
    private val key get() = file.name + "_" + file.length()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        night = prefs.nightPdf
        val path = intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return }
        file = File(path)
        if (!file.exists()) { toast("Файл не найден"); finish(); return }
        // Файл не из приложения — кладём копию в документы и в Историю
        if (!file.absolutePath.startsWith(History.docsDir(this).absolutePath)) {
            val copy = Files.newDoc(this, Files.baseName(file.name), "pdf")
            file.copyTo(copy, true)
            file = copy
            History.add(this, Kind.PDF, copy)
        }

        val body = screen(file.name, scroll = false)
        body.setPadding(0, 0, 0, 0)
        // Верхняя строка плотнее: чем меньше панелей, тем больше места документу
        topBar.setPadding(dp(8), dp(2), dp(8), dp(2))
        addTopAction(R.drawable.ic_share) { share(listOf(file), "application/pdf") }
        addTopAction(R.drawable.ic_more) { more() }

        // Панель функций: стрелки страниц «‹ 1 / 12 ›», поиск, закладки, миниатюры, масштаб
        val toolbar = hbox().apply { setPadding(dp(8), 0, dp(8), dp(4)) }
        toolbar.addView(iconButton(R.drawable.ic_chevron_left) { step(-1) }, lp(dp(40), dp(40)))
        indicator = text("", 14f, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(col(R.color.card), 14, col(R.color.stroke))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { goTo() }
        }
        toolbar.addView(indicator, lp(WRAP, WRAP).apply { setMargins(dp(2), 0, dp(2), 0) })
        toolbar.addView(iconButton(R.drawable.ic_chevron) { step(1) }, lp(dp(40), dp(40)))
        toolbar.addView(View(this), lp(0, 1, 0.3f))
        toolbar.addView(iconButton(R.drawable.ic_search) { search() }, lp(0, dp(40), 1f))
        toolbar.addView(iconButton(R.drawable.ic_bookmark) { bookmarks() }, lp(0, dp(40), 1f))
        toolbar.addView(iconButton(R.drawable.ic_grid) { thumbnails() }, lp(0, dp(40), 1f))
        zoomChip = text("100%", 12.5f, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            background = clickable(rounded(col(R.color.card), 14, col(R.color.stroke)), 14)
            setPadding(dp(4), dp(7), dp(4), dp(7))
            setOnClickListener { zoomPanel.visibility = if (zoomPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        toolbar.addView(zoomChip, lp(0, WRAP, 1.2f))
        body.addView(toolbar, lp())

        // Панель масштаба (открывается по нажатию на проценты): «−», ползунок, «+», быстрые режимы
        zoomPanel = vbox(0).apply {
            visibility = View.GONE
            setPadding(dp(10), dp(4), dp(10), dp(8))
            background = rounded(col(R.color.card), 12, col(R.color.stroke))
        }.also { panel ->
            val row = hbox(0)
            row.addView(iconButton(R.drawable.ic_minus) { pdfView.setZoomLevel(pdfView.zoom / 1.2f) }, lp(dp(40), dp(40)))
            zoomSlider = SeekBar(this).apply {
                max = SLIDER_MAX
                progress = sliderFor(1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) pdfView.setZoomLevel(PdfZoomView.MIN_ZOOM + progress / 100f)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            row.addView(zoomSlider, lp(0, WRAP, 1f))
            row.addView(iconButton(R.drawable.ic_add) { pdfView.setZoomLevel(pdfView.zoom * 1.2f) }, lp(dp(40), dp(40)))
            panel.addView(row)

            val modes = hbox(0)
            fitWidthChip = text("По ширине", 13.5f).apply {
                gravity = Gravity.CENTER
                setOnClickListener { pdfView.fitWidth() }
            }
            fitPageChip = text("Вся страница", 13.5f).apply {
                gravity = Gravity.CENTER
                setOnClickListener { pdfView.fitPage() }
            }
            modes.addView(fitWidthChip, lp(0, WRAP, 1f))
            modes.addView(fitPageChip, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
            panel.addView(modes, lp().apply { topMargin = dp(2) })
        }
        styleMode(PdfZoomView.Mode.FIT_PAGE)
        body.addView(zoomPanel, lp().apply { setMargins(dp(8), 0, dp(8), dp(4)) })

        // Документ — всё оставшееся место
        pdfView = PdfZoomView(this).apply {
            setBackgroundColor(col(R.color.bg2))
            setNight(night)
            onZoomChanged = { z ->
                zoomChip.text = "${(z * 100).roundToInt()}%"
                zoomSlider.progress = sliderFor(z)
            }
            onPageChanged = { p -> indicator.text = "${p + 1} / $pageCount" }
            onModeChanged = { m -> styleMode(m) }
        }
        body.addView(pdfView, lp(MATCH, 0, 1f))
        openRenderer()
    }

    override fun onResume() {
        super.onResume()
        // Файл могли изменить инструменты — перечитываем
        if (render != null && intent.getBooleanExtra("reload", false)) reload()
    }

    override fun onPause() {
        super.onPause()
        if (::pdfView.isInitialized) prefs.setLastPage(key, pdfView.currentPage)
    }

    override fun onDestroy() {
        if (::pdfView.isInitialized) pdfView.release()   // сначала остановить отрисовку, потом закрывать файл
        render?.close()
        super.onDestroy()
    }

    /** Подсветка активного режима: «По ширине» / «Вся страница». В свободном масштабе обе кнопки обычные. */
    private fun styleMode(m: PdfZoomView.Mode) {
        fun style(chip: TextView, selected: Boolean) {
            chip.background = clickable(if (selected) accentBg(16) else rounded(col(R.color.card2), 16, col(R.color.stroke)), 16)
            chip.setTextColor(col(if (selected) R.color.white_fixed else R.color.text))
            chip.setPadding(dp(8), dp(9), dp(8), dp(9))
        }
        style(fitWidthChip, m == PdfZoomView.Mode.FIT_WIDTH)
        style(fitPageChip, m == PdfZoomView.Mode.FIT_PAGE)
    }

    private fun sliderFor(z: Float): Int = ((z - PdfZoomView.MIN_ZOOM) * 100f).roundToInt().coerceIn(0, SLIDER_MAX)

    private fun openRenderer() {
        val r = try { PdfRender.tryOpen(viewFile ?: file) } catch (e: Exception) { fail(e); return }
        if (r == null) { askPassword(); return }
        render = r
        val last = prefs.lastPage(key)
        indicator.text = "${(last + 1).coerceIn(1, r.pageCount)} / ${r.pageCount}"
        // Документ открывается страницей целиком, с места, где закончили чтение
        pdfView.open(r, lifecycleScope, if (last in 0 until r.pageCount) last else 0)
    }

    private fun reload() {
        pdfView.release()
        render?.close()
        texts = null
        openRenderer()
    }

    private fun askPassword() {
        ask("PDF защищён паролем", "Пароль", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { pw ->
            lifecycleScope.launch {
                try {
                    val tmp = Files.tempFile(this@PdfViewerActivity, "pdf")
                    withContext(Dispatchers.IO) { PdfTools.unlock(file, pw, tmp) }
                    password = pw
                    viewFile = tmp
                    openRenderer()
                } catch (e: Throwable) {
                    toast("Неверный пароль")
                    askPassword()
                }
            }
        }
    }

    /** Файл для инструментов: без пароля (если открывали с паролем). */
    private fun workFile(): File = viewFile ?: file

    private fun step(delta: Int) {
        pdfView.goToPage(pdfView.currentPage + delta)
    }

    private fun jump(index: Int) {
        pdfView.goToPage(index)
    }

    private fun goTo() {
        val r = render ?: return
        ask("Перейти к странице", "1 – ${r.pageCount}", "", InputType.TYPE_CLASS_NUMBER) { s ->
            s.toIntOrNull()?.let { jump(it - 1) }
        }
    }

    // ---------- Поиск ----------

    private fun search() {
        ask("Поиск в PDF", "Слово или фраза") { q ->
            if (q.isBlank()) return@ask
            lifecycleScope.launch {
                showProgress("Ищу…")
                try {
                    val t = texts ?: withContext(Dispatchers.IO) { PdfTools.pageTexts(workFile()) }.also { texts = it }
                    hideProgress()
                    val hits = t.mapIndexedNotNull { i, s ->
                        val n = Regex(Regex.escape(q.trim()), RegexOption.IGNORE_CASE).findAll(s).count()
                        if (n > 0) i to n else null
                    }
                    if (hits.isEmpty()) {
                        val noText = t.all { it.isBlank() }
                        TrDialog(this@PdfViewerActivity).setTitle("Поиск")
                            .setMessage(if (noText) "В этом PDF нет текста — это скан. Распознайте его: «Ещё» → «Распознать текст»." else "«$q» не найдено.")
                            .setPositiveButton("OK", null).show()
                    } else {
                        choose("Найдено на ${hits.size} стр.", hits.map { (p, n) -> "Страница ${p + 1} — совпадений: $n" }) { i -> jump(hits[i].first) }
                    }
                } catch (e: Throwable) {
                    fail(e)
                }
            }
        }
    }

    // ---------- Закладки ----------

    private fun bookmarks() {
        val cur = pdfView.currentPage
        val set = prefs.bookmarks(key).toMutableSet()
        val items = ArrayList<String>()
        items.add(if (cur in set) "★ Убрать закладку со страницы ${cur + 1}" else "☆ Добавить закладку на страницу ${cur + 1}")
        val sorted = set.sorted()
        sorted.forEach { items.add("Страница ${it + 1}") }
        choose("Закладки", items) { i ->
            if (i == 0) {
                if (cur in set) set.remove(cur) else set.add(cur)
                prefs.setBookmarks(key, set)
                toast(if (cur in set) "Закладка добавлена" else "Закладка убрана")
            } else jump(sorted[i - 1])
        }
    }

    // ---------- Миниатюры ----------

    private fun thumbnails() {
        val r = render ?: return
        val rv = RecyclerView(this)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.setPadding(dp(8), dp(8), dp(8), dp(8))
        var dialog: AlertDialog? = null
        val thumbCache = LruCache<Int, Bitmap>(40)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = r.pageCount
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val box = vbox(4).apply { gravity = Gravity.CENTER_HORIZONTAL; layoutParams = RecyclerView.LayoutParams(MATCH, WRAP) }
                box.addView(ImageView(parent.context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(0xFFFFFFFF.toInt())
                }, lp(MATCH, dp(130)))
                box.addView(text("", 12f, R.color.text2).apply { gravity = Gravity.CENTER })
                return object : RecyclerView.ViewHolder(box) {}
            }
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val box = h.itemView as ViewGroup
                val iv = box.getChildAt(0) as ImageView
                (box.getChildAt(1) as TextView).text = "${pos + 1}"
                iv.tag = pos
                box.setOnClickListener { dialog?.dismiss(); jump(pos) }
                thumbCache.get(pos)?.let { iv.setImageBitmap(it); return }
                iv.setImageBitmap(null)
                lifecycleScope.launch {
                    val b = withContext(Dispatchers.IO) { try { r.render(pos, 220) } catch (e: Throwable) { null } } ?: return@launch
                    thumbCache.put(pos, b)
                    if (iv.tag == pos) iv.setImageBitmap(b)
                }
            }
        }
        dialog = TrDialog(this).setTitle("Страницы").setView(rv).setNegativeButton("Закрыть", null).show()
    }

    // ---------- Ещё ----------

    private fun more() {
        val items = listOf(
            "Перейти к странице…",
            if (night) "Ночной режим: выключить" else "Ночной режим: включить",
            "Редактировать страницы",
            "Подпись",
            "Текст, рисунок, печать",
            "Водяной знак",
            "Сжать PDF",
            "Защитить паролем / снять пароль",
            "Конвертировать (Word, Excel, JPG…)",
            "Распознать текст (для сканов)",
            "Разделить / извлечь страницы",
            "Печать",
            "Сохранить на телефон…",
            "Заполнить форму",
        )
        choose(file.name, items) { i ->
            val f = workFile()
            when (i) {
                0 -> goTo()
                1 -> { night = !night; prefs.nightPdf = night; pdfView.setNight(night) }
                2 -> openTool(PdfPagesActivity::class.java, f)
                3 -> openTool(PdfAnnotateActivity::class.java, f, "sign")
                4 -> openTool(PdfAnnotateActivity::class.java, f, "draw")
                5 -> openTool(PdfAnnotateActivity::class.java, f, "watermark")
                6 -> openTool(CompressActivity::class.java, f)
                7 -> startActivity(Intent(this, ProtectActivity::class.java).putExtra(EXTRA_FILE, file.absolutePath))
                8 -> startActivity(Intent(this, ConvertActivity::class.java).putExtra(EXTRA_FILE, f.absolutePath))
                9 -> ocrPages()
                10 -> startActivity(Intent(this, MergeSplitActivity::class.java).putExtra("mode", "split").putExtra(EXTRA_FILE, f.absolutePath))
                11 -> Printer.print(this, f)
                12 -> saveAs(file, "application/pdf")
                13 -> openTool(FormActivity::class.java, f)
            }
        }
    }

    private fun openTool(cls: Class<*>, f: File, mode: String? = null) {
        startActivity(Intent(this, cls).putExtra(EXTRA_FILE, f.absolutePath).apply { if (mode != null) putExtra("mode", mode) })
    }

    /** Скан в PDF → картинки страниц → распознавание текста. */
    private fun ocrPages() {
        val r = render ?: return
        val count = r.pageCount
        val doIt = { pages: List<Int> ->
            lifecycleScope.launch {
                showProgress("Готовлю страницы…")
                try {
                    val files = withContext(Dispatchers.IO) {
                        pages.map { p ->
                            val bmp = r.render(p, 2000)
                            val f = File(cacheDir, "pdfocr_${System.nanoTime()}_$p.jpg")
                            ImageTools.saveJpeg(bmp, f, 92)
                            bmp.recycle()
                            f
                        }
                    }
                    hideProgress()
                    startActivity(Intent(this@PdfViewerActivity, OcrActivity::class.java).putExtra("images", files.map { it.absolutePath }.toTypedArray()))
                } catch (e: Throwable) {
                    fail(e)
                }
            }
        }
        if (count <= 10) { doIt((0 until count).toList()); return }
        val cur = pdfView.currentPage
        choose("Какие страницы распознать?", listOf("Текущую (${cur + 1})", "Первые 10", "Указать номера…")) { i ->
            when (i) {
                0 -> doIt(listOf(cur))
                1 -> doIt((0 until 10).toList())
                2 -> ask("Страницы", "например: 1-3, 5") { s ->
                    val pages = PdfTools.parseRanges(s, count).flatMap { it.toList() }.distinct().take(40)
                    if (pages.isNotEmpty()) doIt(pages)
                }
            }
        }
    }

    private companion object {
        /** Ползунок: от 30% (MIN_ZOOM) до 400% (MAX_ZOOM) с шагом 1%. */
        const val SLIDER_MAX = 370
    }
}
