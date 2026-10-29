package com.maysk.scan

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Чтение Word (.docx) и Excel (.xlsx) без интернета — для перевода в PDF и текст. */
object OfficeReader {

    sealed class Block {
        /** level: 0 — обычный абзац, 1..3 — заголовок. */
        class Para(val text: String, val bold: Boolean, val level: Int, val center: Boolean) : Block()
        class Table(val rows: List<List<String>>) : Block()
        object PageBreak : Block()
    }

    class Sheet(val name: String, val rows: List<List<String>>)

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun parse(bytes: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun ZipFile.bytes(name: String): ByteArray? = getEntry(name)?.let { e -> getInputStream(e).use { it.readBytes() } }

    private fun Element.children(ns: String, local: String): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == ns && n.localName == local) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(ns: String, local: String): Element? = children(ns, local).firstOrNull()

    // ---------- Word ----------

    fun docx(file: File): List<Block> {
        ZipFile(file).use { zip ->
            val xml = zip.bytes("word/document.xml") ?: throw IllegalStateException("Это не файл Word (.docx)")
            val doc = parse(xml)
            val body = doc.getElementsByTagNameNS(W, "body").item(0) as? Element ?: return emptyList()
            val out = ArrayList<Block>()
            var n: Node? = body.firstChild
            while (n != null) {
                if (n is Element && n.namespaceURI == W) {
                    when (n.localName) {
                        "p" -> paragraph(n, out)
                        "tbl" -> out.add(Block.Table(table(n)))
                    }
                }
                n = n.nextSibling
            }
            return out
        }
    }

