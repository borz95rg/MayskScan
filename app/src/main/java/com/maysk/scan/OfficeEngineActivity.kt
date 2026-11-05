package com.maysk.scan

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

/** Native OpenDocument.core workspace. The engine renders and edits the original document model. */
class OfficeEngineActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var titleView: TextView
    private lateinit var pageBar: LinearLayout
    private lateinit var pageScroll: HorizontalScrollView
    private val ribbonButtons = ArrayList<TextView>()
    private var accent = 0xFF2B579A.toInt()   // цвет приложения: Word / Excel / PowerPoint
    private var appName = "Word"
    private lateinit var saveButton: Button
    private var decoded: DecodedFile? = null
    private var documentFile: DocumentFile? = null
    private var document: Document? = null
    private var service: HtmlService? = null
    private var pages: List<app.opendocument.core.HtmlPage> = emptyList()
    private var renderDirectory: File? = null
    private var currentPage = 0
    private var sourceFile: File? = null
    private var sourceUri: Uri? = null
    private var documentText: String = ""
    private var editable = false
    private var isApplying = false
    private var webReady = false

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
        when (sourceFile?.extension?.lowercase()) {
            "xlsx", "xls", "ods", "csv" -> { accent = 0xFF217346.toInt(); appName = "Excel" }
            "pptx", "ppt", "odp" -> { accent = 0xFFB7472A.toInt(); appName = "PowerPoint" }
            else -> { accent = 0xFF2B579A.toInt(); appName = "Word" }
        }
        buildUi()
        val file = sourceFile
        if (file == null || !file.exists()) { toast("Файл не найден"); finish(); return }
        titleView.text = file.name
        openDocument(file)
    }

    private fun ripple(base: Int, rippleColor: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), ColorDrawable(base), ColorDrawable(Color.WHITE))

    /** Кнопка ленты: плоская, как в Office. Работает только то, что поддерживает движок. */
    private fun ribbonButton(label: String, style: Int = Typeface.NORMAL, underline: Boolean = false, color: Int = 0xFF333333.toInt(), onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(color)
            setTypeface(Typeface.DEFAULT, style)
            if (underline) paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            background = ripple(0xFFF3F3F3.toInt(), 0x33000000)
            setOnClickListener { onClick() }
        }

    private fun buildUi() {
        window.statusBarColor = accent
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFE9E9E9.toInt()) }

        // Заголовок в цвете приложения: название, имя файла, «Сохранить»
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(accent)
            setPadding(dp(14), dp(6), dp(6), dp(6))
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(TextView(this).apply { text = appName; textSize = 11f; setTextColor(0xCCFFFFFF.toInt()) })
        titleView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        names.addView(titleView)
        top.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        saveButton = Button(this).apply {
            text = "Сохранить"
            isAllCaps = false
            textSize = 14f
            isEnabled = false
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(0x88FFFFFF.toInt(), Color.WHITE)))
            background = ripple(accent, 0x44FFFFFF)
            setOnClickListener { requestSave() }
        }
        top.addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        root.addView(top)

        // Лента: форматирование и отмена действий + помощник
        val ribbon = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFF3F3F3.toInt())
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        fun cmd(label: String, style: Int, underline: Boolean, command: String) {
            val b = ribbonButton(label, style, underline) { runEditorCommand(command) }
            ribbonButtons.add(b)
            ribbon.addView(b, LinearLayout.LayoutParams(dp(44), dp(40)))
        }
        fun separator() {
            ribbon.addView(View(this).apply { setBackgroundColor(0xFFCFCFCF.toInt()) },
                LinearLayout.LayoutParams(dp(1), dp(22)).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        cmd("B", Typeface.BOLD, false, "odr.editing.toggle('bold')")
        cmd("I", Typeface.ITALIC, false, "odr.editing.toggle('italic')")
        cmd("U", Typeface.NORMAL, true, "odr.editing.toggle('underline')")
        separator()
        cmd("↶", Typeface.NORMAL, false, "odr.editing.undo()")
        cmd("↷", Typeface.NORMAL, false, "odr.editing.redo()")
        ribbon.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        ribbon.addView(
            ribbonButton("ИИ", Typeface.BOLD, color = accent) {
                startActivity(Intent(this, LocalAiActivity::class.java).putExtra("document_text", documentText))
            }.apply { textSize = 15f },
            LinearLayout.LayoutParams(dp(52), dp(40)))
        root.addView(ribbon)
        root.addView(View(this).apply { setBackgroundColor(0xFFD0D0D0.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))

        web = WebView(this).apply {
            setBackgroundColor(0xFFE9E9E9.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (editable) view?.evaluateJavascript(
                        "(function(){if(window.odr&&odr.editing&&odr.editing.enable){odr.editing.enable();}})();", null)
                    webReady = true
                    if (!isApplying) saveButton.isEnabled = editable
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
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        // Вкладки листов / слайдов снизу, как в Excel; для документа из одной страницы скрыты
        pageScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFFF3F3F3.toInt())
            visibility = View.GONE
        }
        pageBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pageScroll.addView(pageBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(this).apply { setBackgroundColor(0xFFD0D0D0.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        root.addView(pageScroll, LinearLayout.LayoutParams(-1, dp(40)))
        setContentView(root)
    }

    private fun openDocument(file: File) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    decoded = Odr.open(file.absolutePath)
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
                saveButton.isEnabled = editable
                saveButton.text = if (editable) "Сохранить" else "Только просмотр"
                ribbonButtons.forEach { it.alpha = if (editable) 1f else 0.4f }
                renderPageTabs()
                showPage(0)
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
            val tab = TextView(this).apply {
                text = page.name.take(16).ifBlank { "${index + 1}" }
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { requestPage(index) }
            }
            pageBar.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        styleTabs()
    }

    /** Активная вкладка — белая, цвета приложения; остальные серые. */
    private fun styleTabs() {
        for (i in 0 until pageBar.childCount) {
            val tab = pageBar.getChildAt(i) as? TextView ?: continue
            val selected = i == currentPage
            tab.setBackgroundColor(if (selected) Color.WHITE else Color.TRANSPARENT)
            tab.setTextColor(if (selected) accent else 0xFF555555.toInt())
            tab.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun showPage(index: Int) {
        if (index !in pages.indices) return
        currentPage = index
        styleTabs()
        webReady = false
        if (editable) saveButton.isEnabled = false
        val listed = File(pages[index].path)
        val file = if (listed.isAbsolute) listed else File(renderDirectory, pages[index].path)
        if (file.isFile) web.loadUrl(Uri.fromFile(file).toString())
        else toast("Не найдена страница ${pages[index].name}")
    }

    private fun runEditorCommand(command: String) {
        if (!editable || isApplying || !webReady) return
        web.evaluateJavascript("(function(){if(window.odr&&odr.editing){$command;}})();", null)
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
        saveButton.isEnabled = false
        web.evaluateJavascript(
            "(function(){try{var e=window.odr&&odr.editing;if(!e||!e.getOperations){MayskOffice.postEdit('',${targetPage});return;}var x=e.getOperations();MayskOffice.postEdit(typeof x==='string'?x:JSON.stringify(x),${targetPage});}catch(err){MayskOffice.postError(String(err));}})();",
            null,
        )
    }

    private inner class EditBridge {
        @JavascriptInterface fun postEdit(diff: String, targetPage: Int) { runOnUiThread { applyAndContinue(diff, targetPage) } }
        @JavascriptInterface fun postError(message: String) {
            runOnUiThread {
                isApplying = false
                saveButton.isEnabled = editable && webReady
                toast("Редактор: $message")
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
                        val bytes = doc.saveToMemory()
                        val refreshed = if (changed) runCatching {
                            service?.close()
                            renderDocumentPages(doc, editable)
                        }.getOrNull() else null
                        SavedDocument(bytes, refreshed, changed)
                    }
                }
            }.onSuccess { result ->
                if (targetPage >= 0) {
                    if (result is RenderedDocument) {
                        service = result.service
                        pages = result.pages
                        renderDirectory = result.directory
                        documentText = result.extractedText
                        renderPageTabs()
                    }
                    showPage(targetPage.coerceIn(0, pages.lastIndex))
                } else if (result is SavedDocument) {
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
                        saveButton.isEnabled = false
                        saveButton.text = "Закройте и откройте заново"
                        toast("Изменения подготовлены. Перезапустите документ, чтобы продолжить редактирование.")
                    }
                    pendingBytes = result.bytes
                    waitingForWrite = true
                    val uri = sourceUri
                    if (uri != null) writeBytes(uri, result.bytes)
                    else createCopy.launch(sourceFile?.name ?: "document")
                }
            }.onFailure { toast("Не удалось применить изменения: ${it.message}") }
            if (!waitingForWrite) finishSave()
        }
    }

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
        }.filter { it.isNotBlank() }.joinToString("\n\n").take(4500)
        return RenderedDocument(newService, renderedPages, output, extracted)
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    sourceFile?.let { original ->
                        val backupDir = getExternalFilesDir("office_backups") ?: File(filesDir, "office_backups")
                        backupDir.mkdirs()
                        val backup = File(backupDir, "${original.name}.${System.currentTimeMillis()}.bak")
                        original.copyTo(backup, overwrite = true)
                    }
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("Провайдер не разрешил запись")
                    runCatching { sourceFile?.writeBytes(bytes) }
                }
            }
            result.onSuccess {
                toast("Документ сохранён")
                finishSave()
            }.onFailure { error ->
                // A SAF provider may truncate a target before rejecting a write; restore the cached original.
                runCatching {
                    val original = sourceFile ?: return@runCatching
                    contentResolver.openOutputStream(uri, "wt")?.use { target -> original.inputStream().use { it.copyTo(target) } }
                }
                AlertDialog.Builder(this@OfficeEngineActivity)
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
        if (::saveButton.isInitialized) saveButton.isEnabled = editable && webReady
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        runCatching { web.removeJavascriptInterface("MayskOffice"); web.destroy() }
        runCatching { service?.close() }
        runCatching { document?.close() }
        runCatching { documentFile?.close() }
        runCatching { decoded?.close() }
        super.onDestroy()
    }

}
