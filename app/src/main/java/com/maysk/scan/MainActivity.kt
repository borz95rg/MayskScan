package com.maysk.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/** Главный экран с нижним меню: Главная · Документы · История · Настройки. */
class MainActivity : BaseActivity() {

    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private var tab = 0
    private var historyQuery = ""
    private var historyFilter: Kind? = null
    private var historyFavs = false
    private var docsQuery = ""
    private var docsFilter = "all"
    private var update: Updater.Info? = null

    private val lockSetup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { render() }
    private val lockVerify = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) { Prefs(this).pinHash = ""; toast("Блокировка выключена") }
        render()
    }
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val prefs = Prefs(this)
            prefs.exportTree = uri.toString()
            prefs.exportTreeName = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.name ?: "Выбранная папка"
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: 0
        val root = vbox()
        content = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, 1f))
        nav = hbox().apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundColor(col(R.color.bg2))
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(View(this).apply { setBackgroundColor(col(R.color.stroke)) }, lp(MATCH, dp(1)))
        root.addView(nav, lp())
        setContentView(root)
        buildNav()
        render()
        lifecycleScope.launch {
            update = Updater.check()
            update?.let { u ->
                if (tab == 0) render()
                if (!Updater.dialogShown && !isFinishing) {
                    Updater.dialogShown = true
                    TrDialog(this@MainActivity)
                        .setTitle("Доступно обновление ${u.versionName}")
                        .setMessage("Вышла новая версия Maysk Scan. Нажмите «Обновить» — файл скачается, затем откройте его и нажмите «Установить». Все ваши документы сохранятся.")
                        .setPositiveButton("Обновить") { _, _ -> openUpdate(u) }
                        .setNegativeButton("Позже", null)
                        .show()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0) { tab = 0; buildNav(); render() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun openUpdate(u: Updater.Info) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url)))
            toast("Скачивается версия ${u.versionName}. Откройте файл и нажмите «Установить»")
        } catch (e: Exception) {
            toast("Нет браузера для скачивания")
        }
    }

    private fun buildNav() {
        nav.removeAllViews()
        val items = listOf(
            Triple(R.drawable.ic_home, "Главная", 0),
            Triple(R.drawable.ic_folder, "Документы", 2),
            Triple(R.drawable.ic_history, "История", 1),
            Triple(R.drawable.ic_settings, "Настройки", 3),
        )
        for ((icon, label, i) in items) {
            val sel = i == tab
            val box = vbox().apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(4))
                background = clickable(android.graphics.drawable.ColorDrawable(0), 16)
                setOnClickListener { tab = i; buildNav(); render() }
            }
            // Одинаковая «капсула» у всех пунктов, у выбранного — с подсветкой: ничего не съезжает
            val wrap = FrameLayout(this).apply {
                if (sel) background = rounded(Accent.soft(this@MainActivity), 14)
                setPadding(dp(16), dp(3), dp(16), dp(3))
            }
            wrap.addView(icon(icon, 24, if (sel) R.color.accent else R.color.text2), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            box.addView(wrap, lp(WRAP, WRAP))
            box.addView(text(label, 11.5f, if (sel) R.color.accent else R.color.text2, bold = sel).apply {
                setPadding(0, dp(3), 0, 0); maxLines = 1; gravity = Gravity.CENTER
            }, lp(WRAP, WRAP))
            nav.addView(box, lp(0, WRAP, 1f))
        }
    }

    private fun render() {
        content.removeAllViews()
        val sv = ScrollView(this).apply { isFillViewport = true }
        val col = vbox(16)
        sv.addView(col)
        content.addView(sv)
        when (tab) {
            0 -> home(col)
            1 -> history(col, favoritesOnly = false)
            2 -> documents(col)
            3 -> settings(col)
        }
        if (tab == 2) {
            content.addView(createButton(), FrameLayout.LayoutParams(dp(58), dp(58), Gravity.END or Gravity.BOTTOM).apply {
                setMargins(0, 0, dp(18), dp(18))
            })
        }
    }

    // ---------- Главная ----------

    private fun header(col: LinearLayout, title: String) {
        val row = hbox().apply { setPadding(dp(2), dp(4), 0, dp(12)) }
        row.addView(ImageView(this).apply { setImageResource(R.drawable.wolf) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val t = hbox().apply { setPadding(dp(10), 0, 0, 0) }
        if (title == "Maysk Scan") {
            t.addView(text("Maysk ", 22f, R.color.text, heavy = true))
            t.addView(text("Scan", 22f, R.color.accent, heavy = true))
        } else t.addView(text(title, 22f, R.color.text, heavy = true))
        row.addView(t, lp(0, WRAP, 1f))
        col.addView(row)
    }

    private fun home(col: LinearLayout) {
        header(col, "Maysk Scan")

        update?.let { u ->
            val card = hbox(14).apply { background = cardBg(20, glow = true) }
            card.addView(iconTile(R.drawable.ic_save, R.color.tile_green, 42))
            val t = vbox().apply { setPadding(dp(12), 0, dp(8), 0) }
            t.addView(text("Доступно обновление ${u.versionName}", 15f, bold = true))
            t.addView(text("Нажмите, чтобы скачать", 12.5f, R.color.text2))
            card.addView(t, lp(0, WRAP, 1f))
            card.setOnClickListener { openUpdate(u) }
            col.addView(card, lp().apply { bottomMargin = dp(14) })
        }

        // Быстрый старт
        val quick = vbox(18).apply { background = cardBg(22, glow = true) }
        val qr = hbox()
        qr.addView(iconTile(R.drawable.ic_scan, R.color.tile_blue, 52))
        val qt = vbox().apply { setPadding(dp(14), 0, 0, 0) }
        qt.addView(text("Быстрый старт", 18f, bold = true))
        qt.addView(text("Сканируйте документы, извлекайте текст и работайте с PDF — без интернета.", 13f, R.color.text2))
        qr.addView(qt, lp(0, WRAP, 1f))
        quick.addView(qr)
        quick.addView(primaryButton("Начать", R.drawable.ic_camera) { startActivity(Intent(this, ScanActivity::class.java)) }, lp().apply { topMargin = dp(16) })
        col.addView(quick)

        // Четыре главные плитки
        val grid = listOf(
            Tile(R.drawable.ic_scan, R.color.tile_blue, "Сканер документов", "PDF, JPG, PNG") { startActivity(Intent(this, ScanActivity::class.java)) },
            Tile(R.drawable.ic_ocr, R.color.tile_green, "Фото → Текст", "OCR, 8 языков") { startActivity(Intent(this, OcrActivity::class.java)) },
            Tile(R.drawable.ic_pdf, R.color.tile_red, "Открыть PDF", "Просмотр и редактирование") { openPdf() },
            Tile(R.drawable.ic_qr, R.color.tile_purple, "QR / Штрихкод", "Сканировать и создать") { startActivity(Intent(this, QrActivity::class.java)) },
            Tile(R.drawable.ic_doc, R.color.tile_teal, "Удостоверение / паспорт", "Обе стороны на A4") { startActivity(Intent(this, IdCardActivity::class.java)) },
            Tile(R.drawable.ic_edit, R.color.tile_orange, "Заполнить форму", "PDF-бланки") { tool(FormActivity::class.java) },
            Tile(R.drawable.ic_grid, R.color.tile_blue, "Пакетная обработка", "Много фото → один PDF") { startActivity(Intent(this, BatchActivity::class.java)) },
            Tile(R.drawable.ic_mic, R.color.tile_red, "Голос → Текст", "Диктовка в документ") { startActivity(Intent(this, VoiceActivity::class.java)) },
            Tile(R.drawable.ic_doc, R.color.tile_teal, "Документы", "Word, Excel, PowerPoint") { tab = 2; buildNav(); render() },
        )
        addTiles(col, grid, 2, big = true)

        col.addView(sectionTitle("ИНСТРУМЕНТЫ PDF"))
        val tools = listOf(
            Tile(R.drawable.ic_grid, R.color.tile_blue, "Страницы", null) { tool(PdfPagesActivity::class.java) },
            Tile(R.drawable.ic_convert, R.color.tile_teal, "Конвертация", null) { startActivity(Intent(this, ConvertActivity::class.java)) },
            Tile(R.drawable.ic_compress, R.color.tile_orange, "Сжатие", null) { tool(CompressActivity::class.java) },
            Tile(R.drawable.ic_merge, R.color.tile_purple, "Объединить", null) { startActivity(Intent(this, MergeSplitActivity::class.java).putExtra("mode", "merge")) },
            Tile(R.drawable.ic_split, R.color.tile_green, "Разделить", null) { startActivity(Intent(this, MergeSplitActivity::class.java).putExtra("mode", "split")) },
            Tile(R.drawable.ic_extract, R.color.tile_teal, "Извлечь", null) { startActivity(Intent(this, MergeSplitActivity::class.java).putExtra("mode", "extract")) },
            Tile(R.drawable.ic_sign, R.color.tile_red, "Подпись", null) { tool(PdfAnnotateActivity::class.java, "sign") },
            Tile(R.drawable.ic_edit, R.color.tile_blue, "Текст и рисунок", null) { tool(PdfAnnotateActivity::class.java, "draw") },
            Tile(R.drawable.ic_drop, R.color.tile_orange, "Водяной знак", null) { tool(PdfAnnotateActivity::class.java, "watermark") },
            Tile(R.drawable.ic_lock, R.color.tile_purple, "Защита", null) { tool(ProtectActivity::class.java) },
            Tile(R.drawable.ic_image, R.color.tile_green, "Фото → PDF", null) { startActivity(Intent(this, ConvertActivity::class.java).putExtra("preset", "img2pdf")) },
            Tile(R.drawable.ic_print, R.color.tile_red, "Печать", null) { pickPdf { Printer.print(this, it) } },
        )
        addTiles(col, tools, 3, big = false)

        val recent = History.all(this).take(4)
        if (recent.isNotEmpty()) {
            val head = hbox()
            head.addView(sectionTitle("НЕДАВНИЕ"), lp(0, WRAP, 1f))
            head.addView(text("Все", 13f, R.color.accent, bold = true).apply {
                setPadding(dp(8), dp(18), dp(4), dp(8))
                setOnClickListener { tab = 1; buildNav(); render() }
            })
            col.addView(head)
            recent.forEach { col.addView(entryRow(it), lp().apply { bottomMargin = dp(8) }) }
        }
        col.addView(text("Работает без интернета · данные остаются на телефоне", 12f, R.color.text2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(4))
        })
    }

    private class Tile(val icon: Int, val color: Int, val title: String, val sub: String?, val action: () -> Unit)

    private fun addTiles(col: LinearLayout, tiles: List<Tile>, perRow: Int, big: Boolean) {
        tiles.chunked(perRow).forEach { chunk ->
            val row = hbox().apply { gravity = Gravity.TOP }
            chunk.forEachIndexed { i, t ->
                val card = vbox(if (big) 16 else 12).apply {
                    background = clickable(cardBg(20), 20)
                    setOnClickListener { t.action() }
                    if (!big) gravity = Gravity.CENTER_HORIZONTAL
                }
                card.addView(iconTile(t.icon, t.color, if (big) 46 else 40))
                card.addView(text(t.title, if (big) 15f else 12.5f, bold = true).apply {
                    setPadding(0, dp(10), 0, 0)
                    if (!big) gravity = Gravity.CENTER
                    maxLines = 2
                })
                if (t.sub != null) card.addView(text(t.sub, 12f, R.color.text2).apply { setPadding(0, dp(2), 0, 0) })
                row.addView(card, lp(0, WRAP, 1f).apply {
                    if (i > 0) leftMargin = dp(10)
                })
            }
            repeat(perRow - chunk.size) { row.addView(View(this), lp(0, 1, 1f).apply { leftMargin = dp(10) }) }
            col.addView(row, lp().apply { topMargin = dp(10) })
        }
    }

    private fun openPdf() = pickPdf { f -> startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, f.absolutePath)) }

    private fun pickPdf(cb: (File) -> Unit) {
        pick(arrayOf("application/pdf")) { uris ->
            try {
                val f = Files.copyToCache(this, uris[0])
                cb(f)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun tool(cls: Class<*>, mode: String? = null) {
        // Сначала можно выбрать PDF из истории или с телефона
        val pdfs = History.all(this).filter { it.file.name.endsWith(".pdf", true) }.take(6)
        val open: (File) -> Unit = { f ->
            startActivity(Intent(this, cls).putExtra(EXTRA_FILE, f.absolutePath).apply { if (mode != null) putExtra("mode", mode) })
        }
        if (pdfs.isEmpty()) { pickPdf(open); return }
        val items = listOf("Выбрать PDF на телефоне…") + pdfs.map { it.name }
        choose("Какой PDF?", items) { i -> if (i == 0) pickPdf(open) else open(pdfs[i - 1].file) }
    }

    // ---------- История и избранное ----------

    private fun history(col: LinearLayout, favoritesOnly: Boolean) {
        header(col, if (favoritesOnly) "Избранное" else "История")
        val search = field("Поиск по названию и тексту внутри…", historyQuery)
        col.addView(search)
        val listBox = vbox()
        if (!favoritesOnly) {
            val (sc, row) = chipRow()
            val filters = listOf<Pair<String, Kind?>>("Все" to null, "Сканы" to Kind.SCAN, "OCR" to Kind.OCR, "PDF" to Kind.PDF, "QR" to Kind.QR, "Конвертации" to Kind.CONVERT)
            for ((label, k) in filters) {
                row.addView(chip(label, historyFilter == k && !historyFavs) { historyFilter = k; historyFavs = false; render() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
            row.addView(chip("★ Избранное", historyFavs) { historyFavs = !historyFavs; if (historyFavs) historyFilter = null; render() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            col.addView(sc, lp().apply { topMargin = dp(12) })
        }
        col.addView(listBox, lp().apply { topMargin = dp(12) })
        var job: kotlinx.coroutines.Job? = null
        fun fill() {
            job?.cancel()
            val q = historyQuery.trim()
            val base = History.all(this).filter {
                (!favoritesOnly || it.favorite) && (!historyFavs || it.favorite) && (favoritesOnly || historyFilter == null || it.kind == historyFilter)
            }
            job = lifecycleScope.launch {
                // Поиск по словам внутри документов — в фоне, из готового индекса
                val found: List<Pair<Entry, String?>> = if (q.isEmpty()) base.map { it to null } else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    base.mapNotNull { e ->
                        when {
                            e.name.contains(q, true) -> e to null
                            e.extra.contains(q, true) -> e to e.extra.take(80)
                            else -> TextIndex.get(this@MainActivity, e)?.let { t -> TextIndex.snippet(t, q) }?.let { e to it }
                        }
                    }
                }
                listBox.removeAllViews()
                if (found.isEmpty()) {
                    listBox.addView(text(when {
                        q.isNotEmpty() -> "Ничего не найдено по «$q».\nНовые сканы попадают в поиск через минуту после сохранения."
                        favoritesOnly -> "Здесь пока пусто.\nОтметьте файл звёздочкой в истории."
                        else -> "Здесь пока ничего нет.\nОтсканируйте первый документ!"
                    }, 15f, R.color.text2).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(60), 0, 0)
                    })
                }
                found.forEach { (e, snip) -> listBox.addView(entryRow(e, snip), lp().apply { bottomMargin = dp(8) }) }
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { historyQuery = s?.toString() ?: ""; fill() }
        })
        fill()
    }

    private fun kindIcon(e: Entry): Pair<Int, Int> {
        val name = e.file.name.lowercase()
        return when {
            e.kind == Kind.QR -> R.drawable.ic_qr to R.color.tile_purple
            name.endsWith(".pdf") -> R.drawable.ic_pdf to R.color.tile_red
            name.endsWith(".docx") -> R.drawable.ic_doc to R.color.tile_blue
            name.endsWith(".xlsx") || name.endsWith(".csv") -> R.drawable.ic_grid to R.color.tile_green
            name.endsWith(".pptx") -> R.drawable.ic_image to R.color.tile_orange
            e.kind == Kind.OCR || name.endsWith(".txt") -> R.drawable.ic_ocr to R.color.tile_teal
            else -> R.drawable.ic_image to R.color.tile_blue
        }
    }

    private fun entryRow(e: Entry, snippet: String? = null): View {
        val row = hbox().apply {
            setPadding(dp(12), dp(10), dp(6), dp(10))
            background = clickable(cardBg(16), 16)
            setOnClickListener { Opener.open(this@MainActivity, e) }
            setOnLongClickListener { entryMenu(e); true }
        }
        val (ic, color) = kindIcon(e)
        row.addView(iconTile(ic, color, 40))
        val t = vbox().apply { setPadding(dp(12), 0, dp(6), 0) }
        t.addView(text(e.name, 14.5f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        val sub = if (e.kind == Kind.QR) e.extra.take(60) else "${formatDate(e.created)} · ${formatSize(e.size)}"
        t.addView(text(sub, 12f, R.color.text2).apply { maxLines = 1 })
        if (snippet != null) t.addView(text("«$snippet»", 12f, R.color.accent).apply { maxLines = 2; setPadding(0, dp(2), 0, 0) })
        row.addView(t, lp(0, WRAP, 1f))
        row.addView(iconButton(if (e.favorite) R.drawable.ic_star else R.drawable.ic_star_border, if (e.favorite) R.color.gold else R.color.text2) {
            e.favorite = !e.favorite
            History.update(this, e)
            render()
        })
        row.addView(iconButton(R.drawable.ic_more, R.color.text2) { entryMenu(e) })
        return row
    }

    private fun entryMenu(e: Entry) {
        val items = listOf("Открыть", "Поделиться", "Сохранить на телефон…", "Переименовать", if (e.favorite) "Убрать из избранного" else "В избранное", "Удалить")
        choose(e.name, items) { i ->
            when (i) {
                0 -> Opener.open(this, e)
                1 -> if (e.kind == Kind.QR && !e.file.exists()) Files.shareText(this, e.extra) else share(listOf(e.file), Files.mimeOf(e.file.name))
                2 -> saveAs(e.file, Files.mimeOf(e.file.name), e.name.let { n -> if (n.contains('.')) n else n + "." + e.file.extension })
                3 -> ask("Новое название", "Название", e.name) { n ->
                    if (n.isNotBlank()) { e.name = n.trim(); History.update(this, e); render() }
                }
                4 -> { e.favorite = !e.favorite; History.update(this, e); render() }
                5 -> confirm("Удалить?", "«${e.name}» будет удалён с телефона.", "Удалить") { History.delete(this, e); render() }
            }
        }
    }

    // ---------- Документы ----------

    private class SheetRow(
        val label: String,
        val sub: String? = null,
        val icon: Int? = null,
        val tile: View? = null,
        val danger: Boolean = false,
        val action: () -> Unit,
    )

    private fun typeTile(type: Docs.Type?, size: Int = 46): View {
        val c = type?.color ?: 0xFF607D9B.toInt()
        return FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(c, darker(c))
            ).apply { cornerRadius = dpf(size * 0.28f) }
            addView(text(type?.letter ?: "?", size * 0.46f, R.color.white_fixed, heavy = true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
    }

    private fun createButton(): View = FrameLayout(this).apply {
        background = accentBg(29)
        elevation = dpf(8f)
        isClickable = true
        addView(icon(R.drawable.ic_add, 28, R.color.white_fixed), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        setOnClickListener { createSheet() }
    }

    private fun sheet(title: String, rows: List<SheetRow>, headerTile: View? = null, subtitle: String? = null, withCancel: Boolean = false) {
        val box = vbox(14).apply { background = cardBg(22, glow = true) }
        val head = hbox().apply { setPadding(dp(4), dp(2), dp(4), dp(10)) }
        if (headerTile != null) { head.addView(headerTile); headerTile.margins(this, r = 12) }
        val ht = vbox()
        ht.addView(text(title, 17f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        if (subtitle != null) ht.addView(text(subtitle, 12.5f, R.color.text2))
        head.addView(ht, lp(0, WRAP, 1f))
        box.addView(head)
        val dialog = AlertDialog.Builder(this).setView(box).create()
        for (r in rows) {
            val row = hbox().apply {
                setPadding(dp(10), dp(11), dp(10), dp(11))
                background = clickable(cardBg(14), 14)
                setOnClickListener { dialog.dismiss(); r.action() }
            }
            when {
                r.tile != null -> { row.addView(r.tile); r.tile.margins(this, r = 12) }
                r.icon != null -> row.addView(icon(r.icon, 22, if (r.danger) R.color.danger else R.color.accent).apply { margins(this@MainActivity, l = 2, r = 14) })
            }
            val t = vbox()
            t.addView(text(r.label, 15f, if (r.danger) R.color.danger else R.color.text, bold = r.tile != null))
            if (r.sub != null) t.addView(text(r.sub, 12f, R.color.text2))
            row.addView(t, lp(0, WRAP, 1f))
            box.addView(row, lp().apply { topMargin = dp(6) })
        }
        if (withCancel) box.addView(secondaryButton("Отмена") { dialog.dismiss() }, lp().apply { topMargin = dp(12) })
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        dialog.show()
    }

    private fun createSheet() {
        sheet("Создать новый файл", listOf(
            SheetRow("Создать Word", "Новый документ", tile = typeTile(Docs.Type.WORD, 42)) { createDoc(Docs.Type.WORD, "Документ") },
            SheetRow("Создать Excel", "Таблицу", tile = typeTile(Docs.Type.EXCEL, 42)) { createDoc(Docs.Type.EXCEL, "Таблица") },
            SheetRow("Создать PowerPoint", "Презентацию", tile = typeTile(Docs.Type.POWERPOINT, 42)) { createDoc(Docs.Type.POWERPOINT, "Презентация") },
            SheetRow("Создать из скана", "С помощью камеры", tile = iconTile(R.drawable.ic_scan, R.color.tile_purple, 42)) {
                toast("Отсканируйте страницу, затем в тексте нажмите «Сохранить» → Word")
                startActivity(Intent(this, OcrActivity::class.java))
            },
            SheetRow("Создать из шаблона", "Готовые шаблоны", tile = iconTile(R.drawable.ic_doc, R.color.tile_teal, 42)) { templateSheet() },
            SheetRow("Открыть с телефона", "DOCX, XLSX, PPTX, ODT…", tile = iconTile(R.drawable.ic_open, R.color.tile_blue, 42)) { pickOfficeDocument() },
        ), withCancel = true)
    }

    private fun createDoc(type: Docs.Type, defaultName: String) {
        ask("Название файла", "Название", defaultName) { n ->
            val e = runCatching { Docs.create(this, type, n.trim().ifBlank { defaultName }) }
                .onFailure { fail(it) }.getOrNull() ?: return@ask
            render()
            openDoc(e)
        }
    }

    private fun templateSheet() {
        sheet("Шаблоны", Docs.TEMPLATES.map { t ->
            SheetRow(t.title, t.hint, tile = typeTile(t.type, 42)) { createFromTemplate(t) }
        }, withCancel = true)
    }

    private fun createFromTemplate(t: Docs.Template) {
        val e = runCatching { Docs.createFromTemplate(this, t) }.onFailure { fail(it) }.getOrNull() ?: return
        render()
        openDoc(e)
    }

    private fun pickOfficeDocument() {
        startActivity(Intent(this, OfficeWorkspaceActivity::class.java).putExtra("auto_pick", true))
    }

    private fun openDoc(e: Entry) {
        if (!e.file.exists()) { toast("Файл не найден"); return }
        startActivity(Intent(this, OfficeEngineActivity::class.java)
            .putExtra(EXTRA_FILE, e.path)
            .putExtra("source_uri", Docs.sourceUri(e)))
    }

    private fun documents(col: LinearLayout) {
        val head = hbox().apply { setPadding(dp(2), dp(4), 0, dp(12)) }
        head.addView(iconTile(R.drawable.ic_folder, R.color.tile_blue, 46))
        val ht = vbox().apply { setPadding(dp(12), 0, 0, 0) }
        ht.addView(text("Документы", 22f, heavy = true))
        ht.addView(text("Ваши файлы всегда под рукой", 13f, R.color.text2))
        head.addView(ht, lp(0, WRAP, 1f))
        col.addView(head)

        val search = field("Поиск по названию и содержимому…", docsQuery)
        col.addView(search)

        val (sc, row) = chipRow()
        val filters = listOf("all" to "Все", "word" to "Word", "excel" to "Excel", "ppt" to "PowerPoint",
            "templates" to "Шаблоны", "recent" to "Недавние", "fav" to "★ Избранное")
        for ((key, label) in filters) {
            row.addView(chip(label, docsFilter == key) { docsFilter = key; render() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        col.addView(sc, lp().apply { topMargin = dp(12) })

        val listBox = vbox()
        col.addView(listBox, lp().apply { topMargin = dp(12); bottomMargin = dp(80) })

        if (docsFilter == "templates") {
            Docs.TEMPLATES.forEach { t ->
                val card = hbox().apply {
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = clickable(cardBg(16), 16)
                    setOnClickListener { createFromTemplate(t) }
                }
                card.addView(typeTile(t.type, 44))
                val tx = vbox().apply { setPadding(dp(12), 0, dp(6), 0) }
                tx.addView(text(t.title, 15f, bold = true))
                tx.addView(text("${t.type.title} · ${t.hint}", 12f, R.color.text2))
                card.addView(tx, lp(0, WRAP, 1f))
                card.addView(icon(R.drawable.ic_add, 22, R.color.accent))
                listBox.addView(card, lp().apply { bottomMargin = dp(8) })
            }
            search.visibility = View.GONE
            return
        }

        var job: kotlinx.coroutines.Job? = null
        fun fill() {
            job?.cancel()
            val q = docsQuery.trim()
            var base = Docs.list(this).sortedByDescending { it.file.lastModified() }
            base = when (docsFilter) {
                "word" -> base.filter { Docs.typeOf(it.file.name) == Docs.Type.WORD }
                "excel" -> base.filter { Docs.typeOf(it.file.name) == Docs.Type.EXCEL }
                "ppt" -> base.filter { Docs.typeOf(it.file.name) == Docs.Type.POWERPOINT }
                "recent" -> base.take(10)
                "fav" -> base.filter { it.favorite }
                else -> base
            }
            job = lifecycleScope.launch {
                val found: List<Pair<Entry, String?>> = if (q.isEmpty()) base.map { it to null } else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    base.mapNotNull { e ->
                        if (e.name.contains(q, true)) e to null
                        else TextIndex.get(this@MainActivity, e)?.let { t -> TextIndex.snippet(t, q) }?.let { e to it }
                    }
                }
                listBox.removeAllViews()
                if (found.isEmpty()) {
                    listBox.addView(text(when {
                        q.isNotEmpty() -> "Ничего не найдено по «$q»."
                        docsFilter == "fav" -> "В избранном пока пусто.\nДолгое нажатие на файл → «Добавить в избранное»."
                        else -> "Документов пока нет.\nНажмите «+», чтобы создать Word, Excel или PowerPoint."
                    }, 15f, R.color.text2).apply { gravity = Gravity.CENTER; setPadding(0, dp(50), 0, 0) })
                }
                found.forEach { (e, snip) -> listBox.addView(docCard(e, snip), lp().apply { bottomMargin = dp(8) }) }
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { docsQuery = s?.toString() ?: ""; fill() }
        })
        fill()
    }

    private fun docCard(e: Entry, snippet: String?): View {
        val card = hbox().apply {
            setPadding(dp(12), dp(11), dp(8), dp(11))
            background = clickable(cardBg(18), 18)
            setOnClickListener { openDoc(e) }
            setOnLongClickListener { docMenu(e); true }
        }
        card.addView(typeTile(Docs.typeOf(e.file.name)))
        val t = vbox().apply { setPadding(dp(12), 0, dp(6), 0) }
        t.addView(text(e.name, 15f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        t.addView(text("${formatSize(e.size)} • ${Docs.dateLabel(e.file.lastModified())}", 12f, R.color.text2).apply { maxLines = 1 })
        if (snippet != null) t.addView(text("«$snippet»", 12f, R.color.accent).apply { maxLines = 2; setPadding(0, dp(2), 0, 0) })
        card.addView(t, lp(0, WRAP, 1f))
        val right = vbox().apply { gravity = Gravity.END }
        right.addView(iconButton(R.drawable.ic_more, R.color.text2) { docMenu(e) })
        val cloud = Docs.isCloud(e)
        right.addView(text(if (cloud) "☁ Облако" else "▫ Локально", 11.5f, if (cloud) R.color.accent else R.color.text2).apply { setPadding(0, 0, dp(6), 0) })
        card.addView(right)
        if (e.favorite) (t.getChildAt(0) as? android.widget.TextView)?.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_star, 0)
        return card
    }

    private fun docMenu(e: Entry) {
        val type = Docs.typeOf(e.file.name)
        sheet(e.name, listOf(
            SheetRow("Открыть", icon = R.drawable.ic_open) { openDoc(e) },
            SheetRow("Переименовать", icon = R.drawable.ic_edit) {
                ask("Новое название", "Название", Files.baseName(e.file.name)) { n ->
                    if (n.isNotBlank()) {
                        if (!Docs.rename(this, e, n.trim())) toast("Не удалось переименовать: такое имя уже есть")
                        render()
                    }
                }
            },
            SheetRow("Дублировать", icon = R.drawable.ic_copy) {
                runCatching { Docs.duplicate(this, e) }.onFailure { fail(it) }
                render()
            },
            SheetRow("Поделиться", icon = R.drawable.ic_share) { share(listOf(e.file), Files.mimeOf(e.file.name)) },
            SheetRow(if (e.favorite) "Убрать из избранного" else "Добавить в избранное",
                icon = if (e.favorite) R.drawable.ic_star else R.drawable.ic_star_border) {
                e.favorite = !e.favorite; History.update(this, e); render()
            },
            SheetRow("Удалить", icon = R.drawable.ic_delete, danger = true) {
                confirm("Удалить?", "«${e.name}» будет удалён с телефона.", "Удалить") { History.delete(this, e); render() }
            },
        ), headerTile = typeTile(type, 42), subtitle = formatSize(e.size))
    }

    // ---------- Настройки ----------

    private fun settings(col: LinearLayout) {
        val prefs = Prefs(this)
        header(col, "Настройки")

        col.addView(sectionTitle("ВНЕШНИЙ ВИД"))
        col.addView(listRow(R.drawable.ic_moon, "Тема", Accent.THEMES[prefs.themeMode.coerceIn(0, 2)]) {
            choose("Тема", Accent.THEMES) { i ->
                prefs.themeMode = i
                prefs.darkTheme = i == 0
                Accent.applyTheme(i)
            }
        })
        // Цвет акцента: кружки
        val colors = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = cardBg(16); gravity = Gravity.CENTER_VERTICAL }
        colors.addView(icon(R.drawable.ic_drop, 22, R.color.accent).apply { margins(context, r = 14) })
        colors.addView(text("Цвет", 15f), lp(0, WRAP, 1f))
        Accent.COLORS.forEachIndexed { i, o ->
            val sel = i == prefs.accent
            colors.addView(View(this).apply {
                background = rounded(o.color, 15, if (sel) this@MainActivity.col(R.color.text) else null, 2)
                contentDescription = o.name
                setOnClickListener { Accent.set(this@MainActivity, i); recreate() }
            }, LinearLayout.LayoutParams(dp(if (sel) 30 else 24), dp(if (sel) 30 else 24)).apply { leftMargin = dp(6) })
        }
        col.addView(colors, lp().apply { topMargin = dp(8) })
        col.addView(listRow(R.drawable.ic_globe, "Язык интерфейса", I18n.LANGS.firstOrNull { it.first == prefs.uiLang }?.second ?: "Русский") {
            choose("Язык интерфейса", I18n.LANGS.map { it.second }) { i ->
                val code = I18n.LANGS[i].first
                prefs.uiLang = code
                I18n.load(this, code)
                recreate()
            }
        }, lp().apply { topMargin = dp(8) })

        col.addView(sectionTitle("БЕЗОПАСНОСТЬ"))
        col.addView(listRow(R.drawable.ic_lock, "Блокировка входа", "PIN-код при открытии приложения", switchView(Lock.enabled(this)) { on ->
            if (on && !Lock.enabled(this)) lockSetup.launch(Intent(this, LockActivity::class.java).putExtra("setup", true))
            else if (!on && Lock.enabled(this)) lockVerify.launch(Intent(this, LockActivity::class.java).putExtra("verify", true))
        }))
        if (Lock.enabled(this) && Lock.biometricAvailable(this)) {
            col.addView(listRow(R.drawable.ic_unlock, "Вход по отпечатку пальца", null, switchView(prefs.biometric) { prefs.biometric = it }), lp().apply { topMargin = dp(8) })
        }

        col.addView(sectionTitle("РАСПОЗНАВАНИЕ ТЕКСТА"))
        col.addView(listRow(R.drawable.ic_translate, "Язык распознавания", Ocr.nameOf(prefs.ocrLang)) { chooseOcrLang() })
        col.addView(listRow(R.drawable.ic_ai, "Автоулучшение изображения", "Убирать тени и выравнивать фон перед распознаванием", switchView(prefs.autoEnhance) { prefs.autoEnhance = it }), lp().apply { topMargin = dp(8) })
        col.addView(listRow(R.drawable.ic_scan, "Обрезка и выравнивание фото", "Находить край листа и выпрямлять строки на обычных фото", switchView(prefs.autoCrop) { prefs.autoCrop = it }), lp().apply { topMargin = dp(8) })
        col.addView(listRow(R.drawable.ic_grid, "Распознавание таблиц", "Предлагать сохранить таблицу в Excel/CSV", switchView(prefs.tables) { prefs.tables = it }), lp().apply { topMargin = dp(8) })
        col.addView(listRow(R.drawable.ic_text, "Название по тексту", "Называть сканы по первым строкам документа", switchView(prefs.autoName) { prefs.autoName = it }), lp().apply { topMargin = dp(8) })

        col.addView(sectionTitle("СОХРАНЕНИЕ"))
        col.addView(listRow(R.drawable.ic_folder, "Копии в папку", prefs.exportTreeName.ifEmpty { "Не выбрана — файлы только в приложении" }) {
            try { pickTree.launch(null) } catch (e: Exception) { toast("Нет приложения для выбора папки") }
        })
        if (prefs.exportTree.isNotEmpty()) {
            col.addView(listRow(R.drawable.ic_close, "Не сохранять копии", null) {
                prefs.exportTree = ""; prefs.exportTreeName = ""; render()
            }, lp().apply { topMargin = dp(8) })
        }
        col.addView(listRow(R.drawable.ic_sign, "Мои подписи и печати", "Нарисовать или загрузить") {
            startActivity(Intent(this, SignatureActivity::class.java))
        }, lp().apply { topMargin = dp(8) })

        col.addView(sectionTitle("РЕЗЕРВНАЯ КОПИЯ"))
        col.addView(listRow(R.drawable.ic_backup, "Создать резервную копию", "Вся История одним файлом — для переноса на новый телефон") { makeBackup() })
        col.addView(listRow(R.drawable.ic_folder, "Восстановить из копии", "Добавит документы из файла .zip") {
            pick(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) { restoreBackup(it[0]) }
        }, lp().apply { topMargin = dp(8) })

        col.addView(sectionTitle("О ПРИЛОЖЕНИИ"))
        col.addView(listRow(R.drawable.ic_save, "Проверить обновления", "Версия ${BuildConfig.VERSION_NAME}") {
            lifecycleScope.launch {
                val u = Updater.check()
                if (u == null) toast("У вас последняя версия")
                else openUpdate(u)
            }
        })
        col.addView(listRow(R.drawable.ic_lock, "Политика конфиденциальности", null) {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }, lp().apply { topMargin = dp(8) })
        col.addView(listRow(R.drawable.ic_share, "Поделиться приложением", null) {
            Files.shareText(this, "Maysk Scan — сканер документов, текст из фото, PDF и QR без интернета:\n${Updater.installUrl()}")
        }, lp().apply { topMargin = dp(8) })
        col.addView(text("Maysk Scan ${BuildConfig.VERSION_NAME}\nВсё работает на телефоне: сканы и тексты никуда не отправляются.", 12f, R.color.text2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(10))
        })
    }

    private fun makeBackup() {
        lifecycleScope.launch {
            showProgress("Создаю резервную копию…")
            try {
                val out = File(cacheDir, "MayskScan-копия-${Files.stamp()}.zip")
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Backup.create(this@MainActivity, out) { i, n -> runOnUiThread { showProgress("Резервная копия: файл $i из $n…") } }
                }
                hideProgress()
                TrDialog(this@MainActivity).setTitle("Резервная копия готова")
                    .setMessage("Документов: ${r.entries}, размер ${formatSize(r.bytes)}.\n\nСохраните файл на телефон или отправьте себе (Telegram, почта, облако). На новом телефоне: Настройки → «Восстановить из копии».")
                    .setPositiveButton("Отправить…") { _, _ -> share(listOf(out), "application/zip") }
                    .setNeutralButton("На телефон…") { _, _ -> saveAs(out, "application/zip") }
                    .setNegativeButton("Закрыть", null).show()
            } catch (e: Throwable) { fail(e) }
        }
    }

    private fun restoreBackup(uri: Uri) {
        lifecycleScope.launch {
            showProgress("Восстанавливаю…")
            try {
                val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Backup.restore(this@MainActivity, uri) { i -> runOnUiThread { showProgress("Восстановлено файлов: $i…") } }
                }
                hideProgress()
                TextIndex.scheduleMissing(this@MainActivity)
                TrDialog(this@MainActivity).setTitle("Готово!")
                    .setMessage("Добавлено документов: $n. Они в Истории.")
                    .setPositiveButton("OK", null).show()
                render()
            } catch (e: Throwable) { fail(e) }
        }
    }

    private fun chooseOcrLang() {
        val prefs = Prefs(this)
        val opts = listOf("auto") + Ocr.LANGS.map { it.code } + listOf("rus+eng", "kaz+rus")
        choose("Язык распознавания", opts.map { Ocr.nameOf(it) }) { i -> prefs.ocrLang = opts[i]; render() }
    }
}

const val EXTRA_FILE = "file"

/** Открытие записи из истории подходящим экраном. */
object Opener {
    fun open(ctx: Context, e: Entry) {
        val name = e.file.name.lowercase()
        when {
            e.kind == Kind.QR -> {
                TrDialog(ctx).setTitle("QR-код").setMessage(e.extra)
                    .setPositiveButton("Копировать") { _, _ -> Clip.copy(ctx, e.extra) }
                    .setNeutralButton("Открыть") { _, _ ->
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.extra))) } catch (x: Exception) { ctx.toast("Это не ссылка") }
                    }
                    .setNegativeButton("Закрыть", null).show()
            }
            name.endsWith(".pdf") -> ctx.startActivity(Intent(ctx, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, e.path))
            name.endsWith(".txt") -> ctx.startActivity(Intent(ctx, OcrActivity::class.java).putExtra("text_file", e.path))
            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") ->
                ctx.startActivity(Intent(ctx, ScanActivity::class.java).putExtra("images", arrayOf(e.path)))
            else -> Files.openWith(ctx, e.file)
        }
    }
}

object Clip {
    fun copy(ctx: Context, s: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Maysk Scan", s))
        ctx.toast("Скопировано")
    }
}
