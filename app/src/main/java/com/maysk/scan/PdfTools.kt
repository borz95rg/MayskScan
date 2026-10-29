package com.maysk.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Все операции с PDF (PdfBox) — работают на телефоне без интернета. */
object PdfTools {

    private fun mem() = MemoryUsageSetting.setupMixed(64L * 1024 * 1024)

    fun open(f: File, password: String? = null): PDDocument =
        if (password.isNullOrEmpty()) PDDocument.load(f, mem()) else PDDocument.load(f, password, mem())

    fun isEncrypted(f: File): Boolean = try {
        PDDocument.load(f, mem()).use { false }
    } catch (e: Exception) {
        e.javaClass.simpleName.contains("InvalidPassword") || e.message?.contains("password", true) == true
    }

    fun pageCount(f: File, password: String? = null): Int = open(f, password).use { it.numberOfPages }

    // ---------- Страницы ----------

    /**
     * Собирает новый PDF из страниц исходного в заданном порядке.
     * order — индексы страниц (можно повторять — копия страницы), rotations — поворот по индексу в order.
     */
    fun rebuild(src: File, order: List<Int>, rotations: Map<Int, Int>, out: File, password: String? = null) {
        open(src, password).use { doc ->
            PDDocument().use { dst ->
                order.forEachIndexed { i, idx ->
                    val page = dst.importPage(doc.getPage(idx))
                    val rot = rotations[i] ?: 0
                    if (rot != 0) page.rotation = ((page.rotation + rot) % 360 + 360) % 360
                }
                dst.save(out)
            }
        }
    }

    fun extract(src: File, pages: List<Int>, out: File, password: String? = null) = rebuild(src, pages, emptyMap(), out, password)

    /** Страница для сборки: страница PDF (index ≥ 0) или картинка (index = -1). */
    class Item(val file: File, val index: Int, var rotation: Int = 0)

    /** Собирает PDF из страниц разных файлов и картинок в заданном порядке. */
    fun build(items: List<Item>, out: File) {
        val opened = HashMap<String, PDDocument>()
        try {
            PDDocument().use { dst ->
                var ref: PDRectangle = PDRectangle.A4
                for (it in items) {
                    if (it.index >= 0) {
                        val src = opened.getOrPut(it.file.absolutePath) { open(it.file) }
                        val page = dst.importPage(src.getPage(it.index))
                        ref = page.mediaBox
                        if (it.rotation != 0) page.rotation = ((page.rotation + it.rotation) % 360 + 360) % 360
                    } else {
                        val bmp = ImageTools.rotate(ImageTools.decodeFile(it.file, 2600), it.rotation)
                        dst.addPage(imagePage(dst, bmp, ref))
                        bmp.recycle()
                    }
                }
                dst.save(out)
            }
        } finally {
            opened.values.forEach { try { it.close() } catch (e: Exception) {} }
        }
    }

    /** Вставить картинки как новые страницы в позицию at (0 — в начало). */
    fun insertImages(src: File, images: List<Bitmap>, at: Int, out: File) {
        open(src).use { doc ->
            val count = doc.numberOfPages
            val ref = if (count > 0) doc.getPage(min(at, count - 1)).mediaBox else PDRectangle.A4
            var pos = at.coerceIn(0, count)
            for (bmp in images) {
                val page = imagePage(doc, bmp, ref)
                insertPage(doc, page, pos)
                pos++
            }
            doc.save(out)
        }
    }

    /** Заменить страницу картинкой. */
    fun replaceWithImage(src: File, index: Int, image: Bitmap, out: File) {
        open(src).use { doc ->
            val old = doc.getPage(index)
            val page = imagePage(doc, image, old.mediaBox)
            insertPage(doc, page, index)
            doc.removePage(index + 1)
            doc.save(out)
        }
    }

    private fun insertPage(doc: PDDocument, page: PDPage, pos: Int) {
        val pages = doc.pages
        if (pos >= pages.count) pages.add(page) else pages.insertBefore(page, pages.get(pos))
    }

