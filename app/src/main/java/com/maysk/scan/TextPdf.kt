package com.maysk.scan

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Создание PDF из текста, документов Word и таблиц Excel (простая вёрстка).
 * Шрифты телефона поддерживают кириллицу, казахские буквы и т.д.
 */
class TextPdf(private val landscape: Boolean = false) {
    private val pageW = if (landscape) 842 else 595
    private val pageH = if (landscape) 595 else 842
    private val margin = 48f
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = margin
    private var pageNo = 0
    private val contentW get() = pageW - margin * 2

    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
    private val bold = TextPaint(body).apply { typeface = Typeface.DEFAULT_BOLD }
    private val linePaint = Paint().apply { color = Color.rgb(160, 160, 160); strokeWidth = 0.6f }
    private val headerFill = Paint().apply { color = Color.rgb(232, 240, 252) }

    private fun newPage() {
        finishPage()
        pageNo++
        val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()
        page = doc.startPage(info)
        canvas = page!!.canvas
        y = margin
    }

    private fun finishPage() {
        page?.let { doc.finishPage(it) }
        page = null
        canvas = null
    }

    private fun ensure(h: Float) {
        if (page == null || y + h > pageH - margin) newPage()
    }

    @Suppress("DEPRECATION")
    private fun layout(text: CharSequence, paint: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, max(10, width)).setAlignment(align).setLineSpacing(0f, 1.15f).build()
        } else {
            StaticLayout(text, paint, max(10, width), align, 1.15f, 0f, false)
        }

    /** Абзац с переносом на следующие страницы по строкам. */
    fun paragraph(text: String, size: Float = 11f, isBold: Boolean = false, center: Boolean = false, spaceAfter: Float = 6f) {
        val paint = TextPaint(if (isBold) bold else body).apply { textSize = size }
        if (text.isEmpty()) { ensure(size); y += size * 0.8f; return }
        val l = layout(text, paint, contentW.toInt(), if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
        var line = 0
        while (line < l.lineCount) {
            ensure(l.getLineBottom(line) - l.getLineTop(line).toFloat())
            val c = canvas!!
            // Сколько строк помещается на текущей странице
            var last = line
            val top = l.getLineTop(line)
            while (last + 1 < l.lineCount && y + (l.getLineBottom(last + 1) - top) <= pageH - margin) last++
            c.save()
            c.translate(margin, y - top)
            c.clipRect(0f, top.toFloat(), contentW, l.getLineBottom(last).toFloat())
            l.draw(c)
            c.restore()
            y += l.getLineBottom(last) - top
            line = last + 1
            if (line < l.lineCount) newPage()
        }
        y += spaceAfter
    }

    fun pageBreak() = newPage()

    /** Таблица с рамками; ширина колонок — по длине текста. */
    fun table(rows: List<List<String>>, fontSize: Float = 9.5f) {
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.size }
        if (cols == 0) return
        val paint = TextPaint(body).apply { textSize = fontSize }
        val boldPaint = TextPaint(bold).apply { textSize = fontSize }
        val lens = IntArray(cols) { c -> rows.maxOf { min(40, it.getOrNull(c)?.length ?: 0) } + 2 }
        val total = lens.sum().toFloat()
        val widths = FloatArray(cols) { contentW * lens[it] / total }
        val pad = 3f
        rows.forEachIndexed { ri, row ->
            val header = ri == 0
            val layouts = (0 until cols).map { c ->
                layout(row.getOrNull(c) ?: "", if (header) boldPaint else paint, (widths[c] - pad * 2).toInt())
            }
            val h = layouts.maxOf { it.height } + pad * 2
            ensure(min(h, (pageH - margin * 2).toFloat()))
            val c = canvas!!
            if (header) c.drawRect(margin, y, margin + contentW, y + h, headerFill)
            var x = margin
            for (i in 0 until cols) {
                c.save()
                c.translate(x + pad, y + pad)
                c.clipRect(0f, 0f, widths[i] - pad * 2, h - pad)
                layouts[i].draw(c)
                c.restore()
                c.drawLine(x, y, x, y + h, linePaint)
                x += widths[i]
            }
            c.drawLine(x, y, x, y + h, linePaint)
            c.drawLine(margin, y, margin + contentW, y, linePaint)
            c.drawLine(margin, y + h, margin + contentW, y + h, linePaint)
            y += h
        }
        y += 8f
    }

    fun save(out: File): File {
        if (pageNo == 0) newPage()
        finishPage()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    companion object {
        fun fromText(text: String, out: File, title: String? = null): File {
            val t = TextPdf()
            if (!title.isNullOrBlank()) t.paragraph(title, 16f, isBold = true, spaceAfter = 12f)
            val pages = text.split(OfficeWriter.PAGE_BREAK)
            pages.forEachIndexed { i, p ->
                if (i > 0) t.pageBreak()
                p.replace("\r", "").split(Regex("\n\\s*\n")).forEach { para -> t.paragraph(para.trim('\n')) }
            }
            return t.save(out)
        }

        fun fromDocx(blocks: List<OfficeReader.Block>, out: File): File {
            val t = TextPdf()
            for (b in blocks) when (b) {
                is OfficeReader.Block.Para -> when (b.level) {
                    1 -> t.paragraph(b.text, 18f, true, b.center, 10f)
                    2 -> t.paragraph(b.text, 15f, true, b.center, 8f)
                    3 -> t.paragraph(b.text, 13f, true, b.center, 6f)
                    else -> t.paragraph(b.text, 11f, b.bold, b.center, 6f)
                }
                is OfficeReader.Block.Table -> t.table(b.rows)
                OfficeReader.Block.PageBreak -> t.pageBreak()
            }
            return t.save(out)
        }

        fun fromSheets(sheets: List<OfficeReader.Sheet>, out: File): File {
            val wide = sheets.any { s -> (s.rows.maxOfOrNull { it.size } ?: 0) > 6 }
            val t = TextPdf(landscape = wide)
            sheets.forEachIndexed { i, s ->
                if (i > 0) t.pageBreak()
                t.paragraph(s.name, 14f, true, spaceAfter = 8f)
                t.table(s.rows.filter { r -> r.any { it.isNotBlank() } })
            }
            return t.save(out)
        }
    }
}
