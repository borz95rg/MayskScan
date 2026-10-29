package com.maysk.scan

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Фото → Текст: распознавание, правка, перевод, чтение вслух, экспорт. */
class OcrActivity : BaseActivity() {

    private val images = ArrayList<File>()
    private var lang = "auto"
    private var detected = "und"

    private lateinit var thumbs: LinearLayout
    private lateinit var langChip: TextView
    private lateinit var status: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var editor: EditText
    private lateinit var resultBox: LinearLayout
    private lateinit var transBox: LinearLayout
    private lateinit var transText: TextView
    private lateinit var transTitle: TextView
    private lateinit var startBtn: View
    private var tts: TextToSpeech? = null
    private var speaking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = Prefs(this).ocrLang
        val body = screen("Фото → Текст")
        addTopAction(R.drawable.ic_add) { addImages() }

        val tsc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        thumbs = hbox()
        tsc.addView(thumbs)
        body.addView(tsc)

        val langRow = hbox().apply { setPadding(0, dp(12), 0, 0) }
        langRow.addView(text("Язык:", 14f, R.color.text2).apply { setPadding(0, 0, dp(10), 0) })
        langChip = chip(Ocr.nameOf(lang), true) { chooseLang() }
        langRow.addView(langChip)
        body.addView(langRow)

        startBtn = primaryButton("Распознать текст", R.drawable.ic_ai) { recognize() }
        body.addView(startBtn, lp().apply { topMargin = dp(12) })

        status = hbox(14).apply { background = cardBg(16, glow = true); visibility = View.GONE }
        status.addView(ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        statusText = text("Распознаю…", 14f).apply { setPadding(dp(12), 0, 0, 0) }
        status.addView(statusText)
        body.addView(status, lp().apply { topMargin = dp(12) })

        resultBox = vbox().apply { visibility = View.GONE }
        resultBox.addView(sectionTitle("ОРИГИНАЛ — можно править"))
        editor = EditText(this).apply {
            setTextColor(col(R.color.text))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            background = cardBg(16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 6
            setTextIsSelectable(true)
        }
        resultBox.addView(editor)
        resultBox.addView(actionRow(
            actionButton(R.drawable.ic_copy, "Копировать") { Clip.copy(this, editor.text.toString()) },
            actionButton(R.drawable.ic_search, "Найти") { findReplace() },
            actionButton(R.drawable.ic_translate, "Перевести") { translate() },
            actionButton(R.drawable.ic_speak, "Вслух") { speak() },
        ), lp().apply { topMargin = dp(10) })
        resultBox.addView(actionRow(
            actionButton(R.drawable.ic_save, "Сохранить") { saveTxt() },
            actionButton(R.drawable.ic_share, "Поделиться") { Files.shareText(this, editor.text.toString()) },
            actionButton(R.drawable.ic_doc, "Экспорт", highlighted = true) { export(editor.text.toString(), "Текст") },
        ), lp().apply { topMargin = dp(8) })

        transBox = vbox().apply { visibility = View.GONE }
        transTitle = sectionTitle("ПЕРЕВОД")
        transBox.addView(transTitle)
        transText = text("", 15f).apply {
            background = cardBg(16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextIsSelectable(true)
        }
        transBox.addView(transText)
        transBox.addView(actionRow(
            actionButton(R.drawable.ic_copy, "Копировать") { Clip.copy(this, transText.text.toString()) },
            actionButton(R.drawable.ic_doc, "Экспорт") { export(transText.text.toString(), "Перевод") },
            actionButton(R.drawable.ic_share, "Поделиться") { Files.shareText(this, transText.text.toString()) },
        ), lp().apply { topMargin = dp(10) })
        resultBox.addView(transBox)
        body.addView(resultBox, lp().apply { topMargin = dp(4) })

        // Откуда пришли картинки
        intent.getStringExtra("text_file")?.let { path ->
            titleView.text = File(path).nameWithoutExtension
            editor.setText(File(path).readText())
            startBtn.visibility = View.GONE
            resultBox.visibility = View.VISIBLE
            return
        }
        intent.getStringArrayExtra("images")?.let { arr -> images.addAll(arr.map { File(it) }) }
        intent.getParcelableArrayListExtra<Uri>("uris")?.let { addUris(it) }
        if (images.isEmpty() && savedInstanceState == null && intent.getParcelableArrayListExtra<Uri>("uris") == null) addImages()
        renderThumbs()
        if (images.isNotEmpty() && intent.getStringArrayExtra("images") != null) recognize()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun addImages() {
        choose("Откуда взять текст?", listOf("Сфотографировать", "Выбрать фото", "Сканер документов")) { i ->
            when (i) {
                0 -> photo { uri -> if (uri != null) addUris(listOf(uri)) }
                1 -> pick(arrayOf("image/*"), true) { addUris(it) }
                2 -> { startActivity(android.content.Intent(this, ScanActivity::class.java)); finish() }
            }
        }
    }

    private fun addUris(uris: List<Uri>) {
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    uris.mapIndexed { i, u ->
                        val bmp = ImageTools.decode(this@OcrActivity, u, 2600)
                        val f = File(cacheDir, "ocr_${System.nanoTime()}_$i.jpg")
                        ImageTools.saveJpeg(bmp, f, 92)
                        bmp.recycle()
                        f
                    }
                }
                images.addAll(files)
                renderThumbs()
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    private fun renderThumbs() {
        thumbs.removeAllViews()
        images.forEachIndexed { i, f ->
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(col(R.color.card), 12, col(R.color.stroke))
                setPadding(dp(2), dp(2), dp(2), dp(2))
                setImageBitmap(BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 }))
                setOnClickListener {
                    confirm("Убрать фото?", "Фото ${i + 1} не будет распознаваться.", "Убрать") { images.removeAt(i); renderThumbs() }
                }
            }
            thumbs.addView(iv, LinearLayout.LayoutParams(dp(84), dp(112)).apply { rightMargin = dp(8) })
        }
        startBtn.alpha = if (images.isEmpty()) 0.5f else 1f
    }