    private fun imagePage(doc: PDDocument, bmp: Bitmap, ref: PDRectangle): PDPage {
        // Страница по формату соседних, картинка вписывается с полями
        val landscape = bmp.width > bmp.height
        val w = if (landscape) max(ref.width, ref.height) else min(ref.width, ref.height)
        val h = if (landscape) min(ref.width, ref.height) else max(ref.width, ref.height)
        val page = PDPage(PDRectangle(w, h))
        val img = JPEGFactory.createFromImage(doc, bmp, 0.88f)
        val k = min(w / bmp.width, h / bmp.height)
        val iw = bmp.width * k
        val ih = bmp.height * k
        PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, (w - iw) / 2, (h - ih) / 2, iw, ih) }
        return page
    }

    // ---------- Создание ----------

    /** Картинки → PDF. fitA4 — каждая на лист A4, иначе страница по размеру картинки. */
    fun imagesToPdf(images: List<File>, out: File, fitA4: Boolean = true, quality: Float = 0.88f, onPage: (Int) -> Unit = {}) {
        PDDocument().use { doc ->
            images.forEachIndexed { i, f ->
                onPage(i + 1)
                val bmp = ImageTools.decodeFile(f, 2600)
                val page = if (fitA4) {
                    imagePage(doc, bmp, PDRectangle.A4)
                } else {
                    // 150 dpi → размер страницы в пунктах
                    val pw = bmp.width * 72f / 150f
                    val ph = bmp.height * 72f / 150f
                    val p = PDPage(PDRectangle(pw, ph))
                    val img = JPEGFactory.createFromImage(doc, bmp, quality)
                    PDPageContentStream(doc, p).use { cs -> cs.drawImage(img, 0f, 0f, pw, ph) }
                    p
                }
                doc.addPage(page)
                bmp.recycle()
            }
            doc.save(out)
        }
    }

    // ---------- Объединение и разделение ----------

    fun merge(files: List<File>, out: File) {
        val m = PDFMergerUtility()
        files.forEach { m.addSource(it) }
        m.destinationFileName = out.absolutePath
        m.mergeDocuments(mem())
    }

    /** Разделить по диапазонам: каждый диапазон → отдельный файл. */
    fun splitRanges(src: File, ranges: List<IntRange>, outDir: File, base: String, password: String? = null): List<File> {
        val res = ArrayList<File>()
        open(src, password).use { doc ->
            ranges.forEachIndexed { i, r ->
                PDDocument().use { dst ->
                    for (p in r) if (p in 0 until doc.numberOfPages) dst.importPage(doc.getPage(p))
                    if (dst.numberOfPages > 0) {
                        val name = if (r.first == r.last) "${base}_стр${r.first + 1}.pdf" else "${base}_стр${r.first + 1}-${r.last + 1}.pdf"
                        val f = File(outDir, Files.sanitize(name))
                        dst.save(f)
                        res.add(f)
                    }
                }
            }
        }
        return res
    }

    /** Разделить по размеру: части примерно не больше limitBytes. */
    fun splitBySize(src: File, limitBytes: Long, outDir: File, base: String): List<File> {
        val total = src.length()
        val count = pageCount(src)
        val parts = max(1, ((total + limitBytes - 1) / limitBytes).toInt())
        if (parts == 1) return listOf(src.copyTo(File(outDir, "${base}_1.pdf"), true))
        val per = max(1, (count + parts - 1) / parts)
        val ranges = (0 until count step per).map { it until min(count, it + per) }
        return splitRanges(src, ranges, outDir, base)
    }

    /** Разделы по закладкам верхнего уровня. Пусто — закладок нет. */
    fun bookmarkRanges(src: File): List<Pair<String, IntRange>> {
        open(src).use { doc ->
            val outline = doc.documentCatalog.documentOutline ?: return emptyList()
            val starts = ArrayList<Pair<String, Int>>()
            var item: PDOutlineItem? = outline.firstChild
            while (item != null) {
                val page = try { item.findDestinationPage(doc) } catch (e: Exception) { null }
                if (page != null) {
                    val idx = doc.pages.indexOf(page)
                    if (idx >= 0) starts.add((item.title ?: "Раздел") to idx)
                }
                item = item.nextSibling
            }
            val sorted = starts.sortedBy { it.second }.distinctBy { it.second }
            return sorted.mapIndexed { i, (t, s) ->
                val end = if (i + 1 < sorted.size) sorted[i + 1].second - 1 else doc.numberOfPages - 1
                t to (s..max(s, end))
            }
        }
    }

    /** «1-3, 5, 8-10» → список диапазонов (0-based). */
    fun parseRanges(spec: String, pageCount: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (part in spec.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("^(\\d+)\\s*[-–—]\\s*(\\d+)$").find(part)
            if (m != null) {
                val a = m.groupValues[1].toInt().coerceIn(1, pageCount)
                val b = m.groupValues[2].toInt().coerceIn(1, pageCount)
                out.add((min(a, b) - 1)..(max(a, b) - 1))
            } else {
                part.toIntOrNull()?.takeIf { it in 1..pageCount }?.let { out.add((it - 1)..(it - 1)) }
            }
        }
        return out
    }

    // ---------- Сжатие ----------

    enum class Level(val title: String, val dpi: Int, val quality: Float) {
        LOSSLESS("Без потери качества", 0, 1f),
        HIGH("Высокое качество", 200, 0.85f),
        MEDIUM("Среднее", 150, 0.65f),
        MAX("Максимальное сжатие", 100, 0.45f),
    }

    /**
     * Сжатие: картинки внутри PDF уменьшаются до нужного разрешения и пересжимаются в JPEG.
     * Текст остаётся текстом. «Без потери» — только пересохранение и очистка.
     */
    fun compress(src: File, level: Level, out: File, onPage: (Int, Int) -> Unit = { _, _ -> }) {
        open(src).use { doc ->
            if (level != Level.LOSSLESS) {
                val done = HashSet<Any>()
                val n = doc.numberOfPages
                for (i in 0 until n) {
                    onPage(i + 1, n)
                    val page = doc.getPage(i)
                    val box = page.mediaBox
                    recompress(doc, page.resources ?: continue, box.width, box.height, level, done, 0)
                }
            }
            doc.documentInformation?.let { it.producer = "Maysk Scan" }
            doc.save(out)
        }
        // Если умное сжатие почти ничего не дало (сканы, векторная графика, шрифты),
        // для среднего и максимального уровня пробуем «как скан»: каждая страница — картинка.
        if ((level == Level.MEDIUM || level == Level.MAX) && out.length() > src.length() * 0.7) {
            val tmp = File(out.parentFile, "raster_${System.nanoTime()}.pdf")
            try {
                rasterize(src, level.dpi, level.quality, tmp, onPage)
                if (tmp.length() in 1 until out.length()) tmp.copyTo(out, true)
            } catch (e: Throwable) {
                // защищённый или необычный файл — оставляем результат умного сжатия
            } finally {
                tmp.delete()
            }
        }
    }

    /** Каждая страница рисуется в картинку JPEG с нужным dpi и собирается в новый PDF. */
    fun rasterize(src: File, dpi: Int, quality: Float, out: File, onPage: (Int, Int) -> Unit = { _, _ -> }) {
        PdfRender(src).use { r ->
            PDDocument().use { doc ->
                val n = r.pageCount
                for (i in 0 until n) {
                    onPage(i + 1, n)
                    val (wPt, hPt) = r.pageSize(i)
                    var widthPx = (wPt / 72f * dpi).roundToInt().coerceIn(200, 2500)
                    if (widthPx.toLong() * (widthPx.toLong() * hPt / max(1, wPt)) > 9_000_000L) widthPx = (widthPx * 0.7f).roundToInt()
                    val bmp = r.render(i, widthPx)
                    val page = PDPage(PDRectangle(wPt.toFloat(), hPt.toFloat()))
                    doc.addPage(page)
                    val img = JPEGFactory.createFromImage(doc, bmp, quality)
                    PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, 0f, 0f, wPt.toFloat(), hPt.toFloat()) }
                    bmp.recycle()
                }
                doc.documentInformation.producer = "Maysk Scan"
                doc.save(out)
            }
        }
    }

    private fun recompress(doc: PDDocument, res: PDResources, pageW: Float, pageH: Float, level: Level, done: HashSet<Any>, depth: Int) {
        if (depth > 4) return
        for (name in res.xObjectNames.toList()) {
            val xo = try { res.getXObject(name) } catch (e: Exception) { null } ?: continue
            if (xo is PDFormXObject) {
                xo.resources?.let { recompress(doc, it, pageW, pageH, level, done, depth + 1) }
                continue
            }
            if (xo !is PDImageXObject) continue
            val key = xo.cosObject
            if (!done.add(key)) continue
            try {
                if (xo.bitsPerComponent == 1) continue // ч/б сканы и так маленькие
                if (xo.cosObject.getItem(COSName.SMASK) != null) continue // прозрачность не трогаем
                val w = xo.width
                val h = xo.height
                // Предельный размер картинки: страница целиком при нужном dpi
                val maxW = (pageW / 72f * level.dpi).roundToInt()
                val maxH = (pageH / 72f * level.dpi).roundToInt()
                val k = min(1f, min(maxW.toFloat() / w, maxH.toFloat() / h))
                if (k >= 0.98f && xo.suffix == "jpg" && level == Level.HIGH) continue
                val bmp = xo.image ?: continue
                val scaled = if (k < 0.98f) Bitmap.createScaledBitmap(bmp, max(1, (w * k).roundToInt()), max(1, (h * k).roundToInt()), true) else bmp
                val opaque = if (scaled.hasAlpha()) flatten(scaled) else scaled
                val newImg = JPEGFactory.createFromImage(doc, opaque, level.quality)
                // Заменяем, только если стало меньше
                val oldLen = xo.cosObject.length
                val newLen = newImg.cosObject.length
                if (newLen < oldLen) res.put(name, newImg)
                if (opaque !== scaled) opaque.recycle()
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
            } catch (e: Exception) {
                // необычный формат картинки — оставляем как есть
            } catch (e: OutOfMemoryError) {
                // слишком большая картинка — пропускаем
            }
        }
    }

    private fun flatten(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(b, 0f, 0f, null)
        return out
    }

    // ---------- Защита ----------

    fun protect(src: File, out: File, userPassword: String, allowPrint: Boolean, allowCopy: Boolean, allowModify: Boolean, srcPassword: String? = null) {
        open(src, srcPassword).use { doc ->
            val ap = AccessPermission().apply {
                setCanPrint(allowPrint)
                setCanExtractContent(allowCopy)
                setCanExtractForAccessibility(true)
                setCanModify(allowModify)
                setCanModifyAnnotations(allowModify)
                setCanFillInForm(allowModify)
                setCanAssembleDocument(allowModify)
            }
            // Пароль владельца — случайный, если не задан отдельно: снимать ограничения сможет только знающий пароль открытия
            val owner = userPassword + "#owner"
            val policy = StandardProtectionPolicy(owner, userPassword, ap)
            policy.encryptionKeyLength = 128
            policy.isPreferAES = true
            doc.protect(policy)
            doc.save(out)
        }
    }

    /** Снять пароль (нужно знать пароль). */
    fun unlock(src: File, password: String, out: File) {
        open(src, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
    }

    // ---------- Текст ----------

    /** Текст по страницам (для поиска, Word, TXT). */
    fun pageTexts(src: File, password: String? = null): List<String> {
        open(src, password).use { doc ->
            val st = PDFTextStripper().apply { sortByPosition = true }
            return (1..doc.numberOfPages).map { p ->
                st.startPage = p
                st.endPage = p
                try { st.getText(doc) } catch (e: Exception) { "" }
            }
        }
    }

    // ---------- Наложение (подпись, текст, рисунок, водяной знак) ----------

    /**
     * Накладывает прозрачные картинки на страницы. overlays: индекс страницы → картинка
     * размером с отображаемую страницу (с учётом поворота страницы).
     */
    fun overlay(src: File, overlays: Map<Int, Bitmap>, out: File) {
        open(src).use { doc ->
            for ((idx, bmp) in overlays) {
                if (idx !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(idx)
                val box = page.cropBox ?: page.mediaBox
                val img = LosslessFactory.createFromImage(doc, bmp)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    val rot = ((page.rotation % 360) + 360) % 360
                    // Картинка нарисована для повёрнутой страницы — поворачиваем её обратно
                    val w = box.width
                    val h = box.height
                    when (rot) {
                        90 -> cs.drawImage(img, com.tom_roush.pdfbox.util.Matrix(0f, h, -w, 0f, box.lowerLeftX + w, box.lowerLeftY))
                        180 -> cs.drawImage(img, com.tom_roush.pdfbox.util.Matrix(-w, 0f, 0f, -h, box.lowerLeftX + w, box.lowerLeftY + h))
                        270 -> cs.drawImage(img, com.tom_roush.pdfbox.util.Matrix(0f, -h, w, 0f, box.lowerLeftX, box.lowerLeftY + h))
                        else -> cs.drawImage(img, box.lowerLeftX, box.lowerLeftY, w, h)
                    }
                }
            }
            doc.save(out)
        }
    }

    /** Размер страницы так, как её видит пользователь (с поворотом), в пунктах. */
    fun visibleSize(page: PDPage): Pair<Float, Float> {
        val box = page.cropBox ?: page.mediaBox
        val rot = ((page.rotation % 360) + 360) % 360
        return if (rot == 90 || rot == 270) box.height to box.width else box.width to box.height
    }

    /** Водяной знак: полупрозрачный текст по диагонали на всех страницах. */
    fun watermark(src: File, text: String, out: File, color: Int = 0x55E5484D, sizeK: Float = 0.08f) {
        val overlays = HashMap<Int, Bitmap>()
        open(src).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                val (pw, ph) = visibleSize(doc.getPage(i))
                val scale = 1200f / max(pw, ph)
                val bw = max(1, (pw * scale).roundToInt())
                val bh = max(1, (ph * scale).roundToInt())
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    textSize = max(bw, bh) * sizeK
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
                c.save()
                c.rotate(-35f, bw / 2f, bh / 2f)
                c.drawText(text, bw / 2f, bh / 2f, paint)
                c.restore()
                overlays[i] = bmp
            }
        }
        overlay(src, overlays, out)
        overlays.values.forEach { it.recycle() }
    }
}
