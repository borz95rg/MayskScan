package com.maysk.scan

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** Удостоверение / паспорт / карта: обе стороны на одном листе A4, как ксерокопия. */
class IdCardActivity : BaseActivity() {

    /** Размер документа в мм; 0 — растянуть по ширине листа. */
    private class Mode(val title: String, val wMm: Float, val hMm: Float)

    private val modes = listOf(
        Mode("Удостоверение / ID-карта", 85.6f, 54f),
        Mode("Паспорт (разворот)", 125f, 88f),
        Mode("Крупно, по ширине листа", 0f, 0f),
    )
    private var mode = 0
    private val sides = arrayOfNulls<File>(2)
    private var slot = 0
    private lateinit var slotViews: List<ImageView>
    private lateinit var modeRow: LinearLayout
    private lateinit var preview: ImageView

    private val scanner = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages?.firstOrNull()?.imageUri?.let { setSide(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = screen("Удостоверение / паспорт")
        body.addView(text("Отсканируйте лицевую и обратную сторону — получится лист A4 с обеими сторонами в реальном размере, как ксерокопия.", 13.5f, R.color.text2))

        val (sc, row) = chipRow()
        modeRow = row
        body.addView(sc, lp().apply { topMargin = dp(12) })

        val slots = hbox()
        slotViews = listOf("Лицевая сторона", "Обратная сторона").mapIndexed { i, label ->
            val card = vbox(10).apply { background = clickable(cardBg(18), 18); gravity = Gravity.CENTER_HORIZONTAL }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(R.drawable.ic_camera)
                setColorFilter(col(R.color.text2))
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            card.addView(img, lp(MATCH, dp(110)))
            card.addView(text(label, 13f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
            card.setOnClickListener { slot = i; capture() }
            slots.addView(card, lp(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(10) })
            img
        }
        body.addView(slots, lp().apply { topMargin = dp(12) })

        body.addView(sectionTitle("ПРЕДПРОСМОТР ЛИСТА"))
        val frame = FrameLayout(this).apply { background = cardBg(18); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
        frame.addView(preview, FrameLayout.LayoutParams(MATCH, dp(360)))
        body.addView(frame)

        body.addView(actionRow(
            actionButton(R.drawable.ic_replace, "Поменять") { val t = sides[0]; sides[0] = sides[1]; sides[1] = t; refresh() },
            actionButton(R.drawable.ic_print, "Печать") { make { Printer.print(this, it) } },
            actionButton(R.drawable.ic_share, "Поделиться") { make { share(listOf(it), "application/pdf") } },
        ), lp().apply { topMargin = dp(12) })
        body.addView(primaryButton("Сохранить PDF", R.drawable.ic_pdf) { make(save = true) { done(it) } }, lp().apply { topMargin = dp(10) })
        refresh()
    }

    private fun capture() {
        choose(if (slot == 0) "Лицевая сторона" else "Обратная сторона", listOf("Сканер (рамка сама)", "Камера", "Из галереи")) { i ->
            when (i) {
                0 -> {
                    val opt = GmsDocumentScannerOptions.Builder().setPageLimit(1).setGalleryImportAllowed(true)
                        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
                    GmsDocumentScanning.getClient(opt).getStartScanIntent(this)
                        .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
                        .addOnFailureListener { photo { u -> if (u != null) setSide(u) } }
                }
                1 -> photo { u -> if (u != null) setSide(u) }
                else -> pick(arrayOf("image/*")) { setSide(it[0]) }
            }
        }
    }

    private fun setSide(uri: Uri) {
        lifecycleScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val bmp = ImageTools.decode(this@IdCardActivity, uri, 2000)
                    // Карточку держат горизонтально: если снимок вертикальный — поворачиваем
                    val m = modes[mode]
                    val upright = if (m.wMm > m.hMm && bmp.height > bmp.width * 1.15f) ImageTools.rotate(bmp, 90) else bmp
                    val out = File(cacheDir, "id_${System.nanoTime()}.jpg")
                    ImageTools.saveJpeg(upright, out, 92)
                    if (upright !== bmp) upright.recycle()
                    bmp.recycle()
                    out
                }
                sides[slot] = f
                if (slot == 0 && sides[1] == null) { slot = 1; toast("Теперь обратная сторона") }
                refresh()
            } catch (e: Throwable) { fail(e) }
        }
    }

    private fun refresh() {
        modeRow.removeAllViews()
        modes.forEachIndexed { i, m ->
            modeRow.addView(chip(m.title, i == mode) { mode = i; refresh() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        sides.forEachIndexed { i, f ->
            val v = slotViews[i]
            if (f != null) {
                v.clearColorFilter(); v.setPadding(0, 0, 0, 0)
                v.setImageBitmap(ImageTools.decodeFile(f, 500))
            }
        }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) { compose(60) }
            preview.setImageBitmap(bmp)
        }
    }

    /** Собирает лист A4 с нужным dpi. */
    private fun compose(dpi: Int): Bitmap {
        val pw = (210f / 25.4f * dpi).roundToInt()
        val ph = (297f / 25.4f * dpi).roundToInt()
        val page = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.WHITE)
        val m = modes[mode]
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val frame = Paint().apply { style = Paint.Style.STROKE; color = 0xFFBBBBBB.toInt(); strokeWidth = maxOf(1f, dpi / 100f) }
        for (i in 0..1) {
            val half = RectF(0f, ph / 2f * i, pw.toFloat(), ph / 2f * (i + 1))
            val f = sides[i]
            if (f == null) {
                val dash = Paint(frame).apply { pathEffect = android.graphics.DashPathEffect(floatArrayOf(dpi / 10f, dpi / 10f), 0f) }
                val w = pw * 0.6f; val h = w * 0.63f
                c.drawRect(RectF(half.centerX() - w / 2, half.centerY() - h / 2, half.centerX() + w / 2, half.centerY() + h / 2), dash)
                continue
            }
            val img = ImageTools.decodeFile(f, if (dpi > 100) 2200 else 600)
            val (w, h) = if (m.wMm > 0) {
                // Реальный размер в мм; если фото другой пропорции — вписываем
                val bw = m.wMm / 25.4f * dpi
                val bh = m.hMm / 25.4f * dpi
                val k = min(bw / img.width, bh / img.height)
                img.width * k to img.height * k
            } else {
                val k = min(pw * 0.86f / img.width, half.height() * 0.86f / img.height)
                img.width * k to img.height * k
            }
            val dst = RectF(half.centerX() - w / 2, half.centerY() - h / 2, half.centerX() + w / 2, half.centerY() + h / 2)
            c.drawBitmap(img, Rect(0, 0, img.width, img.height), dst, paint)
            c.drawRect(dst, frame)
            img.recycle()
        }
        return page
    }

    private fun make(save: Boolean = false, cb: (File) -> Unit) {
        if (sides[0] == null) { toast("Сначала отсканируйте лицевую сторону"); return }
        lifecycleScope.launch {
            showProgress("Создаю лист A4…")
            try {
                val out = if (save) Files.newDoc(this@IdCardActivity, "Удостоверение ${Files.stamp()}", "pdf") else File(cacheDir, "Копия документа.pdf")
                withContext(Dispatchers.IO) {
                    val bmp = compose(200)
                    PDDocument().use { doc ->
                        val page = PDPage(PDRectangle.A4)
                        doc.addPage(page)
                        val img = JPEGFactory.createFromImage(doc, bmp, 0.9f)
                        PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height) }
                        doc.save(out)
                    }
                    bmp.recycle()
                    if (save) Files.exportCopy(this@IdCardActivity, out)
                }
                if (save) History.add(this@IdCardActivity, Kind.SCAN, out)
                hideProgress()
                cb(out)
            } catch (e: Throwable) { fail(e) }
        }
    }

    private fun done(f: File) {
        TrDialog(this).setTitle("Готово!")
            .setMessage("«${f.name}» сохранён в Истории.")
            .setPositiveButton("Открыть") { _, _ -> startActivity(android.content.Intent(this, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, f.absolutePath)) }
            .setNeutralButton("Поделиться") { _, _ -> share(listOf(f), "application/pdf") }
            .setNegativeButton("Закрыть", null).show()
    }
}