    private fun chooseLang() {
        val opts = listOf("auto") + Ocr.LANGS.map { it.code } + listOf("rus+eng", "kaz+rus")
        choose("Язык текста", opts.map { Ocr.nameOf(it) }) { i ->
            lang = opts[i]
            langChip.text = Ocr.nameOf(lang)
        }
    }

    private fun recognize() {
        if (images.isEmpty()) { addImages(); return }
        lifecycleScope.launch {
            status.visibility = View.VISIBLE
            startBtn.isEnabled = false
            val enhance = Prefs(this@OcrActivity).autoEnhance
            val parts = ArrayList<String>()
            var usedLangs = ""
            try {
                for ((i, f) in images.withIndex()) {
                    statusText.text = if (images.size > 1) "Распознаю фото ${i + 1} из ${images.size}…" else "Распознаю текст…"
                    val r = withContext(Dispatchers.Default) {
                        val bmp = ImageTools.decodeFile(f, 2600)
                        try {
                            Ocr.recognize(this@OcrActivity, bmp, lang, enhance) { p ->
                                runOnUiThread { statusText.text = (if (images.size > 1) "Фото ${i + 1} из ${images.size}: " else "Распознаю: ") + "$p%" }
                            }
                        } finally {
                            bmp.recycle()
                        }
                    }
                    parts.add(r.text)
                    usedLangs = r.langs
                }
                val text = parts.joinToString("\n\n")
                detected = Ocr.detect(text)
                editor.setText(text)
                resultBox.visibility = View.VISIBLE
                status.visibility = View.GONE
                if (text.isBlank()) {
                    toast("Текст не найден. Попробуйте фото ровнее и при хорошем свете.")
                } else {
                    History.add(this@OcrActivity, Kind.OCR, saveTextFile(text, autoName(text)), autoName(text) + ".txt", Ocr.nameOf(usedLangs))
                    if (Prefs(this@OcrActivity).tables && OfficeWriter.looksLikeTable(text)) offerTable(text)
                }
            } catch (e: Throwable) {
                status.visibility = View.GONE
                fail(e)
            } finally {
                startBtn.isEnabled = true
            }
        }
    }

    private fun autoName(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.length >= 3 }?.take(40)?.let { Files.sanitize(it) } ?: "Текст ${Files.stamp()}"

