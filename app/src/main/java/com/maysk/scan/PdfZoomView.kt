package com.maysk.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Просмотр PDF с настоящим масштабированием.
 *
 * Все страницы лежат в одной вертикальной ленте («документе»). Ленту можно двигать пальцем и
 * масштабировать двумя пальцами вокруг точки касания; двойной тап — приблизить / вернуть всю страницу.
 *
 * Масштаб [zoom] считается от режима «По ширине» (1.0 = страница на всю ширину экрана).
 * «Вся страница» — масштаб, при котором текущая страница целиком помещается на экране.
 *
 * Страницы рисуются в два слоя: целая страница в обычном разрешении и, при сильном увеличении,
 * чёткий фрагмент только той части, что видна на экране (память остаётся небольшой).
 */
class PdfZoomView(context: Context) : View(context) {

    enum class Mode { FIT_PAGE, FIT_WIDTH, FREE }

    var onZoomChanged: ((Float) -> Unit)? = null
    var onPageChanged: ((Int) -> Unit)? = null
    var onModeChanged: ((Mode) -> Unit)? = null

    var mode = Mode.FIT_PAGE
        private set
    var zoom = 1f
        private set
    var currentPage = 0
        private set
    val pageCount: Int get() = sizes.size

    // ---------- Документ ----------

    private var pdf: PdfRender? = null
    private var scope: CoroutineScope? = null
    private var sizes: List<Pair<Int, Int>> = emptyList()   // размеры страниц в пунктах
    private var startPage = 0
    private var started = false
    private var isNight = false

    // Раскладка в «единицах документа» (пиксели при zoom = 1)
    private val pad = context.dpf(8f)
    private val gap = context.dpf(8f)
    private var pageW = 1f
    private var pageTop = FloatArray(0)
    private var pageH = FloatArray(0)
    private var docH = 0f

    // Преобразование: экран = документ * zoom + (tx, ty)
    private var tx = 0f
    private var ty = 0f

    private var lastPage = -1
    private var lastZoom = -1f

    // ---------- Отрисовка ----------

