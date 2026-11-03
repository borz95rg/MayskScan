package com.maysk.scan

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Создание документов Word, Excel, PowerPoint и текстовых форматов без интернета.
 * Пишем минимальные, но правильные файлы Office Open XML.
 */
object OfficeWriter {

    /** Символ разрыва страницы внутри текста. */
    const val PAGE_BREAK = '\u000C'

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> if (ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.put(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    // ---------- Word ----------

    /** Текст → .docx. Абзацы разделяются пустой строкой, разрыв страницы — символ \u000C. */
    fun docx(text: String, out: OutputStream, title: String = "") {
        val body = StringBuilder()
        if (title.isNotBlank()) {
            body.append("<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr><w:r><w:t xml:space=\"preserve\">")
                .append(esc(title)).append("</w:t></w:r></w:p>")
        }
        val pages = text.split(PAGE_BREAK)
        pages.forEachIndexed { pi, page ->
            if (pi > 0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            val paragraphs = page.replace("\r", "").split(Regex("\n\\s*\n"))
            for (para in paragraphs) {
                if (para.isBlank()) continue
                body.append("<w:p>")
                val lines = para.trim('\n').split('\n')
                lines.forEachIndexed { li, line ->
                    if (li > 0) body.append("<w:r><w:br/></w:r>")
                    body.append("<w:r><w:t xml:space=\"preserve\">").append(esc(line)).append("</w:t></w:r>")
                }
                body.append("</w:p>")
            }
        }
        val document = XML +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1134\" w:right=\"850\" w:bottom=\"1134\" w:left=\"1701\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"
        val styles = XML +
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\" w:eastAsia=\"Calibri\"/>" +
            "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/><w:lang w:val=\"ru-RU\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:rPr><w:b/><w:sz w:val=\"36\"/><w:szCs w:val=\"36\"/></w:rPr></w:style>" +
            "</w:styles>"
        ZipOutputStream(out).use { z ->
            z.put("[Content_Types].xml", XML +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                "</Types>")
            z.put("_rels/.rels", XML +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>")
            z.put("word/_rels/document.xml.rels", XML +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                "</Relationships>")
            z.put("word/document.xml", document)
            z.put("word/styles.xml", styles)
        }
    }

    // ---------- Excel ----------

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, ('A' + r))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    /** Таблица → .xlsx. Числа записываются как числа. Несколько листов: имя → строки. */
    fun xlsx(sheets: List<Pair<String, List<List<String>>>>, out: OutputStream) {
        val list = sheets.ifEmpty { listOf("Лист1" to emptyList()) }
        ZipOutputStream(out).use { z ->
            val overrides = StringBuilder()
            list.indices.forEach { i ->
                overrides.append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            }
            z.put("[Content_Types].xml", XML +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                overrides + "</Types>")
            z.put("_rels/.rels", XML +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>")
            val sheetTags = StringBuilder()
            val rels = StringBuilder()
            val used = HashSet<String>()
            list.forEachIndexed { i, (name, _) ->
                var n = name.replace(Regex("[\\\\/?*\\[\\]:]"), " ").take(31).ifBlank { "Лист${i + 1}" }
                while (!used.add(n)) n = (n.take(28) + " " + (i + 1))
                sheetTags.append("<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
                rels.append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>")
            }
            rels.append("<Relationship Id=\"rId${list.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            z.put("xl/workbook.xml", XML +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>$sheetTags</sheets></workbook>")
            z.put("xl/_rels/workbook.xml.rels", XML +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels</Relationships>")
            z.put("xl/styles.xml", XML +
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>" +
                "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
                "</styleSheet>")
            val numRe = Regex("^-?\\d{1,15}([.,]\\d+)?$")
            list.forEachIndexed { si, (_, rows) ->
                val sb = StringBuilder()
                val maxCols = rows.maxOfOrNull { it.size } ?: 0
                sb.append(XML).append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                if (maxCols > 0) {
                    sb.append("<cols>")
                    for (c in 0 until maxCols) {
                        val width = (rows.maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 8).coerceIn(8, 60) + 2
                        sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$width\" customWidth=\"1\"/>")
                    }
                    sb.append("</cols>")
                }
                sb.append("<sheetData>")
                rows.forEachIndexed { ri, row ->
                    sb.append("<row r=\"${ri + 1}\">")
                    row.forEachIndexed { ci, v ->
                        if (v.isEmpty()) return@forEachIndexed
                        val ref = colName(ci) + (ri + 1)
                        val t = v.trim()
                        if (numRe.matches(t) && !(t.length > 1 && t.startsWith("0") && !t.startsWith("0,") && !t.startsWith("0."))) {
                            sb.append("<c r=\"$ref\"><v>${t.replace(',', '.')}</v></c>")
                        } else {
                            sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                z.put("xl/worksheets/sheet${si + 1}.xml", sb.toString())
            }
        }
    }

    // ---------- PowerPoint ----------

    class Slide(val jpeg: ByteArray, val width: Int, val height: Int)

    /** Каждая картинка → отдельный слайд во весь экран (16:9). */
    fun pptx(slides: List<Slide>, out: OutputStream) {
        val cx = 12192000L // 16:9, 13.333 дюйма
        val cy = 6858000L
        ZipOutputStream(out).use { z ->
            val ov = StringBuilder()
            slides.indices.forEach { ov.append("<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>") }
            z.put("[Content_Types].xml", XML +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
                "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
                "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
                "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
                ov + "</Types>")
            z.put("_rels/.rels", XML +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
                "</Relationships>")
            val sldIds = StringBuilder()
            val presRels = StringBuilder("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>")
            slides.indices.forEach { i ->
                sldIds.append("<p:sldId id=\"${256 + i}\" r:id=\"rId${i + 3}\"/>")
                presRels.append("<Relationship Id=\"rId${i + 3}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${i + 1}.xml\"/>")
            }
            z.put("ppt/presentation.xml", XML +
                "<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
                "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
                "<p:sldIdLst>$sldIds</p:sldIdLst>" +
                "<p:sldSz cx=\"$cx\" cy=\"$cy\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
            z.put("ppt/_rels/presentation.xml.rels", XML +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$presRels</Relationships>")
            val emptyTree = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
                "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>"
            val ns = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
            z.put("ppt/slideMasters/slideMaster1.xml", XML +
                "<p:sldMaster $ns>$emptyTree" +
                "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
                "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>")
            z.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", XML +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>" +
                "</Relationships>")
            z.put("ppt/slideLayouts/slideLayout1.xml", XML +
                "<p:sldLayout $ns type=\"blank\" preserve=\"1\">$emptyTree<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
            z.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", XML +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
                "</Relationships>")
            z.put("ppt/theme/theme1.xml", THEME)
            slides.forEachIndexed { i, s ->
                // Вписываем картинку в слайд с сохранением пропорций
                val k = minOf(cx.toDouble() / s.width, cy.toDouble() / s.height)
                val w = (s.width * k).toLong()
                val h = (s.height * k).toLong()
                val x = (cx - w) / 2
                val y = (cy - h) / 2
                z.put("ppt/slides/slide${i + 1}.xml", XML +
                    "<p:sld $ns><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
                    "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
                    "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Page ${i + 1}\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
                    "<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
                    "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>" +
                    "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
                z.put("ppt/slides/_rels/slide${i + 1}.xml.rels", XML +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image${i + 1}.jpeg\"/>" +
                    "</Relationships>")
                z.put("ppt/media/image${i + 1}.jpeg", s.jpeg)
            }
        }
    }

    private val THEME = XML +
        "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Maysk\"><a:themeElements>" +
        "<a:clrScheme name=\"Maysk\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"0B1A2E\"/></a:dk2><a:lt2><a:srgbClr val=\"E7EEF8\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"1E88FF\"/></a:accent1><a:accent2><a:srgbClr val=\"22B366\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"E5484D\"/></a:accent3><a:accent4><a:srgbClr val=\"8B5CF6\"/></a:accent4>" +
        "<a:accent5><a:srgbClr val=\"F59E0B\"/></a:accent5><a:accent6><a:srgbClr val=\"14B8A6\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
        "<a:fontScheme name=\"Maysk\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"Maysk\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
        "<a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>" +
        "<a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>" +
        "<a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
        "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
        "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"

    /** Текстовые слайды → редактируемый .pptx. Изображения и исходный дизайн не переносятся. */
    fun pptxText(slides: List<String>, out: OutputStream) {
        val list = slides.ifEmpty { listOf("") }
        val cx = 12192000L
        val cy = 6858000L
        ZipOutputStream(out).use { z ->
            val overrides = StringBuilder()
            list.indices.forEach { i ->
                overrides.append("<Override PartName=\"/ppt/slides/slide${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
            }
            z.put("[Content_Types].xml", XML +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
                "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
                "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
                "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" + overrides + "</Types>")
            z.put("_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>")
            val ids = StringBuilder()
            val rels = StringBuilder("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>")
            list.indices.forEach { i ->
                ids.append("<p:sldId id=\"${256 + i}\" r:id=\"rId${i + 3}\"/>")
                rels.append("<Relationship Id=\"rId${i + 3}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${i + 1}.xml\"/>")
            }
            val ns = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
            z.put("ppt/presentation.xml", XML + "<p:presentation $ns><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>$ids</p:sldIdLst><p:sldSz cx=\"$cx\" cy=\"$cy\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
            z.put("ppt/_rels/presentation.xml.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels</Relationships>")
            val emptyTree = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>"
            z.put("ppt/slideMasters/slideMaster1.xml", XML + "<p:sldMaster $ns>$emptyTree<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>")
            z.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/></Relationships>")
            z.put("ppt/slideLayouts/slideLayout1.xml", XML + "<p:sldLayout $ns type=\"blank\" preserve=\"1\">$emptyTree<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
            z.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>")
            z.put("ppt/theme/theme1.xml", THEME)
            list.forEachIndexed { i, slide ->
                val paragraphs = slide.split('\n').joinToString("") { line ->
                    "<a:p><a:r><a:rPr lang=\"ru-RU\" sz=\"2200\"/><a:t xml:space=\"preserve\">${esc(line)}</a:t></a:r><a:endParaRPr lang=\"ru-RU\"/></a:p>"
                }
                val shape = "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Текст слайда\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"914400\" y=\"685800\"/><a:ext cx=\"10363200\" cy=\"5486400\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/>$paragraphs</p:txBody></p:sp>"
                val tree = emptyTree.replace("</p:spTree>", "$shape</p:spTree>")
                z.put("ppt/slides/slide${i + 1}.xml", XML + "<p:sld $ns>${tree}<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
                z.put("ppt/slides/_rels/slide${i + 1}.xml.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>")
            }
        }
    }

    // ---------- Текстовые форматы ----------

    fun rtf(text: String): String {
        val sb = StringBuilder("{\\rtf1\\ansi\\ansicpg1251\\deff0{\\fonttbl{\\f0 Calibri;}}\\fs24 ")
        for (ch in text) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '{' -> sb.append("\\{")
                ch == '}' -> sb.append("\\}")
                ch == '\n' -> sb.append("\\par\n")
                ch == PAGE_BREAK -> sb.append("\\page\n")
                ch.code < 128 -> sb.append(ch)
                else -> sb.append("\\u").append(ch.code.toShort().toInt()).append('?')
            }
        }
        return sb.append('}').toString()
    }

    fun html(text: String, title: String): String {
        val paras = text.replace(PAGE_BREAK, '\n').split(Regex("\n\\s*\n")).filter { it.isNotBlank() }
            .joinToString("\n") { "<p>" + esc(it.trim()).replace("\n", "<br>") + "</p>" }
        return "<!DOCTYPE html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\"><title>${esc(title)}</title>" +
            "<style>body{font-family:Arial,sans-serif;max-width:800px;margin:40px auto;line-height:1.5;padding:0 16px}</style>" +
            "</head><body>\n<h1>${esc(title)}</h1>\n$paras\n</body></html>\n"
    }

    fun markdown(text: String, title: String): String =
        "# $title\n\n" + text.replace(PAGE_BREAK.toString(), "\n\n---\n\n").trim() + "\n"

    fun csv(rows: List<List<String>>): String = rows.joinToString("\r\n") { r ->
        r.joinToString(";") { v -> if (v.any { it == ';' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v }
    }

    /**
     * Простое распознавание таблицы в тексте: столбцы разделены табуляцией
     * или двумя и более пробелами. Строки без разделителей — одна ячейка.
     */
    fun textToRows(text: String): List<List<String>> =
        text.replace(PAGE_BREAK, '\n').split('\n').filter { it.isNotBlank() }
            .map { line -> line.trim().split(Regex("\t+| {2,}|\\s\\|\\s")).map { it.trim() } }

    fun looksLikeTable(text: String): Boolean {
        val rows = textToRows(text)
        if (rows.size < 2) return false
        val multi = rows.count { it.size >= 2 }
        return multi >= rows.size * 0.6
    }
}