    private fun saveTextFile(text: String, base: String): File {
        val f = Files.newDoc(this, base, "txt")
        f.writeText(text)
        return f
    }

    /** «Сохранить»: сразу выбор формата (Word первым), затем название. */
    private fun saveTxt() {
        val text = editor.text.toString()
        if (text.isBlank()) { toast("Сначала распознайте текст"); return }
        val formats = listOf("Word (DOCX)", "PDF", "Текст (TXT)", "Excel (XLSX)")
        val exts = listOf("docx", "pdf", "txt", "xlsx")
        choose("Сохранить как…", formats) { i ->
            ask("Название файла", "Название", autoName(text)) { n ->
                exportAs(text, "Текст", exts[i], n.trim().ifBlank { autoName(text) })
            }
        }
    }

    private fun offerTable(text: String) {
        AlertDialog.Builder(this)
            .setTitle("Похоже на таблицу")
            .setMessage("Сохранить распознанную таблицу в Excel?")
            .setPositiveButton("В Excel") { _, _ -> exportAs(text, "Таблица", "xlsx") }
            .setNegativeButton("Не нужно", null)
            .show()
    }

    private fun findReplace() {
        val box = vbox(16)
        val find = field("Найти")
        val repl = field("Заменить на (необязательно)")
        box.addView(find)
        box.addView(repl, lp().apply { topMargin = dp(8) })
        AlertDialog.Builder(this).setTitle("Поиск и замена").setView(box)
            .setPositiveButton("Найти") { _, _ ->
                val q = find.text.toString()
                if (q.isEmpty()) return@setPositiveButton
                val t = editor.text.toString()
                val count = Regex(Regex.escape(q), RegexOption.IGNORE_CASE).findAll(t).count()
                val idx = t.indexOf(q, ignoreCase = true)
                if (idx >= 0) {
                    editor.requestFocus()
                    editor.setSelection(idx, idx + q.length)
                }
                toast(if (count == 0) "Не найдено" else "Найдено: $count")
            }
            .setNeutralButton("Заменить все") { _, _ ->
                val q = find.text.toString()
                if (q.isEmpty()) return@setNeutralButton
                val t = editor.text.toString()
                val re = Regex(Regex.escape(q), RegexOption.IGNORE_CASE)
                val count = re.findAll(t).count()
                editor.setText(re.replace(t, Regex.escapeReplacement(repl.text.toString())))
                toast("Заменено: $count")
            }
            .setNegativeButton("Отмена", null).show()
    }

    // ---------- Перевод (ML Kit, после разовой загрузки работает без интернета) ----------

    private val transLangs = listOf(
        "ru" to "Русский", "en" to "English", "uk" to "Українська", "de" to "Deutsch",
        "fr" to "Français", "es" to "Español", "tr" to "Türkçe",
    )

