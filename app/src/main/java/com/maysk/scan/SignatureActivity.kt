package com.maysk.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Подписи и печати: нарисовать пальцем/стилусом или загрузить картинку. Хранятся на телефоне. */
class SignatureActivity : BaseActivity() {

    private lateinit var pad: SignPad
    private lateinit var listBox: LinearLayout
    private val pickMode get() = intent.getBooleanExtra("pick", false)
    private var ink = Color.rgb(16, 42, 120)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = screen(if (pickMode) "Выберите подпись" else "Мои подписи")

        body.addView(sectionTitle("НАРИСУЙТЕ ПОДПИСЬ"))
        val frame = FrameLayout(this).apply { background = rounded(Color.WHITE, 18, col(R.color.stroke)) }
        pad = SignPad(this)
        frame.addView(pad, FrameLayout.LayoutParams(MATCH, dp(220)))
        body.addView(frame)

        val colors = hbox().apply { setPadding(0, dp(10), 0, 0) }
        listOf("Синие чернила" to Color.rgb(16, 42, 120), "Чёрные" to Color.BLACK, "Красные" to Color.rgb(190, 20, 30)).forEach { (n, c) ->
            colors.addView(chip(n, c == ink) { ink = c; pad.color = c; recreateChips(colors) }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        body.addView(colors)

        body.addView(actionRow(
            actionButton(R.drawable.ic_undo, "Очистить") { pad.clear() },
            actionButton(R.drawable.ic_image, "Загрузить") { importImage() },
            actionButton(R.drawable.ic_check, "Сохранить", highlighted = true) { saveDrawn() },
        ), lp().apply { topMargin = dp(12) })

        body.addView(sectionTitle("СОХРАНЁННЫЕ"))
        listBox = vbox()
        body.addView(listBox)
        renderList()
    }

    private fun recreateChips(row: LinearLayout) {
        row.removeAllViews()
        listOf("Синие чернила" to Color.rgb(16, 42, 120), "Чёрные" to Color.BLACK, "Красные" to Color.rgb(190, 20, 30)).forEach { (n, c) ->
            row.addView(chip(n, c == ink) { ink = c; pad.color = c; recreateChips(row) }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
    }

    private fun renderList() {
        listBox.removeAllViews()
        val files = Signatures.all(this)
        if (files.isEmpty()) {
            listBox.addView(text("Пока нет сохранённых подписей.", 14f, R.color.text2))
            return
        }
        files.forEach { f ->
            val row = hbox(10).apply { background = clickable(cardBg(16), 16) }
            row.addView(ImageView(this).apply {
                setBackgroundColor(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
            }, LinearLayout.LayoutParams(0, dp(70), 1f))
            if (pickMode) {
                row.setOnClickListener { setResult(Activity.RESULT_OK, Intent().putExtra("path", f.absolutePath)); finish() }
                row.addView(icon(R.drawable.ic_chevron, 22, R.color.accent).apply { margins(context, l = 10) })
            } else {
                row.addView(iconButton(R.drawable.ic_delete, R.color.danger) {
                    confirm("Удалить подпись?", "Она будет удалена с телефона.", "Удалить") { f.delete(); renderList() }
                })
            }
            listBox.addView(row, lp().apply { bottomMargin = dp(8) })
        }
    }

    private fun saveDrawn() {
        val bmp = pad.export() ?: run { toast("Сначала нарисуйте подпись"); return }
        val f = Signatures.save(this, bmp)
        bmp.recycle()
        pad.clear()
        if (pickMode) {
            setResult(Activity.RESULT_OK, Intent().putExtra("path", f.absolutePath)); finish()
        } else {
            toast("Подпись сохранена")
            renderList()
        }
    }

    /** Картинка подписи или печати: белый фон становится прозрачным. */
    private fun importImage() {
        pick(arrayOf("image/*")) { uris ->
            lifecycleScope.launch {
                try {
                    val f = withContext(Dispatchers.IO) {
                        val src = ImageTools.decode(this@SignatureActivity, uris[0], 1400)
                        val cut = Signatures.removeWhite(src)
                        src.recycle()
                        Signatures.save(this@SignatureActivity, cut).also { cut.recycle() }
                    }
                    if (pickMode) { setResult(Activity.RESULT_OK, Intent().putExtra("path", f.absolutePath)); finish() }
                    else { toast("Добавлено"); renderList() }
                } catch (e: Throwable) { fail(e) }
            }
        }
    }
}

object Signatures {
    fun dir(ctx: Context) = File(ctx.filesDir, "signatures").apply { mkdirs() }
    fun all(ctx: Context): List<File> = (dir(ctx).listFiles() ?: emptyArray()).filter { it.name.endsWith(".png") }.sortedByDescending { it.lastModified() }
    fun save(ctx: Context, bmp: Bitmap): File = ImageTools.savePng(bmp, File(dir(ctx), "sign_${System.currentTimeMillis()}.png"))

    fun removeWhite(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val light = (r + g + b) / 3
            // чем светлее, тем прозрачнее; белый фон исчезает
            val a = ((235 - light) * 255 / 120).coerceIn(0, 255)
            px[i] = (a shl 24) or (p and 0xFFFFFF)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return trim(out)
    }

    /** Обрезает прозрачные поля вокруг подписи. */
    fun trim(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 20) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return src
        val pad = 8
        val l = (minX - pad).coerceAtLeast(0)
        val t = (minY - pad).coerceAtLeast(0)
        val r = (maxX + pad).coerceAtMost(w - 1)
        val b = (maxY + pad).coerceAtMost(h - 1)
        val out = Bitmap.createBitmap(src, l, t, r - l + 1, b - t + 1)
        if (out !== src) src.recycle()
        return out
    }
}

/** Поле для рисования подписи. */
class SignPad(ctx: Context) : View(ctx) {
    var color: Int = Color.rgb(16, 42, 120)
        set(v) { field = v; paint.color = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(3.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = this@SignPad.color
    }
    private val path = Path()
    private var empty = true
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 180, 195); textSize = ctx.dpf(15f); textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (empty) c.drawText("Распишитесь здесь", width / 2f, height / 2f, hint)
        c.drawPath(path, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { path.moveTo(e.x, e.y); empty = false }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) path.lineTo(e.getHistoricalX(i), e.getHistoricalY(i))
                path.lineTo(e.x, e.y)
            }
        }
        invalidate()
        return true
    }

    fun clear() { path.reset(); empty = true; invalidate() }

    /** Подпись на прозрачном фоне, обрезанная по краям, в двойном разрешении. */
    fun export(): Bitmap? {
        if (empty || width == 0) return null
        val k = 2f
        val b = Bitmap.createBitmap((width * k).toInt(), (height * k).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(k, k)
        c.drawPath(path, paint)
        return Signatures.trim(b)
    }
}
