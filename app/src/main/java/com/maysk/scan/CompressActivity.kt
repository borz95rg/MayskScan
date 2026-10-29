package com.maysk.scan

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/** Сжатие PDF: картинки внутри уменьшаются и пересжимаются, текст остаётся текстом. */
class CompressActivity : BaseActivity() {

    private lateinit var src: File
    private var level = PdfTools.Level.MEDIUM
    private val sizeViews = HashMap<PdfTools.Level, TextView>()
    private val radios = HashMap<PdfTools.Level, RadioButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        src = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        val body = screen("Сжатие PDF")

        val card = hbox(14).apply { background = cardBg(18) }
        card.addView(iconTile(R.drawable.ic_pdf, R.color.tile_red, 46))
        val t = vbox().apply { setPadding(dp(12), 0, 0, 0) }
        t.addView(text(src.name, 15f, bold = true).apply { maxLines = 2 })
        val meta = text(formatSize(src.length()), 13f, R.color.text2)
        t.addView(meta)
        card.addView(t, lp(0, WRAP, 1f))
        body.addView(card)

        body.addView(sectionTitle("ВЫБЕРИТЕ УРОВЕНЬ СЖАТИЯ"))
        for (l in PdfTools.Level.values()) {
            val row = hbox(12).apply { background = clickable(cardBg(16), 16) }
            val rb = RadioButton(this).apply {
                isChecked = l == level
                buttonTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
                isClickable = false
            }
            radios[l] = rb
            row.addView(rb)
            val tt = vbox()
            tt.addView(text(l.title, 15f))
            tt.addView(text(when (l) {
                PdfTools.Level.LOSSLESS -> "Только очистка, качество не меняется"
                PdfTools.Level.HIGH -> "200 dpi — для печати"
                PdfTools.Level.MEDIUM -> "150 dpi — для почты и мессенджеров"
                PdfTools.Level.MAX -> "100 dpi — самый маленький файл"
            }, 12f, R.color.text2))
            row.addView(tt, lp(0, WRAP, 1f))
            val sz = text("…", 14f, R.color.text2, bold = true)
            sizeViews[l] = sz
            row.addView(sz)
            row.setOnClickListener { level = l; radios.forEach { (k, v) -> v.isChecked = k == l } }
            body.addView(row, lp().apply { bottomMargin = dp(8) })
        }
        body.addView(primaryButton("Сжать", R.drawable.ic_compress) { compress() }, lp().apply { topMargin = dp(12) })

        lifecycleScope.launch {
            try {
                val pages = withContext(Dispatchers.IO) { PdfTools.pageCount(src) }
                meta.text = "${formatSize(src.length())} · $pages стр."
                estimate(pages)
            } catch (e: Throwable) {
                meta.text = "${formatSize(src.length())} · защищён паролем?"
            }
        }
    }

    /** Оценка размера: сжимаем первые страницы и пересчитываем на весь файл. */
    private suspend fun estimate(pages: Int) {
        try {
            val sample = withContext(Dispatchers.IO) {
                if (pages <= 3) src else Files.tempFile(this@CompressActivity, "pdf").also { PdfTools.extract(src, (0 until 3).toList(), it) }
            }
            for (l in PdfTools.Level.values()) {
                val est = withContext(Dispatchers.IO) {
                    val out = Files.tempFile(this@CompressActivity, "pdf")
                    PdfTools.compress(sample, l, out)
                    val ratio = out.length().toDouble() / sample.length()
                    out.delete()
                    min(src.length(), (src.length() * ratio).toLong())
                }
                sizeViews[l]?.text = "≈ ${formatSize(est)}"
            }
            if (sample != src) sample.delete()
        } catch (e: Throwable) {
            sizeViews.values.forEach { it.text = "" }
        }
    }

    private fun compress() {
        lifecycleScope.launch {
            showProgress("Сжимаю…")
            try {
                val out = Files.newDoc(this@CompressActivity, Files.baseName(src.name) + " (сжат)", "pdf")
                withContext(Dispatchers.IO) {
                    PdfTools.compress(src, level, out) { p, n -> runOnUiThread { showProgress("Сжимаю страницу $p из $n…") } }
                }
                hideProgress()
                if (out.length() >= src.length()) {
                    out.delete()
                    AlertDialog.Builder(this@CompressActivity).setTitle("Сжатие")
                        .setMessage("Этот PDF уже хорошо сжат — уменьшить его не получилось. Попробуйте уровень «Максимальное сжатие».")
                        .setPositiveButton("OK", null).show()
                    return@launch
                }
                withContext(Dispatchers.IO) { Files.exportCopy(this@CompressActivity, out) }
                History.add(this@CompressActivity, Kind.PDF, out)
                val saved = 100 - out.length() * 100 / src.length()
                AlertDialog.Builder(this@CompressActivity).setTitle("Готово! −$saved%")
                    .setMessage("До: ${formatSize(src.length())}\nПосле: ${formatSize(out.length())}")
                    .setPositiveButton("Открыть") { _, _ -> startActivity(Intent(this@CompressActivity, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, out.absolutePath)); finish() }
                    .setNeutralButton("Поделиться") { _, _ -> share(listOf(out), "application/pdf") }
                    .setNegativeButton("Закрыть", null).show()
            } catch (e: Throwable) { fail(e) }
        }
    }
}