    private fun paragraph(p: Element, out: MutableList<Block>) {
        val pPr = p.child(W, "pPr")
        val style = pPr?.child(W, "pStyle")?.getAttributeNS(W, "val") ?: ""
        val level = when {
            style.equals("Title", true) -> 1
            style.startsWith("Heading", true) || style.startsWith("Заголовок", true) ->
                style.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 3) ?: 2
            else -> 0
        }
        val center = pPr?.child(W, "jc")?.getAttributeNS(W, "val") == "center"
        val sb = StringBuilder()
        var boldChars = 0
        var pageBreakAfter = false
        val runs = p.getElementsByTagNameNS(W, "r")
        for (i in 0 until runs.length) {
            val r = runs.item(i) as Element
            val bold = r.child(W, "rPr")?.child(W, "b")?.let { it.getAttributeNS(W, "val") != "0" && it.getAttributeNS(W, "val") != "false" } ?: false
            var c: Node? = r.firstChild
            while (c != null) {
                if (c is Element && c.namespaceURI == W) {
                    when (c.localName) {
                        "t" -> { sb.append(c.textContent); if (bold) boldChars += c.textContent.length }
                        "tab" -> sb.append('\t')
                        "br" -> if (c.getAttributeNS(W, "type") == "page") pageBreakAfter = true else sb.append('\n')
                        "cr" -> sb.append('\n')
                    }
                }
                c = c.nextSibling
            }
        }
        val text = sb.toString()
        out.add(Block.Para(text, text.isNotEmpty() && boldChars >= text.trim().length * 0.6, level, center))
        if (pageBreakAfter) out.add(Block.PageBreak)
    }

    private fun table(tbl: Element): List<List<String>> {
        val rows = ArrayList<List<String>>()
        for (tr in tbl.children(W, "tr")) {
            val cells = ArrayList<String>()
            for (tc in tr.children(W, "tc")) {
                val parts = ArrayList<String>()
                for (p in tc.children(W, "p")) {
                    val ts = p.getElementsByTagNameNS(W, "t")
                    val sb = StringBuilder()
                    for (i in 0 until ts.length) sb.append(ts.item(i).textContent)
                    parts.add(sb.toString())
                }
                cells.add(parts.joinToString("\n").trim())
            }
            rows.add(cells)
        }
        return rows
    }

    /** Весь текст документа Word одной строкой (для поиска, перевода, TXT). */
    fun docxText(file: File): String = docx(file).joinToString("\n\n") { b ->
        when (b) {
            is Block.Para -> b.text
            is Block.Table -> b.rows.joinToString("\n") { it.joinToString("\t") }
            Block.PageBreak -> OfficeWriter.PAGE_BREAK.toString()
        }
    }.replace(Regex("\n{3,}"), "\n\n").trim()

    // ---------- Excel ----------

    fun xlsx(file: File): List<Sheet> {
        ZipFile(file).use { zip ->
            val wbXml = zip.bytes("xl/workbook.xml") ?: throw IllegalStateException("Это не файл Excel (.xlsx)")
            // Общие строки
            val shared = ArrayList<String>()
            zip.bytes("xl/sharedStrings.xml")?.let { bytes ->
                val d = parse(bytes)
                val sis = d.getElementsByTagNameNS(S, "si")
                for (i in 0 until sis.length) {
                    val si = sis.item(i) as Element
                    val ts = si.getElementsByTagNameNS(S, "t")
                    val sb = StringBuilder()
                    for (j in 0 until ts.length) {
                        // пропускаем фонетические подсказки (rPh)
                        if ((ts.item(j).parentNode as? Element)?.localName == "rPh") continue
                        sb.append(ts.item(j).textContent)
                    }
                    shared.add(sb.toString())
                }
            }
            // Связи листов
            val rels = HashMap<String, String>()
            zip.bytes("xl/_rels/workbook.xml.rels")?.let { bytes ->
                val d = parse(bytes)
                val list = d.getElementsByTagName("Relationship")
                for (i in 0 until list.length) {
                    val e = list.item(i) as Element
                    rels[e.getAttribute("Id")] = e.getAttribute("Target")
                }
            }
            val wb = parse(wbXml)
            val sheetEls = wb.getElementsByTagNameNS(S, "sheet")
            val out = ArrayList<Sheet>()
            for (i in 0 until sheetEls.length) {
                val se = sheetEls.item(i) as Element
                val name = se.getAttribute("name")
                val rid = se.getAttributeNS(R, "id")
                var target = rels[rid] ?: "worksheets/sheet${i + 1}.xml"
                target = target.removePrefix("/xl/").removePrefix("/")
                val path = if (target.startsWith("xl/")) target else "xl/$target"
                val bytes = zip.bytes(path) ?: continue
                out.add(Sheet(name, sheetRows(parse(bytes), shared)))
            }
            return out
        }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1) else break
        }
        return n - 1
    }

    private fun sheetRows(d: org.w3c.dom.Document, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val rowEls = d.getElementsByTagNameNS(S, "row")
        var lastRow = 0
        for (i in 0 until rowEls.length) {
            val re = rowEls.item(i) as Element
            val rnum = re.getAttribute("r").toIntOrNull() ?: (lastRow + 1)
            // пустые строки между
            while (lastRow + 1 < rnum && rows.size < 10000) { rows.add(emptyList()); lastRow++ }
            lastRow = rnum
            val cells = ArrayList<String>()
            for (c in re.children(S, "c")) {
                val idx = colIndex(c.getAttribute("r")).takeIf { it >= 0 } ?: cells.size
                while (cells.size < idx) cells.add("")
                val t = c.getAttribute("t")
                val v = c.child(S, "v")?.textContent ?: ""
                val value = when (t) {
                    "s" -> v.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> c.child(S, "is")?.textContent ?: ""
                    "b" -> if (v == "1") "ИСТИНА" else "ЛОЖЬ"
                    else -> formatNumber(v)
                }
                if (cells.size == idx) cells.add(value) else cells[idx] = value
            }
            rows.add(cells)
        }
        // Убираем хвост пустых строк
        while (rows.isNotEmpty() && rows.last().all { it.isBlank() }) rows.removeAt(rows.size - 1)
        return rows
    }

    private fun formatNumber(v: String): String {
        val d = v.toDoubleOrNull() ?: return v
        return if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString()
        else String.format(java.util.Locale.US, "%.6f", d).trimEnd('0').trimEnd('.')
    }
}
