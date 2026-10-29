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

/** Главный экран с нижним меню: Главная · История · Избранное · Настройки. */
class MainActivity : BaseActivity() {

    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private var tab = 0
    private var historyQuery = ""
    private var historyFilter: Kind? = null
    private var update: Updater.Info? = null

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
                    AlertDialog.Builder(this@MainActivity)
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
            Triple(R.drawable.ic_history, "История", 1),
            Triple(R.drawable.ic_star_border, "Избранное", 2),
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
                if (sel) background = rounded(0x331E88FF, 14)
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
            2 -> history(col, favoritesOnly = true)
            3 -> settings(col)
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
        val search = field("Поиск по названию…", historyQuery)
        col.addView(search)
        val listBox = vbox()
        if (!favoritesOnly) {
            val (sc, row) = chipRow()
            val filters = listOf<Pair<String, Kind?>>("Все" to null, "Сканы" to Kind.SCAN, "OCR" to Kind.OCR, "PDF" to Kind.PDF, "QR" to Kind.QR, "Конвертации" to Kind.CONVERT)
            for ((label, k) in filters) {
                row.addView(chip(label, historyFilter == k) { historyFilter = k; render() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
            col.addView(sc, lp().apply { topMargin = dp(12) })
        }
        col.addView(listBox, lp().apply { topMargin = dp(12) })
        fun fill() {
            listBox.removeAllViews()
            val q = historyQuery.trim().lowercase()
            val list = History.all(this).filter {
                (!favoritesOnly || it.favorite) &&
                    (favoritesOnly || historyFilter == null || it.kind == historyFilter) &&
                    (q.isEmpty() || it.name.lowercase().contains(q) || it.extra.lowercase().contains(q))
            }
            if (list.isEmpty()) {
                listBox.addView(text(if (favoritesOnly) "Здесь пока пусто.\nОтметьте файл звёздочкой в истории." else "Здесь пока ничего нет.\nОтсканируйте первый документ!", 15f, R.color.text2).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(60), 0, 0)
                })
            }
            list.forEach { listBox.addView(entryRow(it), lp().apply { bottomMargin = dp(8) }) }
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

    private fun entryRow(e: Entry): View {
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

    // ---------- Настройки ----------

    private fun settings(col: LinearLayout) {
        val prefs = Prefs(this)
        header(col, "Настройки")

        col.addView(sectionTitle("ВНЕШНИЙ ВИД"))
        col.addView(listRow(R.drawable.ic_moon, "Тёмная тема", null, switchView(prefs.darkTheme) { on ->
            prefs.darkTheme = on
            AppCompatDelegate.setDefaultNightMode(if (on) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }))
        col.addView(listRow(R.drawable.ic_globe, "Язык", null, text("Русский", 14f, R.color.text2)), lp().apply { topMargin = dp(8) })

        col.addView(sectionTitle("РАСПОЗНАВАНИЕ ТЕКСТА"))
        col.addView(listRow(R.drawable.ic_translate, "Язык распознавания", Ocr.nameOf(prefs.ocrLang)) { chooseOcrLang() })
        col.addView(listRow(R.drawable.ic_ai, "Автоулучшение изображения", "Убирать тени и выравнивать фон перед распознаванием", switchView(prefs.autoEnhance) { prefs.autoEnhance = it }), lp().apply { topMargin = dp(8) })
        col.addView(listRow(R.drawable.ic_grid, "Распознавание таблиц", "Предлагать сохранить таблицу в Excel/CSV", switchView(prefs.tables) { prefs.tables = it }), lp().apply { topMargin = dp(8) })

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

        col.addView(sectionTitle("О ПРИЛОЖЕНИИ"))
        col.addView(listRow(R.drawable.ic_save, "Проверить обновления", "Версия ${BuildConfig.VERSION_NAME}") {
            lifecycleScope.launch {
                val u = Updater.check()
                if (u == null) toast("У вас последняя версия")
                else openUpdate(u)
            }
        })
        col.addView(listRow(R.drawable.ic_share, "Поделиться приложением", null) {
            Files.shareText(this, "Maysk Scan — сканер документов, текст из фото, PDF и QR без интернета:\n${Updater.installUrl()}")
        }, lp().apply { topMargin = dp(8) })
        col.addView(text("Maysk Scan ${BuildConfig.VERSION_NAME}\nВсё работает на телефоне: сканы и тексты никуда не отправляются.", 12f, R.color.text2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(10))
        })
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
                AlertDialog.Builder(ctx).setTitle("QR-код").setMessage(e.extra)
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
