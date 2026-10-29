package com.maysk.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Подпись, текст, рисование, печать и водяной знак поверх страниц PDF. */
class PdfAnnotateActivity : BaseActivity() {

    private lateinit var src: File
    private var render: PdfRender? = null
    private var page = 0
    private lateinit var view: AnnotateView
    private lateinit var pageText: android.widget.TextView
    private lateinit var modeRow: LinearLayout
    /** Элементы по страницам. */
    private val pageItems = HashMap<Int, MutableList<Ann>>()

    private val pickSign = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra("path")
        if (r.resultCode == Activity.RESULT_OK && path != null) addImage(File(path), 0.32f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        src = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        when (intent.getStringExtra("mode")) {
            "watermark" -> { watermarkScreen(); return }
        }
        val body = screen("Подпись и пометки", scroll = false)
        addTopAction(R.drawable.ic_check) { save() }

        val nav = hbox().apply { gravity = android.view.Gravity.CENTER }
        nav.addView(iconButton(R.drawable.ic_back) { showPage(page - 1) })
        pageText = text("", 14f, bold = true).apply { setPadding(dp(14), 0, dp(14), 0) }
        nav.addView(pageText)
        nav.addView(iconButton(R.drawable.ic_chevron) { showPage(page + 1) })
        body.addView(nav)

        view = AnnotateView(this)
        body.addView(view, lp(MATCH, 0, 1f))

        modeRow = hbox()
        body.addView(actionRow(
            actionButton(R.drawable.ic_sign, "Подпись") { pickSign.launch(Intent(this, SignatureActivity::class.java).putExtra("pick", true)) },
            actionButton(R.drawable.ic_text, "Текст") { addText() },
            actionButton(R.drawable.ic_draw, "Рисовать") { toggleDraw() },
            actionButton(R.drawable.ic_stamp, "Печать/фото") { pick(arrayOf("image/*")) { u -> stampFrom(u[0]) } },
        ), lp().apply { topMargin = dp(8) })
        body.addView(actionRow(
            actionButton(R.drawable.ic_undo, "Отменить") { view.undo() },
            actionButton(R.drawable.ic_delete, "Удалить") { view.deleteSelected() },
            actionButton(R.drawable.ic_add, "Больше") { view.scaleSelected(1.15f) },
            actionButton(R.drawable.ic_compress, "Меньше") { view.scaleSelected(1 / 1.15f) },
        ), lp().apply { topMargin = dp(8) })
        body.addView(primaryButton("Сохранить PDF", R.drawable.ic_save) { save() }, lp().apply { topMargin = dp(8) })

        render = PdfRender.tryOpen(src) ?: run { toast("PDF защищён паролем — сначала снимите пароль"); finish(); return }
        view.post { showPage(0) }
        if (intent.getStringExtra("mode") == "sign") view.post { pickSign.launch(Intent(this, SignatureActivity::class.java).putExtra("pick", true)) }
    }

    override fun onDestroy() {
        render?.close()
        super.onDestroy()
    }

