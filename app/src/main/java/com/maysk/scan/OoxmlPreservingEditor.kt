package com.maysk.scan

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * A conservative editor for OOXML packages. It edits existing text nodes only,
 * leaving styles, relationships, media, themes, formulas, slide layouts and
 * all untouched ZIP parts intact. It deliberately does not rebuild documents.
 */
object OoxmlPreservingEditor {
    data class Field(
        val part: String,
        val namespace: String,
        val localName: String,
        val nodeIndex: Int,
        val label: String,
        val value: String
    )

    data class Model(val fields: List<Field>, val note: String, val preview: String)

    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val DRAW_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

    fun read(file: File, extension: String): Model = ZipFile(file).use { zip ->
        when (extension.lowercase()) {
            "docx" -> readWord(zip)
            "pptx" -> readPowerPoint(zip)
            "xlsx" -> readExcel(zip)
            else -> throw IllegalArgumentException("Формат .$extension не поддерживается OOXML-редактором")
        }
    }

    private fun readWord(zip: ZipFile): Model {
        val parts = zip.entries().asSequence().map { it.name }
            .filter { it.startsWith("word/") && it.endsWith(".xml") &&
                (it == "word/document.xml" || it.startsWith("word/header") || it.startsWith("word/footer") ||
                    it == "word/footnotes.xml" || it == "word/endnotes.xml" || it == "word/comments.xml") }
            .sorted().toList()
        val fields = mutableListOf<Field>()
        parts.forEach { part ->
            val doc = parse(zip.getInputStream(zip.getEntry(part)).use { it.readBytes() })
            val nodes = doc.getElementsByTagNameNS(WORD_NS, "t")
            for (i in 0 until nodes.length) {
                val value = nodes.item(i).textContent ?: ""
                if (value.isNotEmpty()) fields += Field(part, WORD_NS, "t", i,
                    "${wordPartLabel(part)} · фрагмент ${i + 1}", value)
            }
        }
        return Model(fields, "Редактируются существующие фрагменты текста. Шрифты, абзацы, таблицы, изображения, колонтитулы и прочие элементы пакета остаются на месте. Для сохранения исходной структуры изменяй текстовые фрагменты по отдельности.", fields.joinToString(" ") { it.value })
    }

    private fun wordPartLabel(part: String): String = when {
        part == "word/document.xml" -> "Основной текст"
        part.startsWith("word/header") -> "Колонтитул"
        part.startsWith("word/footer") -> "Нижний колонтитул"
        part.contains("footnotes") -> "Сноска"
        part.contains("endnotes") -> "Концевая сноска"
        else -> "Комментарий"
    }

