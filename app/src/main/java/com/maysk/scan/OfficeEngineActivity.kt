package com.maysk.scan

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.opendocument.core.DecodedFile
import app.opendocument.core.Document
import app.opendocument.core.DocumentFile
import app.opendocument.core.Html
import app.opendocument.core.HtmlConfig
import app.opendocument.core.HtmlService
import app.opendocument.core.Odr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Неоновая тёмная палитра редакторов
private const val BG = 0xFF07101E.toInt()
private const val PANEL = 0xFF0D1B30.toInt()
private const val CARD = 0xFF12294A.toInt()
private const val STROKE = 0xFF23497E.toInt()
private const val TXT = 0xFFE9F2FF.toInt()
private const val TXT2 = 0xFF8EA5C6.toInt()

/** Офисный редактор на движке OpenDocument.core: Word / Excel / PowerPoint в неоновом оформлении. */
class OfficeEngineActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var statusView: TextView
    private lateinit var pageBar: LinearLayout
    private lateinit var pageScroll: HorizontalScrollView
    private lateinit var tabsRow: LinearLayout
    private lateinit var toolsRow: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var ribbonBox: LinearLayout
    private lateinit var bottomBox: LinearLayout
    private lateinit var saveBtn: View
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var aiFab: View
    private lateinit var selectionBar: View
    private lateinit var showOverlay: View

    private val toolViews = ArrayList<View>()
    private var activeTab = "Главная"
    private var accent = 0xFF2B7BFF.toInt()   // цвет приложения: Word / Excel / PowerPoint
    private var appName = "Word"
    private var decoded: DecodedFile? = null
    private var documentFile: DocumentFile? = null
    private var document: Document? = null
    private var service: HtmlService? = null
    private var pages: List<app.opendocument.core.HtmlPage> = emptyList()
    private var renderDirectory: File? = null
    private var workCopy: File? = null
    private var currentPage = 0
    private var sourceFile: File? = null
    private var sourceUri: Uri? = null
    private var documentText: String = ""
    private var editable = false
    private var isApplying = false
    private var webReady = false

    private var dirty = false            // есть несохранённые правки
    private var modelDirty = false       // правки уже применены к документу, но ещё не записаны в файл
    private var lastSavedAt = 0L
    private var silentSave = false
    private var exportOnly = false
    private var finishAfterSave = false
    private var selectedText = ""
    private var slideshow = false
    private var landscape = false
    private var restoreX = 0
    private var restoreY = 0
    private val handler = Handler(Looper.getMainLooper())
    private val autosave = object : Runnable {
        override fun run() {
            autosaveTick()
            updateStatus()
            handler.postDelayed(this, 20_000)
        }
    }

    private data class RenderedDocument(
        val service: HtmlService,
        val pages: List<app.opendocument.core.HtmlPage>,
        val directory: File,
        val extractedText: String,
    )
    private data class SavedDocument(val bytes: ByteArray, val refreshed: RenderedDocument?, val changed: Boolean)

    private val createCopy = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) {
            finishSave()
        } else {
            runCatching {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            sourceUri = uri
            val bytes = pendingBytes
            if (bytes != null) writeBytes(uri, bytes) else finishSave()
        }
    }
    private val exportCopy = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBytes
        if (uri != null && bytes != null) {
            runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Провайдер не разрешил запись") }
                .onSuccess { toast("Копия сохранена") }
                .onFailure { toast("Не удалось сохранить копию: ${it.message}") }
        }
        finishSave()
    }
    private var pendingBytes: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceFile = intent.getStringExtra(com.maysk.scan.EXTRA_FILE)?.let(::File)
        sourceUri = intent.getStringExtra("source_uri")?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        sourceUri?.let { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        val type = Docs.typeOf(sourceFile?.name.orEmpty()) ?: Docs.Type.WORD
        accent = type.color
        appName = type.title
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        val file = sourceFile
        if (file == null || !file.exists()) { toast("Файл не найден"); finish(); return }
        titleView.text = file.name
        openDocument(file)
        handler.postDelayed(autosave, 20_000)
    }

    // ---------- Интерфейс ----------

    private fun tint(res: Int, color: Int = TXT): ImageView = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
    }

    private fun topIcon(res: Int, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        addView(tint(res), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private fun pill(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setTextColor(if (selected) Color.WHITE else TXT2)
        setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
        background = GradientDrawable().apply {
            cornerRadius = dpf(18f)
            setColor(if (selected) accent else Color.TRANSPARENT)
        }
        setOnClickListener { onClick() }
    }

    private fun toolButton(label: String, style: Int = Typeface.NORMAL, underline: Boolean = false, needsEdit: Boolean = true, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(40)
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(TXT)
            setTypeface(Typeface.DEFAULT, style)
            if (underline) paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), GradientDrawable().apply {
                cornerRadius = dpf(12f); setColor(CARD); setStroke(dp(1), STROKE)
            }, null)
            alpha = if (needsEdit && !editable) 0.4f else 1f
            if (needsEdit) toolViews.add(this)
            setOnClickListener { onClick() }
        }

    private fun separator(): View = View(this).apply {
        setBackgroundColor(STROKE)
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(24)).apply { setMargins(dp(6), 0, dp(6), 0) }
    }

    private fun buildUi() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val frame = FrameLayout(this).apply { setBackgroundColor(BG) }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Верх: назад · название · сохранить · меню
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setBackgroundColor(BG)
        }
        topBar.addView(topIcon(R.drawable.ic_back) { handleBack() })
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(8), 0)
            setOnClickListener { renameDialog() }
        }
        titleView = TextView(this).apply {
            textSize = 16f
            setTextColor(TXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        subtitleView = TextView(this).apply { textSize = 12f; setTextColor(accent); maxLines = 1 }
        names.addView(titleView)
        names.addView(subtitleView)
        topBar.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (appName == "PowerPoint") {
            topBar.addView(TextView(this).apply {
                text = "▶"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(TXT)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setOnClickListener { startSlideshow() }
            })
        }
        saveBtn = topIcon(R.drawable.ic_save) { requestSave() }.apply { alpha = 0.4f }
        topBar.addView(saveBtn)
        topBar.addView(topIcon(R.drawable.ic_more) { showMenu() })
        main.addView(topBar)

        // Лента: вкладки и инструменты
        ribbonBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(PANEL) }
        val tabsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(2)) }
        tabsScroll.addView(tabsRow)
        ribbonBox.addView(tabsScroll)
        val toolsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        toolsScroll.addView(toolsRow)
        ribbonBox.addView(toolsScroll)
        main.addView(ribbonBox)
        main.addView(View(this).apply { setBackgroundColor(STROKE) }, LinearLayout.LayoutParams(-1, dp(1)))
        renderRibbon()

        web = WebView(this).apply {
            setBackgroundColor(0xFF0A1424.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (editable) view?.evaluateJavascript(
                        "(function(){if(window.odr&&odr.editing&&odr.editing.enable){odr.editing.enable();}})();", null)
                    view?.evaluateJavascript(TRACKER_JS, null)
                    if (restoreX != 0 || restoreY != 0) {
                        view?.evaluateJavascript("window.scrollTo($restoreX,$restoreY);", null)
                        restoreX = 0; restoreY = 0
                    }
                    webReady = true
                    if (!isApplying) setSaveEnabled(editable)
                    updateStatus()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url ?: return true
                    if (url.scheme != "file") return true
                    val root = runCatching { renderDirectory?.canonicalFile }.getOrNull() ?: return true
                    val localPath = url.path ?: return true
                    val target = runCatching { File(localPath).canonicalFile }.getOrNull() ?: return true
                    return !(target.path == root.path || target.path.startsWith(root.path + File.separator))
                }
            }
            addJavascriptInterface(EditBridge(), "MayskOffice")
        }
        main.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        // Низ: ярлыки листов/слайдов и строка состояния
        bottomBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(PANEL) }
        pageScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE }
        pageBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        pageScroll.addView(pageBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomBox.addView(View(this).apply { setBackgroundColor(STROKE) }, LinearLayout.LayoutParams(-1, dp(1)))
        bottomBox.addView(pageScroll, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(6), dp(4))
        }
        statusView = TextView(this).apply { textSize = 12f; setTextColor(TXT2); maxLines = 2 }
        statusRow.addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        prevBtn = navArrow("◀") { goPage(-1) }
        nextBtn = navArrow("▶") { goPage(1) }
        statusRow.addView(prevBtn)
        statusRow.addView(nextBtn)
        bottomBox.addView(statusRow, LinearLayout.LayoutParams(-1, dp(44)))
        main.addView(bottomBox)
        frame.addView(main, FrameLayout.LayoutParams(-1, -1))

        // Плавающая кнопка AI-помощника
        aiFab = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, darker(accent))).apply { shape = GradientDrawable.OVAL }
            elevation = dpf(8f)
            addView(tint(R.drawable.ic_ai, Color.WHITE), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            setOnClickListener { aiSheet() }
        }
        frame.addView(aiFab, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(14), dp(104)) })

        // Плавающая панель над выделенным текстом
        val selScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dpf(20f); setColor(0xF2102A4D.toInt()); setStroke(dp(1), accent) }
            elevation = dpf(10f)
        }
        val selRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        for ((label, action) in AI_ACTIONS) {
            selRow.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(TXT)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { askAi(action) }
            })
        }
        selScroll.addView(selRow)
        selectionBar = selScroll
        frame.addView(selScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(12), 0, dp(12), dp(160))
        })

        // Режим показа слайдов: касание справа — дальше, слева — назад
        showOverlay = View(this).apply {
            visibility = View.GONE
            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_UP) goPage(if (ev.x < v.width / 3f) -1 else 1)
                true
            }
        }
        frame.addView(showOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        setSaveEnabled(false)
    }

    private fun navArrow(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(TXT)
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
        setOnClickListener { onClick() }
    }

    private fun tabNames(): List<String> =
        if (appName == "Word") listOf("Главная", "Рецензирование", "Вид") else listOf("Главная", "Вид")

    private fun renderRibbon() {
        tabsRow.removeAllViews()
        toolsRow.removeAllViews()
        toolViews.clear()
        for (name in tabNames()) {
            tabsRow.addView(pill(name, name == activeTab) { activeTab = name; renderRibbon() })
        }
        when (activeTab) {
            "Главная" -> {
                toolsRow.addView(toolButton("Ж", Typeface.BOLD) { runEditorCommand("odr.editing.toggle('bold')") })
                toolsRow.addView(toolButton("К", Typeface.ITALIC) { runEditorCommand("odr.editing.toggle('italic')") })
                toolsRow.addView(toolButton("Ч", underline = true) { runEditorCommand("odr.editing.toggle('underline')") })
                toolsRow.addView(separator())
                toolsRow.addView(toolButton("↶") { runEditorCommand("odr.editing.undo()") })
                toolsRow.addView(toolButton("↷") { runEditorCommand("odr.editing.redo()") })
                if (appName == "Excel") {
                    toolsRow.addView(separator())
                    toolsRow.addView(toolButton("Σ Сумма", needsEdit = false) { toast("Сумма: ${selectionNumbers().let { n -> if (n.isEmpty()) "—" else fmt(n.sum()) }}") })
                    toolsRow.addView(toolButton("Среднее", needsEdit = false) { toast("Среднее: ${selectionNumbers().let { n -> if (n.isEmpty()) "—" else fmt(n.average()) }}") })
                    toolsRow.addView(toolButton("Количество", needsEdit = false) { toast("Количество: ${selectionNumbers().size}") })
                }
            }
            "Рецензирование" -> {
                toolsRow.addView(toolButton("Статистика", needsEdit = false) { statsDialog() })
                toolsRow.addView(toolButton("Перевод", needsEdit = false) { askAi("translate") })
                toolsRow.addView(toolButton("Проверка", needsEdit = false) { askAi("check") })
                toolsRow.addView(toolButton("Версии", needsEdit = false) { versionsDialog() })
            }
            else -> {
                toolsRow.addView(toolButton("−", needsEdit = false) { web.zoomOut() })
                toolsRow.addView(toolButton("+", needsEdit = false) { web.zoomIn() })
                toolsRow.addView(separator())
                toolsRow.addView(toolButton("Ландшафт", needsEdit = false) { toggleLandscape() })
                toolsRow.addView(toolButton("Найти", needsEdit = false) { findDialog() })
                if (appName == "PowerPoint") toolsRow.addView(toolButton("▶ Показ", needsEdit = false) { startSlideshow() })
            }
        }
    }

    private fun setSaveEnabled(on: Boolean) {
        if (!::saveBtn.isInitialized) return
        saveBtn.isEnabled = on
        saveBtn.alpha = if (on) 1f else 0.4f
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e12) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)

    private fun selectionNumbers(): List<Double> =
        Regex("-?\\d+(?:[.,]\\d+)?").findAll(selectedText).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toList()

    private fun updateStatus() {
        if (!::statusView.isInitialized) return
        val unit = when (appName) { "Excel" -> "Лист"; "PowerPoint" -> "Слайд"; else -> "Стр." }
        val words = if (documentText.isBlank()) 0 else documentText.trim().split(Regex("\\s+")).size
        val parts = ArrayList<String>()
        if (pages.size > 1 || appName != "Word") parts.add("$unit ${currentPage + 1} из ${maxOf(pages.size, 1)}")
        if (appName == "Excel" && selectionNumbers().isNotEmpty()) {
            val n = selectionNumbers()
            parts.add("Σ Сумма: ${fmt(n.sum())}")
            parts.add("Среднее: ${fmt(n.average())}")
            parts.add("Количество: ${n.size}")
        } else {
            parts.add("$words слов")
        }
        parts.add(saveState())
        statusView.text = parts.joinToString(" · ")
        val multi = pages.size > 1
        prevBtn.visibility = if (multi) View.VISIBLE else View.GONE
        nextBtn.visibility = if (multi) View.VISIBLE else View.GONE
        subtitleView.text = saveState().let { if (dirty) "● $it" else "✓ $it" }
    }

    private fun saveState(): String = when {
        !editable && documentFile != null -> "Только просмотр"
        dirty -> "Есть несохранённые изменения"
        lastSavedAt > 0 -> {
            val sec = (System.currentTimeMillis() - lastSavedAt) / 1000
            when {
                sec < 60 -> "Сохранено только что"
                sec < 3600 -> "Сохранено ${sec / 60} мин назад"
                else -> "Сохранено"
            }
        }
        else -> "Без изменений"
    }

    // ---------- Открытие документа ----------

    private fun openDocument(file: File) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // Движок работает с рабочей копией, поэтому запись в исходный файл ему не мешает
                    val dir = File(cacheDir, "edit_${System.nanoTime()}").apply { mkdirs() }
                    val work = File(dir, file.name)
                    file.copyTo(work, overwrite = true)
                    workCopy = work
                    decoded = Odr.open(work.absolutePath)
                    val opened = decoded ?: error("Не удалось открыть файл")
                    require(opened.isDocumentFile) { "Этот формат не является редактируемым офисным документом" }
                    documentFile = opened.asDocumentFile()
                    document = documentFile!!.document()
                    editable = document!!.isEditable && document!!.isSavable
                    val rendered = renderDocumentPages(document!!, editable)
                    service = rendered.service
                    pages = rendered.pages
                    renderDirectory = rendered.directory
                    documentText = rendered.extractedText
                }
            }.onSuccess {
                setSaveEnabled(editable)
                toolViews.forEach { it.alpha = if (editable) 1f else 0.4f }
                renderPageTabs()
                showPage(0)
                updateStatus()
                if (!editable) toast("Формат открыт для просмотра: сохранение правок недоступно")
            }.onFailure {
                toast("Ошибка открытия: ${it.message ?: "неизвестная ошибка"}")
                finish()
            }
        }
    }

    private fun renderPageTabs() {
        pageBar.removeAllViews()
        pageScroll.visibility = if (pages.size > 1) View.VISIBLE else View.GONE
        pages.forEachIndexed { index, page ->
            val label = if (appName == "PowerPoint") "${index + 1}" else page.name.take(16).ifBlank { "${index + 1}" }
            pageBar.addView(pill(label, index == currentPage) { requestPage(index) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
    }

    private fun styleTabs() = renderPageTabs()

    private fun showPage(index: Int) {
        if (index !in pages.indices) return
        currentPage = index
        styleTabs()
        webReady = false
        if (editable) setSaveEnabled(false)
        val listed = File(pages[index].path)
        val file = if (listed.isAbsolute) listed else File(renderDirectory, pages[index].path)
        if (file.isFile) web.loadUrl(Uri.fromFile(file).toString())
        else toast("Не найдена страница ${pages[index].name}")
    }

    private fun goPage(delta: Int) {
        val target = currentPage + delta
        if (target in pages.indices) requestPage(target)
        else if (slideshow && delta > 0) { toast("Показ завершён"); stopSlideshow() }
    }

    // ---------- Команды ----------

    private fun runEditorCommand(command: String) {
        if (!editable || isApplying || !webReady) return
        web.evaluateJavascript(
            "(function(){if(window.odr&&odr.editing){$command;window.__mkDirty=true;window.__mkLast=Date.now();try{MayskOffice.postDirty();}catch(e){}}})();", null)
    }

    private fun requestSave() {
        if (!editable || isApplying || !webReady) return
        requestOperations(-1)
    }

    private fun requestPage(index: Int) {
        if (index !in pages.indices || !webReady) return
        if (!editable) { showPage(index); return }
        if (isApplying) return
        requestOperations(index)
    }

    private fun requestOperations(targetPage: Int) {
        isApplying = true
        setSaveEnabled(false)
        web.evaluateJavascript(
            "(function(){try{var e=window.odr&&odr.editing;if(!e||!e.getOperations){MayskOffice.postEdit('',${targetPage});return;}var x=e.getOperations();MayskOffice.postEdit(typeof x==='string'?x:JSON.stringify(x),${targetPage});}catch(err){MayskOffice.postError(String(err));}})();",
            null,
        )
    }

    /** Автосохранение: раз в 20 секунд, если есть правки и пользователь не печатает. */
    private fun autosaveTick() {
        if (!editable || isApplying || !webReady || !dirty || slideshow || exportOnly) return
        web.evaluateJavascript(
            "(function(){return (Date.now()-(window.__mkLast||0))+'|'+(window.scrollX||0)+'|'+(window.scrollY||document.documentElement.scrollTop||0);})()"
        ) { raw ->
            val p = raw?.trim('"')?.split('|').orEmpty()
            val idle = p.getOrNull(0)?.toLongOrNull() ?: 0L
            if (idle >= 3000 && dirty && !isApplying && webReady && editable) {
                restoreX = p.getOrNull(1)?.toDoubleOrNull()?.toInt() ?: 0
                restoreY = p.getOrNull(2)?.toDoubleOrNull()?.toInt() ?: 0
                silentSave = true
                requestOperations(-1)
            }
        }
    }

    private inner class EditBridge {
        @JavascriptInterface fun postEdit(diff: String, targetPage: Int) { runOnUiThread { applyAndContinue(diff, targetPage) } }
        @JavascriptInterface fun postError(message: String) {
            runOnUiThread {
                isApplying = false
                silentSave = false
                setSaveEnabled(editable && webReady)
                toast("Редактор: $message")
            }
        }
        @JavascriptInterface fun postDirty() { runOnUiThread { if (!dirty) { dirty = true; updateStatus() } } }
        @JavascriptInterface fun postSelection(text: String) {
            runOnUiThread {
                selectedText = text
                if (::selectionBar.isInitialized && !slideshow) {
                    selectionBar.visibility = if (text.isNotBlank() && editable) View.VISIBLE else View.GONE
                }
                if (appName == "Excel") updateStatus()
            }
        }
    }

    private fun applyAndContinue(diff: String, targetPage: Int) {
        lifecycleScope.launch {
            var waitingForWrite = false
            runCatching {
                withContext(Dispatchers.IO) {
                    val doc = document ?: error("Документ закрыт")
                    val operations = if (diff.isNotBlank() && diff != "null") org.json.JSONObject(diff).optJSONArray("ops") else null
                    val changed = operations != null && operations.length() > 0
                    if (changed) doc.edit(diff)
                    if (targetPage >= 0) {
                        if (changed) {
                            service?.close()
                            renderDocumentPages(doc, editable)
                        } else null
                    } else {
                        val skip = silentSave && !changed && !modelDirty
                        if (skip) {
                            SavedDocument(ByteArray(0), null, false)
                        } else {
                            val bytes = doc.saveToMemory()
                            val refreshed = if (changed) runCatching {
                                service?.close()
                                renderDocumentPages(doc, editable)
                            }.getOrNull() else null
                            SavedDocument(bytes, refreshed, changed)
                        }
                    }
                }
            }.onSuccess { result ->
                if (targetPage >= 0) {
                    if (result is RenderedDocument) {
                        modelDirty = true
                        service = result.service
                        pages = result.pages
                        renderDirectory = result.directory
                        documentText = result.extractedText
                        renderPageTabs()
                    }
                    showPage(targetPage.coerceIn(0, pages.lastIndex))
                } else if (result is SavedDocument) {
                    if (silentSave && result.bytes.isEmpty()) {
                        // правок нет — писать нечего
                        silentSave = false
                        dirty = false
                        waitingForWrite = false
                        return@onSuccess
                    }
                    result.refreshed?.let { refreshed ->
                        service = refreshed.service
                        pages = refreshed.pages
                        renderDirectory = refreshed.directory
                        documentText = refreshed.extractedText
                        renderPageTabs()
                        showPage(currentPage.coerceIn(0, pages.lastIndex))
                    }
                    if (result.changed && result.refreshed == null) {
                        editable = false
                        setSaveEnabled(false)
                        toast("Изменения подготовлены. Перезапустите документ, чтобы продолжить редактирование.")
                    }
                    pendingBytes = result.bytes
                    waitingForWrite = true
                    val uri = sourceUri
                    when {
                        exportOnly -> { exportOnly = false; exportCopy.launch(sourceFile?.name ?: "document") }
                        uri != null -> writeBytes(uri, result.bytes)
                        isLibraryFile() -> writeBytes(null, result.bytes)
                        else -> createCopy.launch(sourceFile?.name ?: "document")
                    }
                }
            }.onFailure {
                silentSave = false
                toast("Не удалось применить изменения: ${it.message}")
            }
            if (!waitingForWrite) finishSave()
        }
    }

    private fun isLibraryFile(): Boolean {
        val f = sourceFile ?: return false
        return f.absolutePath.startsWith(History.docsDir(this).absolutePath)
    }

    // ---------- Страницы движка ----------

    private fun renderDocumentPages(doc: Document, allowEditing: Boolean): RenderedDocument {
        val config = HtmlConfig().apply {
            editable = allowEditing
            keyboardNavigation = true
            keyboardShortcuts = true
            viewportMode = app.opendocument.core.HtmlViewportMode.AUTOMATIC
        }
        val newService = Html.translate(doc, config)
        val output = File(cacheDir, "office_render_${System.currentTimeMillis()}").apply { mkdirs() }
        val html = newService.bringOffline(output.absolutePath)
        val renderedPages = html.pages()
        require(renderedPages.isNotEmpty()) { "Движок не создал страницы документа" }
        val extracted = renderedPages.map { page ->
            val listedPage = File(page.path)
            val htmlFile = if (listedPage.isAbsolute) listedPage else File(output, page.path)
            val raw = if (htmlFile.isFile) htmlFile.readText(Charsets.UTF_8) else ""
            android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                .replace(Regex("\\n{3,}"), "\n\n").trim()
        }.filter { it.isNotBlank() }.joinToString("\n\n")
        return RenderedDocument(newService, renderedPages, output, extracted)
    }

    // ---------- Запись и версии ----------

    private fun backupDir(): File = (getExternalFilesDir("office_backups") ?: File(filesDir, "office_backups")).apply { mkdirs() }

    private fun backupsOf(file: File): List<File> =
        backupDir().listFiles { f -> f.isFile && f.name.startsWith(file.name + ".") && f.name.endsWith(".bak") }
            ?.sortedByDescending { backupTime(file, it) }.orEmpty()

    private fun backupTime(original: File, backup: File): Long =
        backup.name.removePrefix(original.name + ".").removeSuffix(".bak").toLongOrNull() ?: backup.lastModified()

    private fun writeBytes(uri: Uri?, bytes: ByteArray) {
        val silent = silentSave
        silentSave = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    sourceFile?.let { original ->
                        if (original.exists()) {
                            val backup = File(backupDir(), "${original.name}.${System.currentTimeMillis()}.bak")
                            original.copyTo(backup, overwrite = true)
                            backupsOf(original).drop(20).forEach { it.delete() }
                        }
                    }
                    if (uri != null) {
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                            ?: error("Провайдер не разрешил запись")
                        runCatching { sourceFile?.writeBytes(bytes) }
                    } else {
                        (sourceFile ?: error("Нет файла для записи")).writeBytes(bytes)
                    }
                    Unit
                }
            }
            result.onSuccess {
                dirty = false
                modelDirty = false
                lastSavedAt = System.currentTimeMillis()
                updateStatus()
                if (!silent) toast("Документ сохранён")
                finishSave()
            }.onFailure { error ->
                if (silent || uri == null) {
                    toast("Не удалось сохранить: ${error.message ?: ""}")
                    finishSave()
                    return@onFailure
                }
                // Провайдер мог обрезать файл до отказа — возвращаем исходное содержимое
                runCatching {
                    val original = sourceFile ?: return@runCatching
                    contentResolver.openOutputStream(uri, "wt")?.use { target -> original.inputStream().use { it.copyTo(target) } }
                }
                TrDialog(this@OfficeEngineActivity)
                    .setTitle("Не удалось перезаписать исходный файл")
                    .setMessage("Android или файловый провайдер не разрешил запись. Сохранить отредактированный документ отдельной копией?\n\n${error.message ?: ""}")
                    .setPositiveButton("Сохранить копию") { _, _ -> createCopy.launch(sourceFile?.name ?: "document") }
                    .setNegativeButton("Отмена") { _, _ -> finishSave() }
                    .setOnCancelListener { finishSave() }
                    .show()
            }
        }
    }

    private fun finishSave() {
        isApplying = false
        setSaveEnabled(editable && webReady)
        if (finishAfterSave) { finishAfterSave = false; if (!dirty) finish() }
    }

    private fun versionsDialog() {
        val file = sourceFile ?: return
        val list = backupsOf(file)
        if (list.isEmpty()) { toast("Версий пока нет: они появляются при каждом сохранении"); return }
        sheet("История версий", list.map { b ->
            Row(formatDate(backupTime(file, b)), formatSize(b.length()), R.drawable.ic_history) { restoreVersion(file, b) }
        })
    }

    private fun restoreVersion(file: File, backup: File) {
        TrDialog(this).setTitle("Вернуть эту версию?")
            .setMessage("Текущее состояние тоже сохранится в истории версий.")
            .setPositiveButton("Вернуть") { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = backup.readBytes()
                            if (file.exists()) file.copyTo(File(backupDir(), "${file.name}.${System.currentTimeMillis()}.bak"), overwrite = true)
                            file.writeBytes(bytes)
                            sourceUri?.let { u -> contentResolver.openOutputStream(u, "wt")?.use { it.write(bytes) } }
                        }
                    }
                    ok.onSuccess {
                        dirty = false
                        startActivity(Intent(this@OfficeEngineActivity, OfficeEngineActivity::class.java)
                            .putExtra(com.maysk.scan.EXTRA_FILE, file.absolutePath)
                            .putExtra("source_uri", sourceUri?.toString()))
                        finish()
                    }.onFailure { toast("Не удалось вернуть версию: ${it.message}") }
                }
            }
            .setNegativeButton("Отмена", null).show()
    }

    // ---------- Меню, диалоги, ИИ ----------

    private class Row(val label: String, val sub: String? = null, val icon: Int? = null, val action: () -> Unit)

    private fun sheet(title: String, rows: List<Row>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { cornerRadius = dpf(22f); setColor(PANEL); setStroke(dp(1), accent) }
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(TXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(4), dp(2), dp(4), dp(8))
        })
        val dialog = TrDialog(this).setView(box).create()
        val scroll = android.widget.ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (r in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), GradientDrawable().apply {
                    cornerRadius = dpf(14f); setColor(CARD)
                }, null)
                setOnClickListener { dialog.dismiss(); r.action() }
            }
            if (r.icon != null) row.addView(tint(r.icon, accent), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(14) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(TextView(this).apply { text = r.label; textSize = 15f; setTextColor(TXT) })
            if (r.sub != null) t.addView(TextView(this).apply { text = r.sub; textSize = 12f; setTextColor(TXT2) })
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showMenu() {
        val file = sourceFile ?: return
        sheet(file.name, listOf(
            Row("Найти в документе", icon = R.drawable.ic_search) { findDialog() },
            Row("История версий", icon = R.drawable.ic_history) { versionsDialog() },
            Row("Статистика текста", icon = R.drawable.ic_text) { statsDialog() },
            Row("Переименовать", icon = R.drawable.ic_edit) { renameDialog() },
            Row("Поделиться", icon = R.drawable.ic_share) {
                if (dirty) toast("Сначала сохраните изменения: отправится сохранённая версия")
                Files.share(this, listOf(file), Files.mimeOf(file.name))
            },
            Row("Сохранить копию…", icon = R.drawable.ic_save) {
                if (!editable) toast("Этот формат открыт только для просмотра")
                else if (!isApplying && webReady) { exportOnly = true; requestOperations(-1) }
            },
            Row("Открыть в другом приложении", icon = R.drawable.ic_open) { Files.openWith(this, file) },
        ))
    }

    private fun renameDialog() {
        val file = sourceFile ?: return
        val entry = History.findByPath(this, file.absolutePath)
        if (entry == null) { toast("Переименовать можно документы из раздела «Документы»"); return }
        val input = EditText(this).apply {
            setText(Files.baseName(file.name))
            setTextColor(TXT)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        TrDialog(this).setTitle("Название файла").setView(box)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotBlank()) {
                    if (Docs.rename(this, entry, n)) {
                        val renamed = File(file.parentFile, Files.sanitize(n).removeSuffix("." + file.extension) + "." + file.extension)
                        sourceFile = renamed
                        titleView.text = renamed.name
                    } else toast("Не удалось переименовать: такое имя уже есть")
                }
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun findDialog() {
        val input = EditText(this).apply { hint = "Что найти"; setTextColor(TXT); setHintTextColor(TXT2); isSingleLine = true }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        TrDialog(this).setTitle("Найти в документе").setView(box)
            .setPositiveButton("Найти") { _, _ ->
                val q = input.text.toString()
                if (q.isNotBlank()) {
                    web.setFindListener { _, count, done -> if (done) toast(if (count == 0) "Ничего не найдено" else "Найдено: $count") }
                    web.findAllAsync(q)
                } else web.clearMatches()
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun statsDialog() {
        val t = documentText
        val words = if (t.isBlank()) 0 else t.trim().split(Regex("\\s+")).size
        val chars = t.length
        val noSpaces = t.count { !it.isWhitespace() }
        val paragraphs = if (t.isBlank()) 0 else t.split(Regex("\\n+")).count { it.isNotBlank() }
        val unit = when (appName) { "Excel" -> "Листов"; "PowerPoint" -> "Слайдов"; else -> "Страниц движка" }
        TrDialog(this).setTitle("Статистика текста")
            .setMessage("Слов: $words\nСимволов: $chars\nСимволов без пробелов: $noSpaces\nАбзацев: $paragraphs\n$unit: ${pages.size}")
            .setPositiveButton("OK", null).show()
    }

    private fun aiSheet() {
        sheet("AI-помощник", listOf(
            Row("Перефразировать текст", icon = R.drawable.ic_replace) { askAi("paraphrase") },
            Row("Сократить текст", icon = R.drawable.ic_minus) { askAi("shorten") },
            Row("Расширить текст", icon = R.drawable.ic_add) { askAi("expand") },
            Row("Перевести на другой язык", icon = R.drawable.ic_translate) { askAi("translate") },
            Row("Проверить орфографию", icon = R.drawable.ic_check) { askAi("check") },
            Row("Открыть чат помощника", icon = R.drawable.ic_ai) { askAi("chat") },
        ))
    }

    private fun askAi(action: String) {
        val selected = selectedText.trim()
        val target = if (selected.isNotEmpty()) selected else documentText.take(3000)
        val instruction = when (action) {
            "paraphrase" -> "Перефразируй следующий текст, сохранив смысл"
            "shorten" -> "Сократи следующий текст, оставив главное"
            "expand" -> "Расширь следующий текст, добавив детали"
            "translate" -> "Переведи следующий текст на английский язык"
            "check" -> "Проверь орфографию и пунктуацию следующего текста и покажи исправленный вариант"
            else -> ""
        }
        val prompt = if (instruction.isEmpty()) "" else "$instruction:\n\n$target"
        startActivity(Intent(this, LocalAiActivity::class.java)
            .putExtra("document_text", documentText.take(4500))
            .putExtra("prompt", prompt))
    }

    // ---------- Вид ----------

    private fun toggleLandscape() {
        landscape = !landscape
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun hideSystemBars(hide: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (hide) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun startSlideshow() {
        if (pages.isEmpty() || !webReady) return
        slideshow = true
        topBar.visibility = View.GONE
        ribbonBox.visibility = View.GONE
        bottomBox.visibility = View.GONE
        aiFab.visibility = View.GONE
        selectionBar.visibility = View.GONE
        showOverlay.visibility = View.VISIBLE
        hideSystemBars(true)
        toast("Касание справа — дальше, слева — назад, «Назад» — выход")
    }

    private fun stopSlideshow() {
        slideshow = false
        topBar.visibility = View.VISIBLE
        ribbonBox.visibility = View.VISIBLE
        bottomBox.visibility = View.VISIBLE
        aiFab.visibility = View.VISIBLE
        showOverlay.visibility = View.GONE
        hideSystemBars(false)
    }

    private fun handleBack() {
        when {
            slideshow -> stopSlideshow()
            dirty && editable -> TrDialog(this).setTitle("Сохранить изменения?")
                .setMessage("В документе есть несохранённые правки.")
                .setPositiveButton("Сохранить") { _, _ ->
                    if (!isApplying && webReady) { finishAfterSave = true; requestOperations(-1) } else finish()
                }
                .setNeutralButton("Не сохранять") { _, _ -> dirty = false; finish() }
                .setNegativeButton("Отмена", null).show()
            else -> finish()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { web.removeJavascriptInterface("MayskOffice"); web.destroy() }
        runCatching { service?.close() }
        runCatching { document?.close() }
        runCatching { documentFile?.close() }
        runCatching { decoded?.close() }
        runCatching { workCopy?.parentFile?.deleteRecursively() }
        super.onDestroy()
    }

    private companion object {
        val AI_ACTIONS = listOf(
            "Перефразировать" to "paraphrase",
            "Сократить" to "shorten",
            "Расширить" to "expand",
            "Перевести" to "translate",
            "Проверить" to "check",
        )

        /** Отслеживает правки и выделение внутри страницы движка. */
        const val TRACKER_JS = "(function(){if(window.__mkInit)return;window.__mkInit=true;window.__mkDirty=false;window.__mkLast=0;" +
            "var mark=function(){window.__mkLast=Date.now();if(!window.__mkDirty){window.__mkDirty=true;try{MayskOffice.postDirty();}catch(e){}}};" +
            "['input','paste','cut'].forEach(function(n){document.addEventListener(n,mark,true);});" +
            "document.addEventListener('keydown',function(ev){var k=ev.key||'';if(k.length===1||k==='Backspace'||k==='Delete'||k==='Enter'){if(!(ev.ctrlKey||ev.metaKey)||k==='x'||k==='v')mark();}},true);" +
            "var sel='';document.addEventListener('selectionchange',function(){var t='';try{t=String(window.getSelection?window.getSelection().toString():'');}catch(e){}" +
            "if(t!==sel){sel=t;try{MayskOffice.postSelection(t.length>4000?t.substring(0,4000):t);}catch(e){}}});})();"
    }
}
