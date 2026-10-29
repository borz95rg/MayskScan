package com.maysk.scan

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Объединение, разделение и извлечение страниц PDF. */
class MergeSplitActivity : BaseActivity() {

    private val files = ArrayList<File>()
    private lateinit var listBox: LinearLayout
    private var src: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        src = intent.getStringExtra(EXTRA_FILE)?.let { File(it) }
        when (intent.getStringExtra("mode")) {
            "split" -> splitScreen()
            "extract" -> extractScreen()
            else -> mergeScreen()
        }
    }

    private fun pickPdfs(multiple: Boolean, cb: (List<File>) -> Unit) {
        pick(arrayOf("application/pdf"), multiple) { uris ->
            lifecycleScope.launch {
                try {
                    cb(withContext(Dispatchers.IO) { uris.map { Files.copyToCache(this@MergeSplitActivity, it) } })
                } catch (e: Throwable) { fail(e) }
            }
        }
    }

    // ---------- Объединение ----------

    private fun mergeScreen() {
        val body = screen("Объединение PDF")
        body.addView(text("Выберите несколько PDF, расставьте по порядку и объедините в один файл.", 14f, R.color.text2))
        listBox = vbox()
        body.addView(listBox, lp().apply { topMargin = dp(12) })
        body.addView(secondaryButton("Добавить файл", R.drawable.ic_add) { pickPdfs(true) { files.addAll(it); renderList() } }, lp().apply { topMargin = dp(8) })
        body.addView(primaryButton("Объединить", R.drawable.ic_merge) { merge() }, lp().apply { topMargin = dp(12) })
        src?.let { files.add(it) }
        renderList()
        if (files.isEmpty()) pickPdfs(true) { files.addAll(it); renderList() }
    }

    private fun renderList() {
        listBox.removeAllViews()
        files.forEachIndexed { i, f ->
            val row = hbox(10).apply { background = cardBg(16) }
            row.addView(text("${i + 1}", 15f, R.color.accent, bold = true).apply { setPadding(dp(4), 0, dp(10), 0) })
            row.addView(iconTile(R.drawable.ic_pdf, R.color.tile_red, 36))
            val t = vbox().apply { setPadding(dp(10), 0, 0, 0) }
            t.addView(text(f.name, 14f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            t.addView(text(formatSize(f.length()), 12f, R.color.text2))
            row.addView(t, lp(0, WRAP, 1f))
            row.addView(iconButton(R.drawable.ic_back, R.color.text2) {
                if (i > 0) { files.add(i - 1, files.removeAt(i)); renderList() }
            }.apply { rotation = 90f })
            row.addView(iconButton(R.drawable.ic_back, R.color.text2) {
                if (i < files.size - 1) { files.add(i + 1, files.removeAt(i)); renderList() }
            }.apply { rotation = -90f })
            row.addView(iconButton(R.drawable.ic_close, R.color.danger) { files.removeAt(i); renderList() })
            listBox.addView(row, lp().apply { bottomMargin = dp(8) })
        }
    }

    private fun merge() {
        if (files.size < 2) { toast("Добавьте хотя бы два PDF"); return }
        lifecycleScope.launch {
            showProgress("Объединяю ${files.size} файла…")
            try {
                val out = Files.newDoc(this@MergeSplitActivity, "Объединённый ${Files.stamp()}", "pdf")
                withContext(Dispatchers.IO) { PdfTools.merge(files, out); Files.exportCopy(this@MergeSplitActivity, out) }
                History.add(this@MergeSplitActivity, Kind.PDF, out)
                hideProgress()
                toast("Готово: ${out.name}")
                startActivity(Intent(this@MergeSplitActivity, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, out.absolutePath))
                finish()
            } catch (e: Throwable) { fail(e) }
        }
    }

    // ---------- Разделение ----------

    private fun withSource(cb: (File) -> Unit) {
        val s = src
        if (s != null) cb(s) else pickPdfs(false) { src = it[0]; cb(it[0]) }
    }

    private fun splitScreen() {
        val body = screen("Разделение PDF")
        val name = text(src?.name ?: "Файл не выбран", 15f, bold = true)
        val card = hbox(14).apply { background = cardBg(18) }
        card.addView(iconTile(R.drawable.ic_pdf, R.color.tile_red, 42))
        card.addView(name, lp(0, WRAP, 1f).apply { leftMargin = dp(12) })
        card.setOnClickListener { pickPdfs(false) { src = it[0]; name.text = it[0].name } }
        body.addView(card)
        body.addView(sectionTitle("КАК РАЗДЕЛИТЬ"))
        body.addView(listRow(R.drawable.ic_split, "По страницам", "Каждая страница — отдельный файл") { withSource { split(it, "pages") } })
        body.addView(listRow(R.drawable.ic_split, "По диапазону", "Например: 1-3, 4-10, 11") { withSource { split(it, "range") } }, lp().apply { topMargin = dp(8) })
        body.addView(listRow(R.drawable.ic_compress, "По размеру", "Части не больше заданного размера") { withSource { split(it, "size") } }, lp().apply { topMargin = dp(8) })
        body.addView(listRow(R.drawable.ic_bookmark, "По закладкам", "Разделы из оглавления PDF") { withSource { split(it, "bookmarks") } }, lp().apply { topMargin = dp(8) })
        if (src == null) pickPdfs(false) { src = it[0]; name.text = it[0].name }
    }

    private fun split(f: File, how: String) {
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { PdfTools.pageCount(f) }
                when (how) {
                    "pages" -> runSplit(f) { dir, base -> PdfTools.splitRanges(f, (0 until count).map { it..it }, dir, base) }
                    "range" -> ask("Диапазоны страниц (всего $count)", "1-3, 4-10, 11") { s ->
                        val ranges = PdfTools.parseRanges(s, count)
                        if (ranges.isEmpty()) toast("Не понял диапазоны") else runSplit(f) { dir, base -> PdfTools.splitRanges(f, ranges, dir, base) }
                    }
                    "size" -> ask("Максимальный размер части, МБ", "например 5", "5", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { s ->
                        val mb = s.replace(',', '.').toDoubleOrNull()
                        if (mb == null || mb <= 0) toast("Введите размер в МБ") else runSplit(f) { dir, base -> PdfTools.splitBySize(f, (mb * 1048576).toLong(), dir, base) }
                    }
                    "bookmarks" -> {
                        val sections = withContext(Dispatchers.IO) { PdfTools.bookmarkRanges(f) }
                        if (sections.isEmpty()) {
                            AlertDialog.Builder(this@MergeSplitActivity).setTitle("Закладок нет")
                                .setMessage("В этом PDF нет оглавления. Разделите по страницам или по диапазону.")
                                .setPositiveButton("OK", null).show()
                        } else runSplit(f) { dir, base ->
                            sections.flatMap { (title, r) -> PdfTools.splitRanges(f, listOf(r), dir, Files.sanitize("$base - $title").take(80)) }
                        }
                    }
                }
            } catch (e: Throwable) { fail(e) }
        }
    }

    private fun runSplit(f: File, work: (File, String) -> List<File>) {
        lifecycleScope.launch {
            showProgress("Разделяю…")
            try {
                val dir = File(cacheDir, "split_${System.nanoTime()}").apply { mkdirs() }
                val parts = withContext(Dispatchers.IO) {
                    work(dir, Files.baseName(f.name)).map { part ->
                        val out = Files.newDoc(this@MergeSplitActivity, Files.baseName(part.name), "pdf")
                        part.copyTo(out, true)
                        Files.exportCopy(this@MergeSplitActivity, out)
                        out
                    }
                }
                parts.forEach { History.add(this@MergeSplitActivity, Kind.PDF, it) }
                hideProgress()
                AlertDialog.Builder(this@MergeSplitActivity).setTitle("Готово")
                    .setMessage("Получилось файлов: ${parts.size}. Они в Истории.")
                    .setPositiveButton("Поделиться всеми") { _, _ -> share(parts, "application/pdf") }
                    .setNegativeButton("Закрыть", null).show()
            } catch (e: Throwable) { fail(e) }
        }
    }

    // ---------- Извлечение ----------

    private fun extractScreen() {
        val body = screen("Извлечь страницы")
        body.addView(text("Выберите PDF и укажите страницы — они сохранятся в новый файл.\nНапример: 5 или 10 или 15-25 или 1, 3, 7-9", 14f, R.color.text2))
        val name = text(src?.name ?: "Файл не выбран", 15f, bold = true)
        val card = hbox(14).apply { background = cardBg(18) }
        card.addView(iconTile(R.drawable.ic_pdf, R.color.tile_red, 42))
        card.addView(name, lp(0, WRAP, 1f).apply { leftMargin = dp(12) })
        card.setOnClickListener { pickPdfs(false) { src = it[0]; name.text = it[0].name } }
        body.addView(card, lp().apply { topMargin = dp(12) })
        val fld = field("Страницы, например 15-25")
        body.addView(fld, lp().apply { topMargin = dp(12) })
        body.addView(primaryButton("Извлечь в новый PDF", R.drawable.ic_extract) {
            val f = src ?: run { toast("Выберите PDF"); return@primaryButton }
            lifecycleScope.launch {
                showProgress("Извлекаю…")
                try {
                    val count = withContext(Dispatchers.IO) { PdfTools.pageCount(f) }
                    val pages = PdfTools.parseRanges(fld.text.toString(), count).flatMap { it.toList() }
                    if (pages.isEmpty()) { hideProgress(); toast("Укажите страницы от 1 до $count"); return@launch }
                    val out = Files.newDoc(this@MergeSplitActivity, Files.baseName(f.name) + " (стр. ${fld.text.toString().trim().take(20)})", "pdf")
                    withContext(Dispatchers.IO) { PdfTools.extract(f, pages, out); Files.exportCopy(this@MergeSplitActivity, out) }
                    History.add(this@MergeSplitActivity, Kind.PDF, out)
                    hideProgress()
                    startActivity(Intent(this@MergeSplitActivity, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, out.absolutePath))
                    finish()
                } catch (e: Throwable) { fail(e) }
            }
        }, lp().apply { topMargin = dp(12) })
        if (src == null) pickPdfs(false) { src = it[0]; name.text = it[0].name }
    }
}
