package com.maysk.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Unified offline workspace for text files and OOXML office documents. */
class OfficeWorkspaceActivity : BaseActivity() {
    private var source: File? = null
    private var extension = ""
    private lateinit var body: LinearLayout
    private var sourceUri: Uri? = null
    private var officeModel: OoxmlPreservingEditor.Model? = null
    private val edits = linkedMapOf<OoxmlPreservingEditor.Field, String>()
    private val pageInputs = mutableListOf<Pair<OoxmlPreservingEditor.Field, EditText>>()
    private var page = 0
    private val pageSize = 35
    private var textEditor: EditText? = null
    private var textEditable = false
    private var textNote = ""

    // Request both read and write grants so a provider can permit saving back to the same document.
    private val pickOfficeFile = registerForActivityResult(object : ActivityResultContract<Array<String>, Uri?>() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, input)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }) { uri ->
        if (uri != null) openPickedUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = screen("Документы и помощник")
        addTopAction(R.drawable.ic_open) { chooseFile() }
        sourceUri = intent.getStringExtra("source_uri")?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val path = intent.getStringExtra(EXTRA_FILE)
        if (!path.isNullOrBlank()) dispatch(File(path)) else {
            showStart()
            if (intent.getBooleanExtra("auto_pick", false)) chooseFile()
        }
    }

    private fun showStart() {
        body.removeAllViews()
        body.addView(text("Офис документов MayskScan", 20f, bold = true))
        body.addView(text("Открывайте локальные документы, редактируйте их без интернета и сохраняйте в исходном формате.", 14f, R.color.text2))
        body.addView(primaryButton("Открыть документ", R.drawable.ic_open) { chooseFile() })
        body.addView(actionButton("Локальный ИИ-помощник", R.drawable.ic_ai) {
            startActivity(Intent(this, LocalAiActivity::class.java))
        })
        body.addView(actionButton("Лицензии офисного движка и ИИ", R.drawable.ic_doc) {
            val notices = assets.open("licenses/THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle("Сторонние компоненты").setMessage(notices)
                .setPositiveButton("Закрыть", null).show()
        })
        body.addView(sectionTitle("ПОДДЕРЖИВАЕМЫЕ ФОРМАТЫ"))
        body.addView(text("DOCX · XLSX · PPTX · ODT · ODS · ODP · DOC · XLS · PPT · PDF · TXT · CSV · MD · RTF · HTML · JPG · PNG", 14f))
        body.addView(text("DOCX, XLSX, PPTX и OpenDocument открываются в визуальном редакторе OpenDocument.core. Сохранение выполняется в исходный формат; доступные операции зависят от типа файла.", 12.5f, R.color.text2))
        body.addView(text("Старые бинарные DOC/XLS/PPT поддерживаются для просмотра, но не редактирования. Для записи поверх оригинала Android должен разрешить доступ; иначе можно сохранить копию.", 12.5f, R.color.text2))
        body.addView(sectionTitle("ОФЛАЙН-ПОМОЩНИК"))
        body.addView(text("Доступны локальные инструменты анализа и отдельный чат с нейросетью LiteRT-LM. Для генеративного ИИ нужно один раз скачать и импортировать файл модели .litertlm; после этого ответы формируются на телефоне без отправки документа в облако.", 12.5f, R.color.text2))
    }

    private fun chooseFile() {
        pickOfficeFile.launch(arrayOf("*/*"))
    }

    private fun openPickedUri(uri: Uri) {
        lifecycleScope.launch {
            showProgress("Открываю документ…")
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            val result = withContext(Dispatchers.IO) { runCatching { Files.copyToCache(this@OfficeWorkspaceActivity, uri) } }
            hideProgress()
            result.onSuccess { file ->
                sourceUri = uri
                if (file.extension.lowercase(Locale.ROOT) in Docs.OFFICE_EXT) {
                    // Документ попадает в раздел «Документы»; правки записываются и в исходный файл
                    val entry = runCatching { Docs.registerExternal(this@OfficeWorkspaceActivity, file, uri) }.getOrNull()
                    dispatch(entry?.file ?: file)
                } else dispatch(file)
            }
                .onFailure { toast("Не удалось открыть документ: ${it.message}") }
        }
    }

    private fun dispatch(file: File) {
        when (file.extension.lowercase(Locale.ROOT)) {
            "pdf" -> { startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, file.absolutePath)); finish() }
            "jpg", "jpeg", "png", "webp", "bmp" -> { startActivity(Intent(this, OcrActivity::class.java).putExtra("images", arrayOf(file.absolutePath))); finish() }
            // DOCX/XLSX/PPTX and ODF are handled by the native OpenDocument.core editor.
            "docx", "xlsx", "pptx", "odt", "ods", "odp", "doc", "xls", "ppt" -> {
                startActivity(Intent(this, OfficeEngineActivity::class.java)
                    .putExtra(EXTRA_FILE, file.absolutePath)
                    .putExtra("source_uri", sourceUri?.toString()))
                finish()
            }
            else -> load(file)
        }
    }

    private fun load(file: File) {
        if (!file.exists()) { toast("Файл не найден"); showStart(); return }
        source = file
        extension = file.extension.lowercase(Locale.ROOT)
        edits.clear()
        page = 0
        officeModel = null
        textEditor = null
        lifecycleScope.launch {
            showProgress("Читаю документ…")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (extension) {
                        "docx", "xlsx", "pptx" -> OoxmlPreservingEditor.read(file, extension)
                        "txt", "md", "csv", "html", "htm", "rtf" -> null
                        else -> throw IllegalArgumentException("Встроенный редактор пока не поддерживает .$extension")
                    }
                }
            }
            hideProgress()
            result.onSuccess { model ->
                if (model != null) {
                    officeModel = model
                    renderOffice(file.name)
                } else {
                    textEditable = true
                    textNote = "Текстовый документ редактируется локально."
                    renderText(file.name, file.readText(Charsets.UTF_8))
                }
            }.onFailure {
                toast("Формат не поддерживается встроенным редактором: ${it.message ?: "ошибка чтения"}")
                showStart()
                Files.openWith(this@OfficeWorkspaceActivity, file)
            }
        }
    }

    private fun renderOffice(name: String) {
        val model = officeModel ?: return
        body.removeAllViews()
        body.addView(text(name, 18f, bold = true))
        body.addView(text(model.note, 12.5f, R.color.text2))
        if (model.fields.isEmpty()) {
            body.addView(text("В документе не найдено редактируемых текстовых фрагментов.", 14f, R.color.text2))
        } else {
            renderOfficePageContent()
        }
        body.addView(sectionTitle("ОФЛАЙН-ПОМОЩНИК"))
        body.addView(text("Анализ выполняется на телефоне. Файл никуда не отправляется.", 12f, R.color.text2))
        body.addView(actionButton("Краткое содержание", R.drawable.ic_ai) { showAssistant("Краткое содержание", summarize(currentPreview())) })
        body.addView(actionButton("Выделить ключевые пункты", R.drawable.ic_text) { showAssistant("Ключевые пункты", keyPoints(currentPreview())) })
        body.addView(actionButton("Определить тип документа", R.drawable.ic_doc) { showAssistant("Тип документа", classify(currentPreview())) })
        body.addView(primaryButton("Сохранить изменения в исходный файл", R.drawable.ic_save) { saveOffice(false) })
        body.addView(actionButton("Сохранить как отдельную копию", R.drawable.ic_save) { saveOffice(true) })
        body.addView(actionButton("Открыть в другом приложении", R.drawable.ic_open) { source?.let { Files.openWith(this, it) } })
    }

    private fun renderOfficePageContent() {
        val model = officeModel ?: return
        val totalPages = (model.fields.size + pageSize - 1) / pageSize
        body.addView(text("Фрагменты ${page * pageSize + 1}–${minOf((page + 1) * pageSize, model.fields.size)} из ${model.fields.size}", 13f, R.color.text2))
        pageInputs.clear()
        val from = page * pageSize
        val to = minOf(from + pageSize, model.fields.size)
        for (index in from until to) {
            val field = model.fields[index]
            body.addView(text(field.label, 12f, R.color.text2, bold = true))
            val input = EditText(this).apply {
                setText(edits[field] ?: field.value)
                textSize = 14f
                setTextColor(col(R.color.text))
                setHintTextColor(col(R.color.text2))
                gravity = Gravity.TOP or Gravity.START
                minLines = if (field.value.length > 100) 2 else 1
                maxLines = 5
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isSingleLine = false
                background = rounded(col(R.color.bg2), 12)
            }
            pageInputs += field to input
            body.addView(input, lp())
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(actionButton("Назад", R.drawable.ic_back) { if (page > 0) { saveVisiblePage(); page--; renderOffice(source?.name ?: "Документ") } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        nav.addView(actionButton("Далее", R.drawable.ic_chevron) { if (page < totalPages - 1) { saveVisiblePage(); page++; renderOffice(source?.name ?: "Документ") } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(nav)
    }

    private fun saveVisiblePage() {
        pageInputs.forEach { (field, input) -> edits[field] = input.text.toString() }
    }

    private fun currentPreview(): String {
        saveVisiblePage()
        val model = officeModel ?: return textEditor?.text?.toString().orEmpty()
        return model.fields.joinToString("\n") { "${it.label}: ${edits[it] ?: it.value}" }
    }

    private fun renderText(name: String, value: String) {
        body.removeAllViews()
        body.addView(text(name, 18f, bold = true))
        body.addView(text(textNote, 12.5f, R.color.text2))
        val input = EditText(this).apply {
            setText(value)
            textSize = 15f
            setTextColor(col(R.color.text))
            setHintTextColor(col(R.color.text2))
            gravity = Gravity.TOP or Gravity.START
            minLines = 12
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isSingleLine = false
            background = rounded(col(R.color.bg2), 14)
        }
        textEditor = input
        body.addView(input, lp().apply { height = dp(360) })
        body.addView(sectionTitle("ОФЛАЙН-ПОМОЩНИК"))
        body.addView(actionButton("Краткое содержание", R.drawable.ic_ai) { showAssistant("Краткое содержание", summarize(input.text.toString())) })
        body.addView(actionButton("Ключевые пункты", R.drawable.ic_text) { showAssistant("Ключевые пункты", keyPoints(input.text.toString())) })
        body.addView(actionButton("Определить тип документа", R.drawable.ic_doc) { showAssistant("Тип документа", classify(input.text.toString())) })
        body.addView(primaryButton("Сохранить изменения", R.drawable.ic_save) { saveText(false) })
        body.addView(actionButton("Сохранить копию", R.drawable.ic_save) { saveText(true) })
    }

    private fun actionButton(label: String, icon: Int, click: () -> Unit): TextView = text(label, 14f, R.color.accent, bold = true).apply {
        setPadding(dp(12), dp(13), dp(12), dp(13))
        setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawablePadding = dp(10)
        background = clickable(cardBg(14), 14)
        setOnClickListener { click() }
    }

    private fun showAssistant(title: String, result: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(result.ifBlank { "Недостаточно текста для анализа." })
            .setPositiveButton("Понятно", null).show()
    }

    private fun summarize(raw: String): String {
        val sentences = raw.replace(Regex("\\s+"), " ").split(Regex("(?<=[.!?])\\s+")).filter { it.length > 25 }
        if (sentences.isEmpty()) return raw.take(900).ifBlank { "В документе пока нет текста." }
        val selected = if (sentences.size <= 3) sentences else listOf(sentences.first(), sentences[sentences.size / 2], sentences.last())
        return selected.joinToString("\n\n").take(1600)
    }

    private fun keyPoints(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.length >= 18 }
        val candidates = lines.filter { it.length < 180 || it.matches(Regex("^[-•*0-9. )]+.*")) }
        return candidates.take(12).joinToString("\n") { "• $it" }.ifBlank { "Не удалось выделить отдельные пункты." }
    }

    private fun classify(raw: String): String {
        val t = raw.lowercase(Locale.ROOT)
        val types = listOf(
            "Счёт или чек" to listOf("итого", "сумма", "оплат", "счёт", "invoice", "total"),
            "Договор" to listOf("договор", "стороны", "обязуется", "contract", "agreement"),
            "Заявление или обращение" to listOf("заявление", "прошу", "обращаюсь"),
            "Резюме" to listOf("опыт работы", "образование", "навыки", "резюме", "curriculum"),
            "Отчёт" to listOf("отчёт", "результаты", "заключение", "report")
        )
        val match = types.map { it.first to it.second.count { word -> t.contains(word) } }.maxByOrNull { it.second }
        return if (match == null || match.second == 0) "Тип документа не определён уверенно." else
            "Вероятный тип: ${match.first}.\n\nЭто локальная эвристика по ключевым словам, результат может быть неточным."
    }

    private fun saveOffice(asCopy: Boolean) {
        saveVisiblePage()
        val original = source ?: return
        val model = officeModel ?: return
        lifecycleScope.launch {
            showProgress("Сохраняю исходный Office-пакет…")
            val out = Files.tempFile(this@OfficeWorkspaceActivity, extension)
            val result = withContext(Dispatchers.IO) { runCatching { OoxmlPreservingEditor.save(original, out, edits) } }
            hideProgress()
            result.onFailure { toast("Не удалось подготовить документ: ${it.message}") }
            result.onSuccess {
                if (asCopy || sourceUri == null) {
                    saveAs(out, Files.mimeOf(original.name), original.name)
                } else {
                    writeBackOrCopy(out, original)
                }
            }
        }
    }

    private fun saveText(asCopy: Boolean) {
        val original = source ?: return
        val input = textEditor ?: return
        val out = Files.tempFile(this, extension)
        out.writeText(input.text.toString(), Charsets.UTF_8)
        if (asCopy || sourceUri == null) saveAs(out, Files.mimeOf(original.name), original.name) else writeBackOrCopy(out, original)
    }

    private fun writeBackOrCopy(out: File, original: File) {
        val uri = sourceUri
        if (uri == null) {
            saveAs(out, Files.mimeOf(original.name), original.name)
            return
        }
        lifecycleScope.launch {
            showProgress("Записываю изменения в исходный файл…")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Keep a recoverable local snapshot before touching the user's original file.
                    val backup = Files.newDoc(this@OfficeWorkspaceActivity,
                        Files.baseName(original.name) + "_backup_" + Files.stamp(), extension)
                    original.inputStream().use { input -> backup.outputStream().use { input.copyTo(it) } }
                    val stream = contentResolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("Провайдер не разрешил запись")
                    try {
                        stream.use { target -> out.inputStream().use { it.copyTo(target) } }
                    } catch (writeError: Throwable) {
                        // If the provider truncated the target before failing, try to restore the cached original.
                        runCatching {
                            contentResolver.openOutputStream(uri, "wt")?.use { restore ->
                                original.inputStream().use { it.copyTo(restore) }
                            }
                        }
                        throw writeError
                    }
                    backup
                }
            }
            hideProgress()
            result.onSuccess { backup ->
                source = out
                toast("Изменения сохранены. Резервная копия: ${backup.name}")
            }.onFailure {
                AlertDialog.Builder(this@OfficeWorkspaceActivity)
                    .setTitle("Не удалось перезаписать исходный файл")
                    .setMessage("Возможно, источник открыт только для чтения. Сохранить отредактированную копию?")
                    .setPositiveButton("Сохранить копию") { _, _ -> saveAs(out, Files.mimeOf(original.name), original.name) }
                    .setNegativeButton("Отмена", null).show()
            }
        }
    }
}
