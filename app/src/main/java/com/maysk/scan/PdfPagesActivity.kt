package com.maysk.scan

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * Редактирование страниц PDF: добавить, удалить, повернуть, переместить (перетаскиванием),
 * дублировать, заменить, извлечь.
 */
class PdfPagesActivity : BaseActivity() {

    private lateinit var src: File
    private val items = ArrayList<PdfTools.Item>()
    private val selected = HashSet<PdfTools.Item>()
    private val renders = HashMap<String, PdfRender>()
    private val thumbs = LruCache<String, Bitmap>(60)
    private lateinit var grid: RecyclerView
    private lateinit var info: TextView
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        src = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        val body = screen("Редактирование PDF", scroll = false)
        addTopAction(R.drawable.ic_check) { save() }

        info = text("Нажмите на страницу, чтобы выбрать. Удерживайте и тяните, чтобы переместить.", 12.5f, R.color.text2)
        body.addView(info, lp().apply { bottomMargin = dp(8) })

        grid = RecyclerView(this)
        grid.layoutManager = GridLayoutManager(this, 3)
        body.addView(grid, lp(MATCH, 0, 1f))

        body.addView(actionRow(
            actionButton(R.drawable.ic_add, "Добавить") { addPages() },
            actionButton(R.drawable.ic_delete, "Удалить") { deleteSelected() },
            actionButton(R.drawable.ic_rotate, "Повернуть") { rotateSelected() },
            actionButton(R.drawable.ic_more, "Ещё") { more() },
        ), lp().apply { topMargin = dp(8) })
        body.addView(actionRow(
            actionButton(R.drawable.ic_extract, "Извлечь") { extractSelected() },
            actionButton(R.drawable.ic_save, "Сохранить", highlighted = true) { save() },
        ), lp().apply { topMargin = dp(8) })

        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { renderFor(src) } ?: run { toast("PDF защищён паролем — сначала снимите пароль"); finish(); return@launch }
                for (i in 0 until r.pageCount) items.add(PdfTools.Item(src, i))
                grid.adapter = Adapter()
                touch.attachToRecyclerView(grid)
                updateInfo()
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    override fun onDestroy() {
        renders.values.forEach { it.close() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (changed) confirm("Выйти без сохранения?", "Изменения страниц не сохранены.", "Выйти") { finish() }
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    @Synchronized
    private fun renderFor(f: File): PdfRender? = renders[f.absolutePath] ?: PdfRender.tryOpen(f)?.also { renders[f.absolutePath] = it }

    private fun updateInfo() {
        info.text = if (selected.isEmpty()) "Страниц: ${items.size}. Нажмите на страницу, чтобы выбрать; удерживайте и тяните, чтобы переместить."
        else "Выбрано: ${selected.size} из ${items.size}"
    }

    private val touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
            val from = a.bindingAdapterPosition
            val to = b.bindingAdapterPosition
            if (from < 0 || to < 0) return false
            Collections.swap(items, from, to)
            rv.adapter?.notifyItemMoved(from, to)
            changed = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            rv.adapter?.notifyDataSetChanged() // обновить номера
        }
    })

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val box = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, dp(170)).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            box.addView(ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFFFFFFFF.toInt())
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            box.addView(text("", 12f, R.color.white_fixed, bold = true).apply {
                background = rounded(col(R.color.accent), 10)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })
            box.addView(ImageView(parent.context).apply {
                setImageResource(R.drawable.ic_check)
                background = rounded(col(R.color.accent), 12)
                setPadding(dp(3), dp(3), dp(3), dp(3))
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
            return object : RecyclerView.ViewHolder(box) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val box = h.itemView as FrameLayout
            val iv = box.getChildAt(0) as ImageView
            val num = box.getChildAt(1) as TextView
            val check = box.getChildAt(2)
            val item = items[pos]
            val sel = item in selected
            box.background = if (sel) rounded(col(R.color.card), 12, col(R.color.accent), 3) else rounded(col(R.color.card), 12, col(R.color.stroke))
            check.visibility = if (sel) android.view.View.VISIBLE else android.view.View.GONE
            num.text = "${pos + 1}"
            iv.rotation = item.rotation.toFloat()
            box.setOnClickListener {
                if (item in selected) selected.remove(item) else selected.add(item)
                notifyItemChanged(h.bindingAdapterPosition)
                updateInfo()
            }
            val key = item.file.absolutePath + "#" + item.index
            iv.tag = key
            thumbs.get(key)?.let { iv.setImageBitmap(it); return }
            iv.setImageBitmap(null)
            lifecycleScope.launch {
                val b = withContext(Dispatchers.IO) {
                    try {
                        if (item.index >= 0) renderFor(item.file)?.render(item.index, 260)
                        else ImageTools.decodeFile(item.file, 300)
                    } catch (e: Throwable) { null }
                } ?: return@launch
                thumbs.put(key, b)
                if (iv.tag == key) iv.setImageBitmap(b)
            }
        }
    }

    private fun refresh() {
        grid.adapter?.notifyDataSetChanged()
        updateInfo()
    }

    private fun targets(): List<PdfTools.Item> {
        if (selected.isEmpty()) { toast("Сначала выберите страницы — нажмите на них"); return emptyList() }
        return items.filter { it in selected }
    }

    private fun rotateSelected() {
        val t = targets().ifEmpty { return }
        t.forEach { it.rotation = (it.rotation + 90) % 360 }
        changed = true
        refresh()
    }

    private fun deleteSelected() {
        val t = targets().ifEmpty { return }
        if (t.size == items.size) { toast("Нельзя удалить все страницы"); return }
        confirm("Удалить страницы?", "Будет удалено страниц: ${t.size}", "Удалить") {
            items.removeAll(t.toSet())
            selected.clear()
            changed = true
            refresh()
        }
    }

    private fun insertPos(): Int {
        val idx = items.indexOfLast { it in selected }
        return if (idx >= 0) idx + 1 else items.size
    }

    private fun addPages() {
        choose("Добавить страницы", listOf("Из галереи (фото)", "Сфотографировать", "Из другого PDF", "Пустая страница")) { i ->
            when (i) {
                0 -> pick(arrayOf("image/*"), true) { uris -> addImages(uris) }
                1 -> photo { uri -> if (uri != null) addImages(listOf(uri)) }
                2 -> pick(arrayOf("application/pdf"), false) { uris ->
                    lifecycleScope.launch {
                        try {
                            val f = withContext(Dispatchers.IO) { Files.copyToCache(this@PdfPagesActivity, uris[0]) }
                            val r = withContext(Dispatchers.IO) { renderFor(f) } ?: run { toast("Этот PDF защищён паролем"); return@launch }
                            val pos = insertPos()
                            items.addAll(pos, (0 until r.pageCount).map { PdfTools.Item(f, it) })
                            changed = true
                            refresh()
                        } catch (e: Throwable) { fail(e) }
                    }
                }
                3 -> {
                    val f = File(cacheDir, "blank_${System.nanoTime()}.png")
                    val b = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFFFFFF.toInt()) }
                    ImageTools.savePng(b, f)
                    b.recycle()
                    items.add(insertPos(), PdfTools.Item(f, -1))
                    changed = true
                    refresh()
                }
            }
        }
    }

    private fun addImages(uris: List<android.net.Uri>) {
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    uris.map { u ->
                        val bmp = ImageTools.decode(this@PdfPagesActivity, u, 2600)
                        val f = File(cacheDir, "add_${System.nanoTime()}.jpg")
                        ImageTools.saveJpeg(bmp, f, 90)
                        bmp.recycle()
                        f
                    }
                }
                items.addAll(insertPos(), files.map { PdfTools.Item(it, -1) })
                changed = true
                refresh()
            } catch (e: Throwable) { fail(e) }
        }
    }

    private fun more() {
        choose("Ещё", listOf("Дублировать выбранные", "Заменить выбранную страницу фото", "Переместить выбранные…", "Выбрать все", "Снять выбор")) { i ->
            when (i) {
                0 -> {
                    val t = targets().ifEmpty { return@choose }
                    val pos = insertPos()
                    items.addAll(pos, t.map { PdfTools.Item(it.file, it.index, it.rotation) })
                    changed = true
                    refresh()
                }
                1 -> {
                    val t = targets().ifEmpty { return@choose }
                    if (t.size != 1) { toast("Выберите одну страницу"); return@choose }
                    pick(arrayOf("image/*")) { uris ->
                        lifecycleScope.launch {
                            try {
                                val f = withContext(Dispatchers.IO) {
                                    val bmp = ImageTools.decode(this@PdfPagesActivity, uris[0], 2600)
                                    val out = File(cacheDir, "rep_${System.nanoTime()}.jpg")
                                    ImageTools.saveJpeg(bmp, out, 90); bmp.recycle(); out
                                }
                                val idx = items.indexOf(t[0])
                                items[idx] = PdfTools.Item(f, -1)
                                selected.clear()
                                changed = true
                                refresh()
                            } catch (e: Throwable) { fail(e) }
                        }
                    }
                }
                2 -> {
                    val t = targets().ifEmpty { return@choose }
                    ask("Переместить на позицию", "1 – ${items.size}", "1", android.text.InputType.TYPE_CLASS_NUMBER) { s ->
                        val to = (s.toIntOrNull() ?: return@ask).coerceIn(1, items.size) - 1
                        items.removeAll(t.toSet())
                        items.addAll(to.coerceAtMost(items.size), t)
                        changed = true
                        refresh()
                    }
                }
                3 -> { selected.addAll(items); refresh() }
                4 -> { selected.clear(); refresh() }
            }
        }
    }

    private fun extractSelected() {
        val t = targets().ifEmpty { return }
        lifecycleScope.launch {
            showProgress("Извлекаю страницы…")
            try {
                val out = Files.newDoc(this@PdfPagesActivity, Files.baseName(src.name) + " (страницы)", "pdf")
                withContext(Dispatchers.IO) { PdfTools.build(t, out); Files.exportCopy(this@PdfPagesActivity, out) }
                History.add(this@PdfPagesActivity, Kind.PDF, out)
                hideProgress()
                openResult(out)
            } catch (e: Throwable) { fail(e) }
        }
    }

    private fun save() {
        lifecycleScope.launch {
            showProgress("Сохраняю PDF…")
            try {
                val out = Files.newDoc(this@PdfPagesActivity, Files.baseName(src.name) + " (изменён)", "pdf")
                withContext(Dispatchers.IO) { PdfTools.build(items, out); Files.exportCopy(this@PdfPagesActivity, out) }
                History.add(this@PdfPagesActivity, Kind.PDF, out)
                hideProgress()
                changed = false
                openResult(out)
                finish()
            } catch (e: Throwable) { fail(e) }
        }
    }

    private fun openResult(f: File) {
        toast("Сохранено: ${f.name}")
        startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, f.absolutePath))
    }
}