    private val baseCache = object : LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 5L).coerceIn(48L * 1024 * 1024, 96L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    private class Tile(val bmp: Bitmap, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val zoom: Float) {
        fun covers(a: Float, b: Float, c: Float, d: Float) = x0 <= a + 0.5f && y0 <= b + 0.5f && x1 >= c - 0.5f && y1 >= d - 0.5f
    }

    private val tiles = HashMap<Int, Tile>()
    private val pending = HashMap<Int, Job>()
    private val detailJobs = HashMap<Int, Job>()
    private var loadJob: Job? = null
    private val renderMutex = Mutex()

    @Volatile private var generation = 0
    @Volatile private var wantedFirst = 0
    @Volatile private var wantedLast = -1
    @Volatile private var detailEpoch = 0

    private val rect = RectF()
    private val tileRect = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pagePaint = Paint().apply { style = Paint.Style.FILL }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1f)
        color = context.col(R.color.stroke)
    }

    private val detailRunnable = Runnable { updateDetail() }

    // ---------- Жесты ----------

    private val scroller = OverScroller(context)
    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                tx = scroller.currX.toFloat()
                ty = scroller.currY.toFloat()
                clampTranslation()
                afterTransform()
                postOnAnimation(this)
            }
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            stopFling()
            return started
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAround(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            stopFling()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!started) return false
            tx -= distanceX
            ty -= distanceY
            clampTranslation()
            afterTransform()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!started || scaleDetector.isInProgress) return false
            startFling(velocityX, velocityY)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!started) return false
            if (zoom < 1.5f) zoomAround(2.5f, e.x, e.y) else fitPage()
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    // ---------- Открытие и закрытие ----------

    /** Открывает документ. Размеры страниц читаются в фоне, после чего страница [startPage] показывается целиком. */
    fun open(r: PdfRender, scope: CoroutineScope, startPage: Int) {
        release()
        pdf = r
        this.scope = scope
        this.startPage = startPage
        val gen = generation
        loadJob = scope.launch {
            val list = withContext(Dispatchers.IO) {
                (0 until r.pageCount).map { i ->
                    try { r.pageSize(i) } catch (e: Exception) { 595 to 842 }
                }
            }
            if (gen != generation) return@launch
            sizes = list
            if (width > 0 && height > 0) applyInitial()
        }
    }

    /** Останавливает все фоновые задачи и очищает память. Вызывать перед закрытием [PdfRender]. */
    fun release() {
        generation++
        loadJob?.cancel()
        loadJob = null
        cancelJobs()
        removeCallbacks(detailRunnable)
        stopFling()
        baseCache.evictAll()
        tiles.clear()
        sizes = emptyList()
        started = false
        pdf = null
        lastPage = -1
        lastZoom = -1f
        invalidate()
    }

    private fun cancelJobs() {
        for (j in pending.values) j.cancel()
        pending.clear()
        for (j in detailJobs.values) j.cancel()
        detailJobs.clear()
    }

    private fun resetCaches() {
        generation++
        cancelJobs()
        baseCache.evictAll()
        tiles.clear()
    }

    fun setNight(value: Boolean) {
        if (isNight == value) return
        isNight = value
        resetCaches()
        invalidate()
    }

    // ---------- Раскладка ----------

    private fun buildLayout() {
        val n = sizes.size
        pageW = max(1f, width - 2 * pad)
        pageTop = FloatArray(n)
        pageH = FloatArray(n)
        var y = pad
        for (i in 0 until n) {
            val (pw, ph) = sizes[i]
            pageH[i] = max(1f, pageW * ph / max(1, pw))
            pageTop[i] = y
            y += pageH[i] + gap
        }
        docH = if (n > 0) y - gap + pad else 0f
    }

    private fun applyInitial() {
        if (sizes.isEmpty()) return
        buildLayout()
        started = true
        lastPage = -1
        lastZoom = -1f
        tx = 0f
        ty = 0f
        changeMode(Mode.FIT_PAGE)
        goToPage(startPage, animate = false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (sizes.isEmpty() || w <= 0 || h <= 0) return
        if (!started) {
            applyInitial()
            return
        }
        val keep = currentPage
        val widthChanged = w != oldw
        if (widthChanged) resetCaches()
        buildLayout()
        if (mode == Mode.FREE && !widthChanged) {
            // Изменилась только высота (например, открыли панель масштаба) — остаёмся на том же месте
            clampTranslation()
            afterTransform()
        } else {
            goToPage(keep, animate = false)
        }
    }

    /** Масштаб, при котором страница [i] целиком помещается на экране. */
    private fun fitScale(i: Int): Float = when (mode) {
        Mode.FIT_WIDTH -> 1f
        Mode.FIT_PAGE -> min(1f, (height - 2 * pad) / pageH[i]).coerceAtLeast(MIN_ZOOM)
        Mode.FREE -> zoom
    }

    private fun clampX(v: Float, z: Float): Float {
        val cw = width * z
        return if (cw <= width) (width - cw) / 2f else v.coerceIn(width - cw, 0f)
    }

    private fun clampY(v: Float, z: Float): Float {
        val ch = docH * z
        return if (ch <= height) (height - ch) / 2f else v.coerceIn(height - ch, 0f)
    }

    private fun clampTranslation() {
        tx = clampX(tx, zoom)
        ty = clampY(ty, zoom)
    }

    // ---------- Управление ----------

    /** Переход к странице. В режимах «По ширине» / «Вся страница» страница подгоняется заново. */
    fun goToPage(index: Int, animate: Boolean = true) {
        if (!started || sizes.isEmpty()) return
        val i = index.coerceIn(0, sizes.size - 1)
        val oldZoom = zoom
        if (mode != Mode.FREE) zoom = fitScale(i)
        val z = zoom
        val top = pageTop[i] * z
        val ph = pageH[i] * z
        val targetTy = clampY(if (ph <= height) (height - ph) / 2f - top else -top + pad * z, z)
        val targetTx = clampX(tx, z)
        stopFling()
        val far = abs(targetTy - ty) > height * 3f
        if (animate && !far && abs(z - oldZoom) < 0.001f) {
            tx = clampX(tx, z)
            scroller.startScroll(
                tx.roundToInt(), ty.roundToInt(),
                (targetTx - tx).roundToInt(), (targetTy - ty).roundToInt(), 220
            )
            postOnAnimation(flingRunnable)
            afterTransform()
        } else {
            tx = targetTx
            ty = targetTy
            afterTransform()
        }
    }

    /** Масштаб в долях «По ширине» вокруг центра экрана (кнопки «+ / −», ползунок). */
    fun setZoomLevel(value: Float) = zoomAround(value, width / 2f, height / 2f)

    /** Подгонка по ширине экрана, место чтения не теряется. */
    fun fitWidth() {
        if (!started) return
        stopFling()
        val centerY = (height / 2f - ty) / zoom
        zoom = 1f
        changeMode(Mode.FIT_WIDTH)
        tx = clampX(0f, zoom)
        ty = clampY(height / 2f - centerY * zoom, zoom)
        afterTransform()
    }

    /** Текущая страница целиком на экране. */
    fun fitPage() {
        if (!started) return
        changeMode(Mode.FIT_PAGE)
        goToPage(currentPage, animate = false)
    }

    private fun zoomAround(target: Float, fx: Float, fy: Float) {
        if (!started) return
        val z = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = z / zoom
        tx = fx - (fx - tx) * k
        ty = fy - (fy - ty) * k
        zoom = z
        changeMode(Mode.FREE)
        clampTranslation()
        afterTransform()
    }

    private fun changeMode(m: Mode) {
        if (mode == m) return
        mode = m
        onModeChanged?.invoke(m)
    }

    private fun stopFling() {
        scroller.forceFinished(true)
        removeCallbacks(flingRunnable)
    }

    private fun startFling(vx: Float, vy: Float) {
        val cw = width * zoom
        val ch = docH * zoom
        val minX = if (cw <= width) tx.roundToInt() else (width - cw).roundToInt()
        val maxX = if (cw <= width) tx.roundToInt() else 0
        val minY = if (ch <= height) ty.roundToInt() else (height - ch).roundToInt()
        val maxY = if (ch <= height) ty.roundToInt() else 0
        scroller.fling(tx.roundToInt(), ty.roundToInt(), vx.roundToInt(), vy.roundToInt(), minX, maxX, minY, maxY)
        postOnAnimation(flingRunnable)
    }

    private fun afterTransform() {
        updateCurrentPage()
        if (lastZoom != zoom) {
            lastZoom = zoom
            onZoomChanged?.invoke(zoom)
        }
        invalidate()
        scheduleDetail()
    }

    private fun updateCurrentPage() {
        if (sizes.isEmpty()) return
        val centerY = (height / 2f - ty) / zoom
        currentPage = indexAt(centerY + gap / 2f)
        if (currentPage != lastPage) {
            lastPage = currentPage
            onPageChanged?.invoke(currentPage)
        }
    }

    /** Номер последней страницы, верх которой не ниже [y] (координаты документа). */
    private fun indexAt(y: Float): Int {
        var lo = 0
        var hi = pageTop.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pageTop[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    // ---------- Рисование ----------

    override fun onDraw(canvas: Canvas) {
        if (!started || sizes.isEmpty()) return
        val docTop = -ty / zoom
        val docBottom = (height - ty) / zoom
        val first = indexAt(docTop)
        val last = indexAt(docBottom)
        wantedFirst = max(0, first - 1)
        wantedLast = min(sizes.size - 1, last + 1)
        pagePaint.color = if (isNight) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()

        for (i in first..last) {
            val top = pageTop[i]
            if (top + pageH[i] < docTop || top > docBottom) continue
            val l = tx + pad * zoom
            val t = ty + top * zoom
            rect.set(l, t, l + pageW * zoom, t + pageH[i] * zoom)
            canvas.drawRect(rect, pagePaint)
            baseCache.get(i)?.let { canvas.drawBitmap(it, null, rect, bmpPaint) }
            tiles[i]?.let { tile ->
                val tl = tx + (pad + tile.x0) * zoom
                val tt = ty + (top + tile.y0) * zoom
                val tr = tx + (pad + tile.x1) * zoom
                val tb = ty + (top + tile.y1) * zoom
                tileRect.set(tl, tt, tr, tb)
                canvas.drawBitmap(tile.bmp, null, tileRect, bmpPaint)
            }
            canvas.drawRect(rect, borderPaint)
        }
        for (i in wantedFirst..wantedLast) {
            if (baseCache.get(i) == null) requestBase(i)
        }
    }

    // ---------- Фоновая отрисовка ----------

    /** Ширина «целой» картинки страницы: чуть больше экранной, с ограничением по памяти. */
    private fun baseWidth(i: Int): Int {
        var w = (pageW * 1.3f).toInt().coerceIn(300, 2000)
        val ratio = pageH[i] / pageW
        if (w * w * ratio > MAX_BASE_PIXELS) w = sqrt(MAX_BASE_PIXELS / ratio).toInt()
        if (w * ratio > MAX_EDGE) w = (MAX_EDGE / ratio).toInt()
        return max(100, w)
    }

    private fun requestBase(i: Int) {
        if (pending.containsKey(i)) return
        val r = pdf ?: return
        val sc = scope ?: return
        val w = baseWidth(i)
        val gen = generation
        val night = isNight
        pending[i] = sc.launch {
            val bmp = withContext(Dispatchers.IO) {
                renderMutex.withLock {
                    // Пока ждали очереди, страница могла уйти из видимой области — не тратим время
                    if (gen != generation || i < wantedFirst || i > wantedLast) null
                    else try { r.render(i, w, night) } catch (e: Throwable) { null }
                }
            }
            if (gen != generation) return@launch
            pending.remove(i)
            if (bmp != null) {
                baseCache.put(i, bmp)
                invalidate()
                scheduleDetail()
            }
        }
    }

    private fun scheduleDetail() {
        removeCallbacks(detailRunnable)
        detailEpoch++
        postDelayed(detailRunnable, 160)
    }

    /** При увеличении дорисовывает чёткий фрагмент видимой части страницы. */
    private fun updateDetail() {
        val r = pdf ?: return
        val sc = scope ?: return
        if (!started || sizes.isEmpty()) return
        val epoch = detailEpoch
        val gen = generation
        val night = isNight
        val dx0 = -tx / zoom
        val dx1 = (width - tx) / zoom
        val dy0 = -ty / zoom
        val dy1 = (height - ty) / zoom
        val keep = HashSet<Int>()

        for (i in indexAt(dy0)..indexAt(dy1)) {
            val top = pageTop[i]
            if (top + pageH[i] < dy0 || top > dy1) continue
            val base = baseCache.get(i) ?: continue
            // Обычной картинки хватает — фрагмент не нужен
            if (pageW * zoom <= base.width * 1.05f) continue
            // Видимая часть страницы (в единицах документа, от угла страницы)
            val nx0 = max(0f, dx0 - pad)
            val nx1 = min(pageW, dx1 - pad)
            val ny0 = max(0f, dy0 - top)
            val ny1 = min(pageH[i], dy1 - top)
            if (nx1 <= nx0 || ny1 <= ny0) continue
            keep.add(i)
            val old = tiles[i]
            if (old != null && old.covers(nx0, ny0, nx1, ny1) && abs(old.zoom / zoom - 1f) < 0.10f) continue
            if (detailJobs.containsKey(i)) continue

            // Фрагмент с запасом по краям, чтобы небольшие сдвиги не вызывали перерисовку
            val px = (nx1 - nx0) * 0.2f
            val py = (ny1 - ny0) * 0.2f
            val x0 = max(0f, nx0 - px)
            val y0 = max(0f, ny0 - py)
            val k = zoom
            val bw = ceil((min(pageW, nx1 + px) - x0) * k).toInt().coerceIn(1, MAX_EDGE.toInt())
            val bh = ceil((min(pageH[i], ny1 + py) - y0) * k).toInt().coerceIn(1, MAX_EDGE.toInt())
            val x1 = x0 + bw / k
            val y1 = y0 + bh / k
            val pxPerPoint = pageW * k / max(1, sizes[i].first)

            detailJobs[i] = sc.launch {
                val bmp = withContext(Dispatchers.IO) {
                    renderMutex.withLock {
                        if (gen != generation || epoch != detailEpoch) null
                        else try { r.renderRegion(i, pxPerPoint, x0 * k, y0 * k, bw, bh, night) } catch (e: Throwable) { null }
                    }
                }
                if (gen != generation) return@launch
                detailJobs.remove(i)
                if (bmp != null) {
                    tiles[i] = Tile(bmp, x0, y0, x1, y1, k)
                    invalidate()
                    scheduleDetail()   // пока рисовали, экран мог сдвинуться — проверим, хватает ли фрагмента
                }
            }
        }
        // Фрагменты страниц, которые больше не нужны, освобождаем
        val it = tiles.keys.iterator()
        while (it.hasNext()) if (it.next() !in keep) it.remove()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(detailRunnable)
        stopFling()
        super.onDetachedFromWindow()
    }

    companion object {
        const val MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 4f
        private const val MAX_BASE_PIXELS = 8_000_000f
        private const val MAX_EDGE = 4096f
    }
}
