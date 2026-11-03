package com.maysk.scan

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Единая точка входа для офисных файлов и локального помощника. */
class OfficeWorkspaceActivity : BaseActivity() {
    private var source: File? = null
    private var extension = ""
    private lateinit var body: LinearLayout
    private lateinit var editor: EditText
    private var editable = false
    private var sourceUri: android.net.Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = screen("Документы и помощник")
        body = content
        addTopAction(R.drawable.ic_open) { chooseFile() }
        sourceUri = intent.getStringExtra("source_uri")?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
        val path = intent.getStringExtra(EXTRA_FILE)
        if (!path.isNullOrBlank()) dispatch(File(path)) else showStart()
    }

    private fun showStart() {
        body.removeAllViews()
        body.addView(text("Все документы в одном месте", 20f, bold = true))
        body.addView(text("Открывайте файлы с телефона или из других приложений. Просмотр и базовая обработка работают офлайн.", 14f, R.color.text2))
        body.addView(primaryButton("Открыть документ", R.drawable.ic_open) { chooseFile() })
        body.addView(sectionTitle("ПОДДЕРЖИВАЕМЫЕ ФОРМАТЫ"))
        body.addView(text("PDF · DOCX · XLSX · PPTX · TXT · CSV · MD · RTF · HTML · JPG · PNG", 14f))
        body.addView(text("Для полноценного редактирования сложных DOCX/XLSX/PPTX с сохранением всех элементов форматирования потребуется отдельный офисный движок. Сейчас редактор текста сохраняет DOCX как новый простой документ; таблицы Excel и презентации открываются в режиме просмотра.", 12.5f, R.color.text2))
    }

    private fun chooseFile() {
        pick(arrayOf("*/*")) { uris ->
            lifecycleScope.launch {
                showProgress("Открываю документ…")
                val uri = uris.first()
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                val f = withContext(Dispatchers.IO) { Files.copyToCache(this@OfficeWorkspaceActivity, uri) }
                hideProgress()
                sourceUri = uri
                dispatch(f)
            }
        }
    }

    private fun dispatch(file: File) {
        when (file.extension.lowercase(Locale.ROOT)) {
            "pdf" -> { startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, file.absolutePath)); finish() }
            "jpg", "jpeg", "png", "webp", "bmp" -> { startActivity(Intent(this, OcrActivity::class.java).putExtra("images", arrayOf(file.absolutePath))); finish() }
            else -> load(file)
        }
    }

    private fun load(file: File) {
        if (!file.exists()) { toast("Файл не найден"); showStart(); return }
        source = file
        extension = file.extension.lowercase(Locale.ROOT)
        lifecycleScope.launch {
            showProgress("Читаю документ…")
            val result = withContext(Dispatchers.IO) { runCatching { readDocument(file) } }
            hideProgress()
            result.onSuccess { (text, canEdit, note) ->
                editable = canEdit
                renderDocument(file.name, text, note)
            }.onFailure {
                toast("Встроенный просмотрщик не поддерживает этот формат. Ищу другое приложение…")
                showStart()
                Files.openWith(this@OfficeWorkspaceActivity, file)
            }
        }
    }

    private fun readDocument(file: File): Triple<String, Boolean, String> = when (extension) {
        "docx" -> Triple(OfficeReader.docxText(file), true,
            "DOCX открыт офлайн. При сохранении будет создан новый простой DOCX; сложное форматирование, изображения и встроенные объекты исходника не переносятся.")
        "xlsx" -> {
            val sheets = OfficeReader.xlsx(file)
            val text = sheets.joinToString("\n\n") { sheet ->
                "ЛИСТ: ${sheet.name}\n" + sheet.rows.joinToString("\n") { row -> row.joinToString("\t") }
            }
            Triple(text, true, "Excel можно отредактировать как таблицы и сохранить новым XLSX. В этой первой версии формулы, стили, диаграммы и объединённые ячейки не переносятся — исходный файл останется нетронутым.")
        }
        "txt", "md", "csv", "html", "htm", "rtf" -> Triple(file.readText(Charsets.UTF_8), true,
            "Текстовый документ можно изменить и сохранить отдельной копией в исходном формате.")
        "pptx" -> Triple(readPptxText(file), true,
            "Текст слайдов можно изменить и сохранить как новый PPTX. В этой версии сохраняются текстовые блоки; изображения, анимация, тема и исходная вёрстка не переносятся.")
        else -> throw IllegalArgumentException("Формат .$extension пока не поддерживается встроенным просмотрщиком")
    }

    private fun readPptxText(file: File): String {
        val out = ArrayList<String>()
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { it.name.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) }
                .sortedBy { it.name.substringAfter("slide").substringBefore(".").toIntOrNull() ?: 0 }
            for (entry in entries) {
                val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder().parse(zip.getInputStream(entry))
                val nodes = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main", "t")
                val parts = (0 until nodes.length).map { nodes.item(it).textContent }.filter { it.isNotBlank() }
                out.add("СЛАЙД ${entry.name.substringAfter("slide").substringBefore(".")}\n" + parts.joinToString("\n"))
            }
        }
        return out.joinToString("\n\n").ifBlank { "В презентации не найден текст." }
    }

    private fun renderDocument(name: String, value: String, note: String) {
        body.removeAllViews()
        body.addView(text(name, 18f, bold = true))
        body.addView(text(note, 12.5f, R.color.text2))
        editor = EditText(this).apply {
            setText(value)
            textSize = 15f
            setTextColor(col(R.color.text))
            setHintTextColor(col(R.color.text2))
            gravity = Gravity.TOP or Gravity.START
            minLines = 12
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isSingleLine = false
            isEnabled = editable
            background = rounded(col(R.color.bg2), 14)
        }
        body.addView(editor, lp().apply { height = dp(360) })
        body.addView(sectionTitle("ОФЛАЙН-ПОМОЩНИК"))
        body.addView(text("Локальные инструменты не отправляют текст в интернет.", 12f, R.color.text2))
        body.addView(actionButton("Краткое содержание", R.drawable.ic_ai) { showAssistant("Краткое содержание", summarize(editor.text.toString())) })
        body.addView(actionButton("Выделить ключевые пункты", R.drawable.ic_text) { showAssistant("Ключевые пункты", keyPoints(editor.text.toString())) })
        body.addView(actionButton("Определить тип документа", R.drawable.ic_doc) { showAssistant("Тип документа", classify(editor.text.toString())) })
        if (editable) body.addView(primaryButton("Сохранить копию", R.drawable.ic_save) { saveEdited() })
        body.addView(actionButton("Открыть в другом приложении", R.drawable.ic_open) {
            source?.let { Files.openWith(this, it) }
        })
        if (!editable) body.addView(text("Редактирование этого формата появится в следующем этапе разработки. Сейчас исходный документ не изменяется.", 12f, R.color.text2))
    }

    private fun actionButton(label: String, icon: Int, click: () -> Unit): TextView = text("${label}", 14f, R.color.accent, bold = true).apply {
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
        return candidates.take(12).joinToString("\n") { "• $it" }.ifBlank { "Не удалось выделить отдельные пункты. Попробуйте текстовый документ." }
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
        val match = types.map { it.first to it.second.fold(0) { count, word -> count + if (t.contains(word)) 1 else 0 } }.maxByOrNull { it.second }
        return if (match == null || match.second == 0) "Тип не определён уверенно. Можно классифицировать документ вручную." else "Вероятный тип: ${match.first}.\n\nОпределение выполняется локально по ключевым словам и может ошибаться."
    }

    private fun parsePptxText(raw: String): List<String> = raw
        .split(Regex("\\n\\s*\\n(?=СЛАЙД \\d+)"))
        .map { block -> block.lines().drop(1).joinToString("\n").trim() }
        .ifEmpty { listOf(raw) }

    private fun parseXlsxText(raw: String): List<Pair<String, List<List<String>>>> {
        val sections = raw.split(Regex("\\n\\s*\\n(?=ЛИСТ: )")).filter { it.isNotBlank() }
        return sections.mapIndexed { index, section ->
            val lines = section.lines()
            val name = lines.firstOrNull()?.removePrefix("ЛИСТ:")?.trim()?.ifBlank { "Лист${index + 1}" } ?: "Лист${index + 1}"
            val rows = lines.drop(1).map { line -> line.split('\t').map { it.trimEnd('\r') } }
                .filter { row -> row.any { it.isNotEmpty() } }
            name to rows
        }.ifEmpty { listOf("Лист1" to emptyList()) }
    }

    private fun saveEdited() {
        val f = source ?: return
        val value = editor.text.toString()
        val out = Files.tempFile(this, extension.ifBlank { "txt" })
        try {
            when (extension) {
                "docx" -> out.outputStream().use { OfficeWriter.docx(value, it, Files.baseName(f.name)) }
                "xlsx" -> out.outputStream().use { OfficeWriter.xlsx(parseXlsxText(value), it) }
                "pptx" -> out.outputStream().use { OfficeWriter.pptxText(parsePptxText(value), it) }
                "txt", "md", "csv", "html", "htm", "rtf" -> out.writeText(value, Charsets.UTF_8)
                else -> throw IllegalStateException("Сохранение для этого формата пока недоступно")
            }
            val uri = sourceUri
            if (uri != null && extension !in listOf("docx", "xlsx", "pptx")) {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { stream -> out.inputStream().use { it.copyTo(stream) } }
                        ?: throw IllegalStateException("Провайдер не разрешил запись")
                    toast("Изменения сохранены в исходный документ")
                    source = out
                } catch (e: Exception) {
                    TrDialog(this).setTitle("Не удалось заменить исходный файл")
                        .setMessage("У этого файла нет разрешения на запись. Сохранить изменённый документ отдельной копией?")
                        .setPositiveButton("Сохранить копию") { _, _ -> saveAs(out, Files.mimeOf(f.name), f.name) }
                        .setNegativeButton("Отмена", null).show()
                }
            } else {
                if (extension == "docx") toast("Сохраняю как новый DOCX: сложное оформление исходника не переносится")
                if (extension == "xlsx") toast("Сохраняю новый XLSX без исходных формул и оформления")
                if (extension == "pptx") toast("Сохраняю новый PPTX только с текстом слайдов")
                saveAs(out, Files.mimeOf(f.name), f.name)
            }
        } catch (e: Exception) {
            toast("Не удалось сохранить: ${e.message}")
        }
    }
}