    private fun readPowerPoint(zip: ZipFile): Model {
        val parts = zip.entries().asSequence().map { it.name }
            .filter { it.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) }
            .sortedBy { it.substringAfter("slide").substringBefore(".").toIntOrNull() ?: 0 }
        val fields = mutableListOf<Field>()
        parts.forEach { part ->
            val slideNo = part.substringAfterLast("slide").substringBefore(".")
            val doc = parse(zip.getInputStream(zip.getEntry(part)).use { it.readBytes() })
            val nodes = doc.getElementsByTagNameNS(DRAW_NS, "t")
            for (i in 0 until nodes.length) {
                val value = nodes.item(i).textContent ?: ""
                if (value.isNotEmpty()) fields += Field(part, DRAW_NS, "t", i,
                    "Слайд $slideNo · текстовый фрагмент ${i + 1}", value)
            }
        }
        return Model(fields, "Изменяются только существующие текстовые фрагменты слайдов. Макеты, тема, шрифты, изображения, диаграммы, переходы и анимация остаются в исходном пакете.", fields.joinToString("\n") { it.value })
    }

    private fun readExcel(zip: ZipFile): Model {
        val fields = mutableListOf<Field>()
        val refsBySharedIndex = mutableMapOf<Int, MutableList<String>>()
        val workbookEntry = zip.getEntry("xl/workbook.xml")
        val relsEntry = zip.getEntry("xl/_rels/workbook.xml.rels")
        val sheetEntries = mutableListOf<Pair<String, String>>()
        if (workbookEntry != null && relsEntry != null) {
            val wb = parse(zip.getInputStream(workbookEntry).use { it.readBytes() })
            val rels = parse(zip.getInputStream(relsEntry).use { it.readBytes() })
            val targets = mutableMapOf<String, String>()
            val relNodes = rels.getElementsByTagNameNS(PKG_REL_NS, "Relationship")
            for (i in 0 until relNodes.length) {
                val el = relNodes.item(i) as Element
                targets[el.getAttribute("Id")] = resolvePart("xl/workbook.xml", el.getAttribute("Target"))
            }
            val sheets = wb.getElementsByTagNameNS(SHEET_NS, "sheet")
            for (i in 0 until sheets.length) {
                val el = sheets.item(i) as Element
                val relId = el.getAttributeNS(REL_NS, "id")
                targets[relId]?.let { sheetEntries += (el.getAttribute("name") to it) }
            }
        }
        // Record which cells use each shared-string item. Editing a shared string changes all its references.
        sheetEntries.forEach { (sheetName, part) ->
            val entry = zip.getEntry(part) ?: return@forEach
            val doc = parse(zip.getInputStream(entry).use { it.readBytes() })
            val cells = doc.getElementsByTagNameNS(SHEET_NS, "c")
            for (i in 0 until cells.length) {
                val cell = cells.item(i) as Element
                if (cell.getAttribute("t") == "s") {
                    val v = cell.getElementsByTagNameNS(SHEET_NS, "v")
                    if (v.length > 0) {
                        val idx = v.item(0).textContent.toIntOrNull()
                        if (idx != null) refsBySharedIndex.getOrPut(idx) { mutableListOf() }.add("$sheetName!${cell.getAttribute("r")}")
                    }
                }
            }
        }
        val shared = zip.getEntry("xl/sharedStrings.xml")
        if (shared != null) {
            val doc = parse(zip.getInputStream(shared).use { it.readBytes() })
            val items = doc.getElementsByTagNameNS(SHEET_NS, "si")
            for (si in 0 until items.length) {
                val item = items.item(si) as Element
                val textNodes = item.getElementsByTagNameNS(SHEET_NS, "t")
                for (run in 0 until textNodes.length) {
                    val value = textNodes.item(run).textContent ?: ""
                    if (value.isNotEmpty()) {
                        val refs = refsBySharedIndex[si].orEmpty().distinct().take(6)
                        val suffix = if (refs.isEmpty()) "" else " · ${refs.joinToString(", ")}" + if (refsBySharedIndex[si].orEmpty().size > 6) "…" else ""
                        fields += Field("xl/sharedStrings.xml", SHEET_NS, "t", globalTextIndex(doc, textNodes.item(run)),
                            "Общий текст ${si + 1}, фрагмент ${run + 1}$suffix", value)
                    }
                }
            }
        }
        // Inline strings and literal numeric values are stored directly in worksheet XML.
        sheetEntries.forEach { (sheetName, part) ->
            val entry = zip.getEntry(part) ?: return@forEach
            val doc = parse(zip.getInputStream(entry).use { it.readBytes() })
            val cells = doc.getElementsByTagNameNS(SHEET_NS, "c")
            for (i in 0 until cells.length) {
                val cell = cells.item(i) as Element
                val ref = cell.getAttribute("r")
                if (cell.getAttribute("t") == "s") continue
                if (cell.getElementsByTagNameNS(SHEET_NS, "f").length > 0) continue // formula and cached result are kept intact
                val inline = cell.getAttribute("t") == "inlineStr"
                val nodeName = if (inline) "t" else "v"
                val nodes = cell.getElementsByTagNameNS(SHEET_NS, nodeName)
                for (n in 0 until nodes.length) {
                    val value = nodes.item(n).textContent ?: ""
                    if (value.isNotEmpty()) {
                        fields += Field(part, SHEET_NS, nodeName, globalIndex(doc, SHEET_NS, nodeName, nodes.item(n)),
                            "$sheetName!$ref", value)
                    }
                }
            }
        }
        val preview = fields.joinToString("\n") { "${it.label}: ${it.value}" }
        return Model(fields, "Редактируются существующие значения ячеек и текстовые фрагменты. Формулы, стили, ширина столбцов, объединения, диаграммы и листы сохраняются. Внимание: если одинаковая строка используется несколькими ячейками через общий shared-string, изменение её фрагмента отразится во всех таких ячейках.", preview)
    }

    private fun globalTextIndex(doc: Document, node: org.w3c.dom.Node): Int =
        globalIndex(doc, SHEET_NS, "t", node)

    private fun globalIndex(doc: Document, ns: String, local: String, target: org.w3c.dom.Node): Int {
        val all = doc.getElementsByTagNameNS(ns, local)
        for (i in 0 until all.length) if (all.item(i).isSameNode(target)) return i
        return -1
    }

    private fun resolvePart(base: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parent = base.substringBeforeLast('/')
        val stack = (parent.split('/') + target.split('/')).toMutableList()
        val out = mutableListOf<String>()
        stack.forEach { when (it) { "", "." -> Unit; ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex); else -> out += it } }
        return out.joinToString("/")
    }

    /** Writes edited text nodes into a copy of the original package. */
    fun save(original: File, destination: File, edits: Map<Field, String>) {
        val grouped = edits.entries.groupBy { it.key.part }
        val replacement = mutableMapOf<String, ByteArray>()
        ZipFile(original).use { zip ->
            grouped.forEach { (part, entries) ->
                val zipEntry = zip.getEntry(part) ?: throw IllegalStateException("В документе отсутствует часть $part")
                val doc = parse(zip.getInputStream(zipEntry).use { it.readBytes() })
                entries.forEach { (field, value) ->
                    val nodes = doc.getElementsByTagNameNS(field.namespace, field.localName)
                    if (field.nodeIndex !in 0 until nodes.length) throw IllegalStateException("Структура документа изменилась: ${field.label}")
                    nodes.item(field.nodeIndex).textContent = value
                }
                replacement[part] = serialize(doc)
            }
            FileOutputStream(destination).use { fos -> ZipOutputStream(fos).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val old = entries.nextElement()
                    val newEntry = ZipEntry(old.name)
                    newEntry.time = old.time
                    out.putNextEntry(newEntry)
                    val bytes = replacement[old.name]
                    if (bytes != null) out.write(bytes) else zip.getInputStream(old).use { it.copyTo(out) }
                    out.closeEntry()
                }
            } }
        }
        // Basic OPC integrity check before returning a candidate for save.
        ZipFile(destination).use { check ->
            if (check.getEntry("[Content_Types].xml") == null) throw IllegalStateException("Повреждённый офисный пакет")
            val required = when (original.extension.lowercase()) {
                "docx" -> "word/document.xml"
                "xlsx" -> "xl/workbook.xml"
                "pptx" -> "ppt/presentation.xml"
                else -> "[Content_Types].xml"
            }
            if (check.getEntry(required) == null) throw IllegalStateException("Не найдена основная часть документа")
        }
    }

    private fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun serialize(doc: Document): ByteArray {
        val factory = TransformerFactory.newInstance().apply {
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "") }
        }
        val transformer = factory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }
        return ByteArrayOutputStream().use { out ->
            transformer.transform(DOMSource(doc), StreamResult(out))
            out.toByteArray()
        }
    }
}