    private fun translate() {
        val text = editor.text.toString()
        if (text.isBlank()) return
        choose("Перевести на…", transLangs.map { it.second } + listOf("Қазақша (пока не поддерживается)")) { i ->
            if (i >= transLangs.size) {
                AlertDialog.Builder(this).setTitle("Казахский язык")
                    .setMessage("Перевод на казахский без интернета пока не поддерживается: такого языкового пакета нет у Google. Распознавание казахского текста работает.")
                    .setPositiveButton("OK", null).show()
                return@choose
            }
            val target = transLangs[i].first
            lifecycleScope.launch {
                val src0 = if (detected == "und") Ocr.detect(text) else detected
                val src = TranslateLanguage.fromLanguageTag(src0)
                val dst = TranslateLanguage.fromLanguageTag(target)
                if (src == null) { toast("Этот язык нельзя перевести без интернета"); return@launch }
                if (src == dst) { toast("Текст уже на этом языке"); return@launch }
                val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst!!).build())
                try {
                    showProgress("Подготовка перевода…\nПри первом переводе языковой пакет (~30 МБ) скачивается один раз, потом всё работает без интернета.")
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                    showProgress("Перевожу…")
                    val paras = text.split("\n")
                    val out = ArrayList<String>()
                    for (p in paras) out.add(if (p.isBlank()) "" else translator.translate(p).await())
                    hideProgress()
                    transTitle.text = "ПЕРЕВОД: ${transLangs[i].second.uppercase(Locale("ru"))}"
                    transText.text = out.joinToString("\n")
                    transBox.visibility = View.VISIBLE
                } catch (e: Exception) {
                    hideProgress()
                    AlertDialog.Builder(this@OcrActivity).setTitle("Перевод")
                        .setMessage("Не удалось подготовить перевод. Для первого перевода на этот язык нужен интернет (один раз).\n\n${e.message ?: ""}")
                        .setPositiveButton("OK", null).show()
                } finally {
                    translator.close()
                }
            }
        }
    }

    // ---------- Чтение вслух ----------

    private fun speak() {
        if (speaking) { tts?.stop(); speaking = false; toast("Остановлено"); return }
        val text = editor.text.toString()
        if (text.isBlank()) return
        val go = {
            val t = tts!!
            val loc = when (if (detected == "und") "ru" else detected) {
                "kk" -> Locale("kk", "KZ"); "en" -> Locale.US; "uk" -> Locale("uk", "UA"); "de" -> Locale.GERMANY
                "fr" -> Locale.FRANCE; "es" -> Locale("es", "ES"); "tr" -> Locale("tr", "TR"); else -> Locale("ru", "RU")
            }
            if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.language = loc
            else t.language = Locale("ru", "RU")
            // Длинный текст читаем частями
            text.split(Regex("(?<=[.!?\\n])")).filter { it.isNotBlank() }.chunked(6).forEachIndexed { i, chunk ->
                t.speak(chunk.joinToString(" "), if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "p$i")
            }
            speaking = true
            toast("Читаю вслух… Нажмите «Вслух» ещё раз, чтобы остановить")
        }
        if (tts == null) {
            tts = TextToSpeech(this) { st ->
                if (st == TextToSpeech.SUCCESS) runOnUiThread { go() } else runOnUiThread { toast("На телефоне нет синтезатора речи") }
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {}
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {}
            })
        } else go()
    }

    // ---------- Экспорт ----------

    private fun export(text: String, title: String) {
        if (text.isBlank()) return
        val formats = listOf("TXT", "Word (DOCX)", "PDF", "RTF", "HTML", "Markdown", "Excel (XLSX)", "CSV")
        val exts = listOf("txt", "docx", "pdf", "rtf", "html", "md", "xlsx", "csv")
        choose("Экспорт в…", formats) { i -> exportAs(text, title, exts[i]) }
    }

    private fun exportAs(text: String, title: String, ext: String, name: String? = null) {
        lifecycleScope.launch {
            showProgress("Создаю файл…")
            try {
                val base = Files.sanitize(name ?: autoName(text))
                val out = Files.newDoc(this@OcrActivity, base, ext)
                withContext(Dispatchers.IO) {
                    when (ext) {
                        "txt" -> out.writeText(text)
                        "docx" -> out.outputStream().use { OfficeWriter.docx(text, it) }
                        "pdf" -> TextPdf.fromText(text, out)
                        "rtf" -> out.writeText(OfficeWriter.rtf(text))
                        "html" -> out.writeText(OfficeWriter.html(text, title))
                        "md" -> out.writeText(OfficeWriter.markdown(text, title))
                        "xlsx" -> out.outputStream().use { OfficeWriter.xlsx(listOf(title to OfficeWriter.textToRows(text)), it) }
                        "csv" -> out.writeText("﻿" + OfficeWriter.csv(OfficeWriter.textToRows(text)))
                    }
                    Files.exportCopy(this@OcrActivity, out)
                }
                History.add(this@OcrActivity, if (ext == "txt") Kind.OCR else Kind.CONVERT, out)
                hideProgress()
                AlertDialog.Builder(this@OcrActivity).setTitle("Готово")
                    .setMessage("«${out.name}» сохранён в Истории.")
                    .setPositiveButton("Поделиться") { _, _ -> share(listOf(out), Files.mimeOf(out.name)) }
                    .setNeutralButton("На телефон…") { _, _ -> saveAs(out, Files.mimeOf(out.name)) }
                    .setNegativeButton("Открыть") { _, _ -> if (ext == "pdf") startActivity(android.content.Intent(this@OcrActivity, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, out.absolutePath)) else Files.openWith(this@OcrActivity, out) }
                    .show()
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }
}