    private fun showPage(i: Int) {
        val r = render ?: return
        if (i < 0 || i >= r.pageCount) return
        page = i
        pageText.text = "Страница ${i + 1} из ${r.pageCount}"
        val items = pageItems.getOrPut(i) { ArrayList() }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { r.render(i, 1400) }
            view.setPage(bmp, items)
        }
    }

    private fun addImage(f: File, widthFrac: Float) {
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return
        view.add(Ann.Image(bmp, widthFrac))
    }

    private fun stampFrom(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    val src = ImageTools.decode(this@PdfAnnotateActivity, uri, 1200)
                    choiceTransparent(src)
                }
                view.add(Ann.Image(bmp, 0.3f))
            } catch (e: Throwable) { fail(e) }
        }
    }

    /** Печать с белым фоном — делаем фон прозрачным, фото оставляем как есть. */
    private fun choiceTransparent(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val light = px.count { ((it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF)) / 3 > 215 }
        return if (light > px.size * 0.5) Signatures.removeWhite(src) else src
    }

    private fun addText() {
        val box = vbox(16)
        val f = field("Текст (например, «Согласовано»)")
        box.addView(f)
        var color = Color.rgb(16, 42, 120)
        val colors = hbox().apply { setPadding(0, dp(10), 0, 0) }
        fun chips() {
            colors.removeAllViews()
            listOf("Синий" to Color.rgb(16, 42, 120), "Чёрный" to Color.BLACK, "Красный" to Color.rgb(200, 20, 30)).forEach { (n, c) ->
                colors.addView(chip(n, c == color) { color = c; chips() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
        }
        chips()
        box.addView(colors)
        androidx.appcompat.app.TrDialog(this).setTitle("Добавить текст").setView(box)
            .setPositiveButton("Добавить") { _, _ ->
                val t = f.text.toString().trim()
                if (t.isNotEmpty()) view.add(Ann.Text(t, color, 0.035f))
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun toggleDraw() {
        view.drawMode = !view.drawMode
        toast(if (view.drawMode) "Рисуйте пальцем по странице. Нажмите «Рисовать» ещё раз, чтобы выйти." else "Режим рисования выключен")
    }

    private fun save() {
        val r = render ?: return
        val used = pageItems.filterValues { it.isNotEmpty() }
        if (used.isEmpty()) { toast("Добавьте подпись, текст или рисунок"); return }
        lifecycleScope.launch {
            showProgress("Сохраняю PDF…")
            try {
                val out = Files.newDoc(this@PdfAnnotateActivity, Files.baseName(src.name) + " (подписан)", "pdf")
                withContext(Dispatchers.IO) {
                    val overlays = HashMap<Int, Bitmap>()
                    for ((p, items) in used) {
                        val (pw, ph) = r.pageSize(p)
                        val k = 1800f / max(pw, ph)
                        val bw = max(1, (pw * k).roundToInt())
                        val bh = max(1, (ph * k).roundToInt())
                        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        val c = Canvas(bmp)
                        items.forEach { it.draw(c, bw.toFloat(), bh.toFloat(), false) }
                        overlays[p] = bmp
                    }
                    PdfTools.overlay(src, overlays, out)
                    overlays.values.forEach { it.recycle() }
                    Files.exportCopy(this@PdfAnnotateActivity, out)
                }
                History.add(this@PdfAnnotateActivity, Kind.PDF, out)
                hideProgress()
                toast("Сохранено: ${out.name}")
                startActivity(Intent(this@PdfAnnotateActivity, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, out.absolutePath))
                finish()
            } catch (e: Throwable) { fail(e) }
        }
    }

    // ---------- Водяной знак ----------

    private fun watermarkScreen() {
        val body = screen("Водяной знак")
        body.addView(text("Полупрозрачная надпись по диагонали на всех страницах.", 14f, R.color.text2))
        val f = field("Текст", "КОПИЯ")
        body.addView(f, lp().apply { topMargin = dp(12) })
        var color = 0x55E5484D
        var size = 0.08f
        val colorRow = hbox().apply { setPadding(0, dp(12), 0, 0) }
        val sizeRow = hbox().apply { setPadding(0, dp(10), 0, 0) }
        fun rows() {
            colorRow.removeAllViews()
            listOf("Красный" to 0x55E5484D, "Синий" to 0x551E88FF, "Серый" to 0x55808080).forEach { (n, c) ->
                colorRow.addView(chip(n, c == color) { color = c; rows() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
            sizeRow.removeAllViews()
            listOf("Мелкий" to 0.05f, "Средний" to 0.08f, "Крупный" to 0.12f).forEach { (n, s) ->
                sizeRow.addView(chip(n, s == size) { size = s; rows() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
        }
        rows()
        body.addView(colorRow)
        body.addView(sizeRow)
        body.addView(primaryButton("Добавить водяной знак", R.drawable.ic_drop) {
            val t = f.text.toString().trim()
            if (t.isEmpty()) { toast("Введите текст"); return@primaryButton }
            lifecycleScope.launch {
                showProgress("Добавляю водяной знак…")
                try {
                    val out = Files.newDoc(this@PdfAnnotateActivity, Files.baseName(src.name) + " (водяной знак)", "pdf")
                    withContext(Dispatchers.IO) { PdfTools.watermark(src, t, out, color, size); Files.exportCopy(this@PdfAnnotateActivity, out) }
                    History.add(this@PdfAnnotateActivity, Kind.PDF, out)
                    hideProgress()
                    startActivity(Intent(this@PdfAnnotateActivity, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, out.absolutePath))
                    finish()
                } catch (e: Throwable) { fail(e) }
            }
        }, lp().apply { topMargin = dp(20) })
    }
}

/** Элемент пометки. Координаты — доли от размера страницы (0..1). */
sealed class Ann {
    var x = 0.5f
    var y = 0.5f

    abstract fun bounds(w: Float, h: Float): RectF
    abstract fun draw(c: Canvas, w: Float, h: Float, preview: Boolean)
    abstract fun scale(k: Float)

    class Image(val bmp: Bitmap, var widthFrac: Float) : Ann() {
        override fun bounds(w: Float, h: Float): RectF {
            val bw = widthFrac * w
            val bh = bw * bmp.height / bmp.width
            return RectF(x * w - bw / 2, y * h - bh / 2, x * w + bw / 2, y * h + bh / 2)
        }
        override fun draw(c: Canvas, w: Float, h: Float, preview: Boolean) {
            c.drawBitmap(bmp, null, bounds(w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        override fun scale(k: Float) { widthFrac = (widthFrac * k).coerceIn(0.05f, 1f) }
    }

    class Text(val text: String, val color: Int, var sizeFrac: Float) : Ann() {
        private fun paint(w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@Text.color
            textSize = sizeFrac * w
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        override fun bounds(w: Float, h: Float): RectF {
            val p = paint(w)
            val tw = p.measureText(text)
            return RectF(x * w - tw / 2, y * h - p.textSize, x * w + tw / 2, y * h + p.textSize * 0.3f)
        }
        override fun draw(c: Canvas, w: Float, h: Float, preview: Boolean) {
            c.drawText(text, x * w, y * h, paint(w))
        }
        override fun scale(k: Float) { sizeFrac = (sizeFrac * k).coerceIn(0.01f, 0.2f) }
    }

    class Stroke(val color: Int, val widthFrac: Float) : Ann() {
        val pts = ArrayList<Float>()
        override fun bounds(w: Float, h: Float): RectF {
            if (pts.isEmpty()) return RectF()
            var l = 1f; var t = 1f; var r = 0f; var b = 0f
            var i = 0
            while (i < pts.size) { l = min(l, pts[i]); r = max(r, pts[i]); t = min(t, pts[i + 1]); b = max(b, pts[i + 1]); i += 2 }
            return RectF(l * w, t * h, r * w, b * h)
        }
        override fun draw(c: Canvas, w: Float, h: Float, preview: Boolean) {
            if (pts.size < 4) return
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = this@Stroke.color
                style = Paint.Style.STROKE
                strokeWidth = widthFrac * w
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path()
            path.moveTo(pts[0] * w, pts[1] * h)
            var i = 2
            while (i < pts.size) { path.lineTo(pts[i] * w, pts[i + 1] * h); i += 2 }
            c.drawPath(path, p)
        }
        override fun scale(k: Float) {}
    }
}

/** Страница с пометками: перетаскивание, выбор, рисование. */
class AnnotateView(ctx: Context) : View(ctx) {
    private var page: Bitmap? = null
    private var items: MutableList<Ann> = ArrayList()
    private var selected: Ann? = null
    var drawMode = false
    private val dst = RectF()
    private var stroke: Ann.Stroke? = null
    private var lastX = 0f
    private var lastY = 0f
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(30, 136, 255)
        strokeWidth = ctx.dpf(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(ctx.dpf(6f), ctx.dpf(4f)), 0f)
    }
    private val shadow = Paint().apply { color = 0x33000000 }

    fun setPage(bmp: Bitmap, list: MutableList<Ann>) {
        page?.recycle()
        page = bmp
        items = list
        selected = null
        invalidate()
    }

    fun add(a: Ann) {
        items.add(a)
        selected = a
        invalidate()
    }

    fun undo() { if (items.isNotEmpty()) { items.removeAt(items.size - 1); selected = null; invalidate() } }
    fun deleteSelected() { selected?.let { items.remove(it); selected = null; invalidate() } ?: context.toast("Нажмите на пометку, чтобы выбрать её") }
    fun scaleSelected(k: Float) { selected?.let { it.scale(k); invalidate() } ?: context.toast("Нажмите на подпись или текст, чтобы выбрать") }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val b = page ?: return
        val k = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * k
        val h = b.height * k
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        c.drawRect(dst.left + 4, dst.top + 4, dst.right + 4, dst.bottom + 4, shadow)
        c.drawBitmap(b, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        c.save()
        c.translate(dst.left, dst.top)
        c.clipRect(0f, 0f, w, h)
        items.forEach { it.draw(c, w, h, true) }
        selected?.let { s -> val r = s.bounds(w, h); r.inset(-8f, -8f); c.drawRect(r, border) }
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (page == null || dst.width() <= 0) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        val fx = (e.x - dst.left) / dst.width()
        val fy = (e.y - dst.top) / dst.height()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (drawMode) {
                    stroke = Ann.Stroke(Color.rgb(16, 42, 120), 0.004f).also { it.pts.add(fx); it.pts.add(fy); items.add(it) }
                } else {
                    selected = items.lastOrNull { a -> a !is Ann.Stroke && a.bounds(dst.width(), dst.height()).let { r -> r.inset(-30f, -30f); r.contains(e.x - dst.left, e.y - dst.top) } }
                    lastX = fx; lastY = fy
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawMode) {
                    stroke?.let { it.pts.add(fx.coerceIn(0f, 1f)); it.pts.add(fy.coerceIn(0f, 1f)) }
                } else selected?.let {
                    it.x = (it.x + fx - lastX).coerceIn(0f, 1f)
                    it.y = (it.y + fy - lastY).coerceIn(0f, 1f)
                    lastX = fx; lastY = fy
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stroke = null
        }
        invalidate()
        return true
    }
}
