package com.maysk.scan

import android.content.Intent
import android.graphics.Bitmap
import android.view.ScaleGestureDetector
import android.widget.SeekBar
import android.os.Bundle
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Просмотр PDF: страницы, поиск, закладки, миниатюры, ночной режим, все инструменты. */
class PdfViewerActivity : BaseActivity() {

    private lateinit var file: File
    private var viewFile: File? = null   // расшифрованная копия, если PDF с паролем
    private var password: String? = null
    private var render: PdfRender? = null
    private lateinit var list: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var indicator: TextView
    private val cache = object : LruCache<Int, Bitmap>(6) {}
    private var night = false
    private var zoom = 1.0f
    private lateinit var zoomLabel: TextView
    private lateinit var zoomSlider: SeekBar
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
        addTopAction(R.drawable.ic_share) { share(listOf(file), "application/pdf") }
        addTopAction(R.drawable.ic_more) { more() }

        // Переключатель страниц «‹ 1 / 12 ›»
        val pager = hbox().apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6)) }
        pager.addView(iconButton(R.drawable.ic_back) { jump(lm.findFirstVisibleItemPosition() - 1) })
        indicator = text("", 14f, bold = true).apply {
            background = rounded(col(R.color.card), 14, col(R.color.stroke))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { goTo() }
        }
        pager.addView(indicator)
        pager.addView(iconButton(R.drawable.ic_chevron) { jump(lm.findFirstVisibleItemPosition() + 1) })
        body.addView(pager)

        list = RecyclerView(this).apply {
            setBackgroundColor(col(R.color.bg2))
            clipChildren = false
            clipToPadding = false
        }
        lm = LinearLayoutManager(this)
        list.layoutManager = lm
        body.addView(list, lp(MATCH, 0, 1f))

        // Масштаб: жест двумя пальцами, ползунок и быстрые режимы просмотра.
        val zoomPanel = vbox(4).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(col(R.color.card), 12, col(R.color.stroke))
        }
        val zoomTop = hbox(8).apply { gravity = Gravity.CENTER_VERTICAL }
        zoomTop.addView(text("Масштаб", 12f, R.color.text2))
        zoomLabel = text("100%", 12f, bold = true)
        zoomTop.addView(zoomLabel)
        zoomTop.addView(View(this), lp(0, 1, 1f))
        zoomTop.addView(actionButton(R.drawable.ic_undo, "−") { setZoom(zoom - 0.1f) }, lp(WRAP, WRAP))
        zoomTop.addView(actionButton(R.drawable.ic_add, "+") { setZoom(zoom + 0.1f) }, lp(WRAP, WRAP))
        zoomPanel.addView(zoomTop)
        zoomSlider = SeekBar(this).apply {
            max = 275
            progress = 25 // 100% (диапазон 75–350%)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) setZoom(0.75f + progress / 100f, updateSlider = false)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        zoomPanel.addView(zoomSlider, lp(MATCH, WRAP))
        val modes = hbox(8)
        modes.addView(actionButton(R.drawable.ic_pdf, "По ширине") { setZoom(1f) }, lp(0, WRAP, 1f))
        modes.addView(actionButton(R.drawable.ic_doc, "Вся страница") { setZoom(0.85f) }, lp(0, WRAP, 1f))
        zoomPanel.addView(modes)
        body.addView(zoomPanel)

        val bottom = hbox(8)
        bottom.addView(actionButton(R.drawable.ic_search, "Поиск") { search() }, lp(0, WRAP, 1f))
        bottom.addView(actionButton(R.drawable.ic_bookmark, "Закладки") { bookmarks() }, lp(0, WRAP, 1f).apply { leftMargin = dp(6) })
        bottom.addView(actionButton(R.drawable.ic_grid, "Миниатюры") { thumbnails() }, lp(0, WRAP, 1f).apply { leftMargin = dp(6) })
        bottom.addView(actionButton(R.drawable.ic_more, "Ещё") { more() }, lp(0, WRAP, 1f).apply { leftMargin = dp(6) })
        body.addView(bottom)

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateIndicator()
        })
        openRenderer()
    }

    override fun onResume() {
        super.onResume()
        // Файл могли изменить инструменты — перечитываем
        if (render != null && intent.getBooleanExtra("reload", false)) reload()
    }

    override fun onPause() {
        super.onPause()
        if (::lm.isInitialized) prefs.setLastPage(key, lm.findFirstVisibleItemPosition().coerceAtLeast(0))
    }

    override fun onDestroy() {
        render?.close()
        cache.evictAll()
        super.onDestroy()
    }

    private fun setZoom(value: Float, updateSlider: Boolean = true) {
        zoom = value.coerceIn(0.75f, 3.5f)
        zoomLabel.text = "${(zoom * 100).toInt()}%"
        if (updateSlider && ::zoomSlider.isInitialized) zoomSlider.progress = ((zoom - 0.75f) * 100).toInt().coerceIn(0, 275)
        if (::list.isInitialized) {
            for (i in 0 until list.childCount) {
                val frame = list.getChildAt(i) as? FrameLayout ?: continue
                frame.getChildAt(0)?.let { page -> page.scaleX = zoom; page.scaleY = zoom }
            }
        }
    }

    private fun openRenderer() {
        val r = try { PdfRender.tryOpen(viewFile ?: file) } catch (e: Exception) { fail(e); return }
        if (r == null) { askPassword(); return }
        render = r
        list.adapter = PageAdapter()
        val last = prefs.lastPage(key)
        if (last > 0 && last < r.pageCount) lm.scrollToPositionWithOffset(last, 0)
        updateIndicator()
    }

    private fun reload() {
        render?.close()
        cache.evictAll()
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

    private fun updateIndicator() {
        val r = render ?: return
        val p = (lm.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: 0) + 1
        indicator.text = "$p / ${r.pageCount}"
    }

    private fun jump(index: Int) {
        val r = render ?: return
        lm.scrollToPositionWithOffset(index.coerceIn(0, r.pageCount - 1), 0)
        list.post { updateIndicator() }
    }

    private fun goTo() {
        val r = render ?: return
        ask("Перейти к странице", "1 – ${r.pageCount}", "", InputType.TYPE_CLASS_NUMBER) { s ->
            s.toIntOrNull()?.let { jump(it - 1) }
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = render?.pageCount ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val frame = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), dp(6), dp(10), dp(6)) }
                setBackgroundColor(if (night) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt())
                elevation = dpf(2f)
                clipChildren = false
            }
            val iv = ImageView(parent.context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
            frame.addView(iv, FrameLayout.LayoutParams(MATCH, WRAP))
            val detector = ScaleGestureDetector(parent.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    setZoom(zoom * detector.scaleFactor)
                    return true
                }
            })
            frame.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                event.pointerCount > 1 || detector.isInProgress
            }
            return object : RecyclerView.ViewHolder(frame) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val frame = holder.itemView as FrameLayout
            val iv = frame.getChildAt(0) as ImageView
            iv.tag = position
            iv.scaleX = zoom
            iv.scaleY = zoom
            val r = render ?: return
            val width = list.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val (pw, ph) = try { r.pageSize(position) } catch (e: Exception) { 1 to 1 }
            iv.minimumHeight = (width * ph / pw.toFloat()).toInt()
            val cached = cache.get(position)
            if (cached != null) { iv.setImageBitmap(cached); return }
            iv.setImageBitmap(null)
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try { r.render(position, (width * 1.3f).toInt().coerceAtMost(2000), night) } catch (e: Throwable) { null }
                } ?: return@launch
                cache.put(position, bmp)
                if (iv.tag == position) iv.setImageBitmap(bmp)
            }
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
        val cur = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
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
                1 -> { night = !night; prefs.nightPdf = night; cache.evictAll(); list.adapter = PageAdapter(); jump(lm.findFirstVisibleItemPosition()) }
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
        val cur = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
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
}
