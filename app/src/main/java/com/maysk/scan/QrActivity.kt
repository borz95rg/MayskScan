package com.maysk.scan

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** QR и штрихкоды: сканирование камерой или из фото, создание своих QR. Всё без интернета. */
class QrActivity : BaseActivity() {

    private lateinit var body: LinearLayout
    private var tab = 0
    private var scanner: BarcodeScanner? = null
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var found = false
    private var torch = false
    private lateinit var resultBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = screen("QR / Штрихкод", scroll = true)
        scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
        render()
        intent.getStringExtra("image")?.let { fromImage(Uri.fromFile(File(it))) }
    }

    override fun onDestroy() {
        provider?.unbindAll()
        scanner?.close()
        executor.shutdown()
        super.onDestroy()
    }

    private fun render() {
        provider?.unbindAll()
        body.removeAllViews()
        val tabs = hbox()
        tabs.addView(chip("Сканировать", tab == 0) { tab = 0; render() }, lp(0, WRAP, 1f))
        tabs.addView(chip("Создать QR", tab == 1) { tab = 1; render() }, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        (0 until tabs.childCount).forEach { (tabs.getChildAt(it) as TextView).gravity = android.view.Gravity.CENTER }
        body.addView(tabs)
        if (tab == 0) scanTab() else createTab()
    }

    // ---------- Сканирование ----------

    private fun scanTab() {
        found = false
        val frame = FrameLayout(this).apply { background = cardBg(20) }
        val pv = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        frame.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))
        // Рамка-прицел
        frame.addView(View(this).apply { background = rounded(Color.TRANSPARENT, 22, col(R.color.accent), 3) },
            FrameLayout.LayoutParams(dp(230), dp(230), android.view.Gravity.CENTER))
        body.addView(frame, lp(MATCH, dp(340)).apply { topMargin = dp(12) })

        body.addView(actionRow(
            actionButton(R.drawable.ic_flash, "Вспышка") { torch = !torch; camera?.cameraControl?.enableTorch(torch) },
            actionButton(R.drawable.ic_image, "Из фото") { pick(arrayOf("image/*")) { fromImage(it[0]) } },
        ), lp().apply { topMargin = dp(10) })

        resultBox = vbox()
        body.addView(resultBox, lp().apply { topMargin = dp(12) })
        body.addView(text("Поддерживаются: QR, EAN, UPC, Code128, PDF417, Aztec, Data Matrix и другие.", 12f, R.color.text2).apply { setPadding(dp(4), dp(10), 0, 0) })

        withCamera { startCamera(pv) }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera(pv: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || found) { proxy.close(); return@setAnalyzer }
                val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner?.process(img)
                    ?.addOnSuccessListener { codes ->
                        val code = codes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                        if (code != null && !found) {
                            found = true
                            runOnUiThread { showResult(code) }
                        }
                    }
                    ?.addOnCompleteListener { proxy.close() }
                    ?: proxy.close()
            }
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                toast("Камера недоступна")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun fromImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val img = withContext(Dispatchers.IO) { InputImage.fromFilePath(this@QrActivity, uri) }
                val codes = scanner?.process(img)?.await() ?: emptyList()
                val code = codes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                if (code == null) toast("Код на фото не найден") else { found = true; showResult(code) }
            } catch (e: Throwable) { fail(e) }
        }
    }

    private fun typeName(b: Barcode): String = when (b.valueType) {
        Barcode.TYPE_URL -> "Ссылка"
        Barcode.TYPE_WIFI -> "Wi-Fi"
        Barcode.TYPE_CONTACT_INFO -> "Контакт"
        Barcode.TYPE_PHONE -> "Телефон"
        Barcode.TYPE_EMAIL -> "Эл. почта"
        Barcode.TYPE_SMS -> "SMS"
        Barcode.TYPE_GEO -> "Место на карте"
        Barcode.TYPE_PRODUCT -> "Товар (штрихкод)"
        Barcode.TYPE_ISBN -> "Книга (ISBN)"
        else -> if (b.format == Barcode.FORMAT_QR_CODE) "Текст" else "Штрихкод"
    }

    private fun showResult(b: Barcode) {
        val raw = b.rawValue ?: return
        saveToHistory(raw, typeName(b))
        resultBox.removeAllViews()
        val card = vbox(16).apply { background = cardBg(18, glow = true) }
        card.addView(text("Результат · ${typeName(b)}", 13f, R.color.text2, bold = true))
        card.addView(text(raw, 16f).apply { setTextIsSelectable(true); setPadding(0, dp(6), 0, 0) })
        b.wifi?.let { w -> card.addView(text("Сеть: ${w.ssid}\nПароль: ${w.password ?: "—"}", 14f, R.color.text2).apply { setPadding(0, dp(6), 0, 0) }) }
        resultBox.addView(card)
        val isLink = raw.startsWith("http://", true) || raw.startsWith("https://", true)
        resultBox.addView(actionRow(
            actionButton(R.drawable.ic_open, if (isLink) "Открыть" else "Найти") {
                val uri = if (isLink) Uri.parse(raw) else Uri.parse("https://www.google.com/search?q=" + Uri.encode(raw))
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { toast("Нет браузера") }
            },
            actionButton(R.drawable.ic_copy, "Копировать") { Clip.copy(this, b.wifi?.password ?: raw) },
            actionButton(R.drawable.ic_share, "Поделиться") { Files.shareText(this, raw) },
            actionButton(R.drawable.ic_camera, "Ещё раз") { found = false; resultBox.removeAllViews() },
        ), lp().apply { topMargin = dp(10) })
    }

    private fun saveToHistory(raw: String, type: String) {
        try {
            val f = Files.newDoc(this, "QR ${Files.stamp()}", "txt")
            f.writeText(raw)
            History.add(this, Kind.QR, f, "$type: ${raw.take(40)}", raw)
        } catch (e: Exception) {}
    }

    // ---------- Создание ----------

    private fun createTab() {
        var kind = 0
        val form = vbox()
        val preview = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        var current: Bitmap? = null
        val (sc, kinds) = chipRow()
        fun buildForm() {
            form.removeAllViews()
            kinds.removeAllViews()
            listOf("Текст / ссылка", "Wi-Fi", "Контакт", "Телефон").forEachIndexed { i, n ->
                kinds.addView(chip(n, i == kind) { kind = i; buildForm() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
            val fields = when (kind) {
                1 -> listOf(field("Название сети (SSID)"), field("Пароль"))
                2 -> listOf(field("Имя"), field("Телефон"), field("Эл. почта (необязательно)"))
                3 -> listOf(field("Номер телефона"))
                else -> listOf(field("Текст или ссылка, например https://maysk.kz", multiline = true))
            }
            fields.forEachIndexed { i, f -> form.addView(f, lp().apply { if (i > 0) topMargin = dp(8) }) }
            form.addView(primaryButton("Создать QR", R.drawable.ic_qr) {
                val v = fields.map { it.text.toString().trim() }
                val content = when (kind) {
                    1 -> "WIFI:T:WPA;S:${esc(v[0])};P:${esc(v[1])};;"
                    2 -> "BEGIN:VCARD\nVERSION:3.0\nFN:${v[0]}\nTEL:${v[1]}\n" + (if (v[2].isNotEmpty()) "EMAIL:${v[2]}\n" else "") + "END:VCARD"
                    3 -> "tel:${v[0]}"
                    else -> v[0]
                }
                if (v.firstOrNull().isNullOrEmpty()) { toast("Заполните поле"); return@primaryButton }
                current?.recycle()
                current = makeQr(content, 900)
                preview.setImageBitmap(current)
                preview.visibility = View.VISIBLE
            }, lp().apply { topMargin = dp(12) })
        }
        body.addView(sc, lp().apply { topMargin = dp(14) })
        body.addView(form, lp().apply { topMargin = dp(12) })
        preview.visibility = View.GONE
        body.addView(preview, LinearLayout.LayoutParams(dp(260), dp(260)).apply { topMargin = dp(16); gravity = android.view.Gravity.CENTER_HORIZONTAL })
        body.addView(actionRow(
            actionButton(R.drawable.ic_save, "Сохранить") {
                val b = current ?: return@actionButton toast("Сначала создайте QR")
                val f = Files.newDoc(this, "QR-код ${Files.stamp()}", "png")
                ImageTools.savePng(b, f)
                Files.exportCopy(this, f)
                History.add(this, Kind.IMAGE, f)
                toast("Сохранено в Истории")
            },
            actionButton(R.drawable.ic_share, "Поделиться") {
                val b = current ?: return@actionButton toast("Сначала создайте QR")
                val f = File(cacheDir, "qr_${System.nanoTime()}.png")
                ImageTools.savePng(b, f)
                share(listOf(f), "image/png")
            },
        ), lp().apply { topMargin = dp(12) })
        buildForm()
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")

    private fun makeQr(content: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
