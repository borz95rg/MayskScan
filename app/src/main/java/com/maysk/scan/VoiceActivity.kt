package com.maysk.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Диктовка: голос → текст → документ (Word, PDF, TXT).
 * Распознаёт встроенный в телефон сервис речи; если на телефоне скачан офлайн-язык — без интернета.
 * Голосовые команды: «точка», «запятая», «вопросительный знак», «новая строка».
 */
class VoiceActivity : BaseActivity() {

    private val langs = listOf("ru-RU" to "Русский", "kk-KZ" to "Қазақша", "en-US" to "English")
    private var lang = "ru-RU"
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var onDevice = true

    private lateinit var editor: EditText
    private lateinit var partial: TextView
    private lateinit var micBox: FrameLayout
    private lateinit var hint: TextView
    private lateinit var langRow: LinearLayout

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else toast("Нужен доступ к микрофону")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = screen("Голос → Текст")
        lang = when (I18n.lang) { "kk" -> "kk-KZ"; "en" -> "en-US"; else -> "ru-RU" }

        val (sc, row) = chipRow()
        langRow = row
        body.addView(sc)
        renderLangs()

        micBox = FrameLayout(this).apply { setOnClickListener { toggle() } }
        micBox.addView(icon(R.drawable.ic_mic, 44, R.color.white_fixed), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        val micWrap = LinearLayout(this).apply { gravity = Gravity.CENTER }
        micWrap.addView(micBox, LinearLayout.LayoutParams(dp(110), dp(110)))
        body.addView(micWrap, lp().apply { topMargin = dp(20) })
        hint = text("Нажмите и говорите", 14f, R.color.text2).apply { gravity = Gravity.CENTER }
        body.addView(hint, lp().apply { topMargin = dp(10) })
        drawMic()

        partial = text("", 14f, R.color.accent).apply { setPadding(dp(4), dp(10), dp(4), 0) }
        body.addView(partial)

        body.addView(sectionTitle("ТЕКСТ — можно править"))
        editor = EditText(this).apply {
            setTextColor(col(R.color.text))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            background = cardBg(16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 8
        }
        body.addView(editor)
        body.addView(actionRow(
            actionButton(R.drawable.ic_copy, "Копировать") { Clip.copy(this, editor.text.toString()) },
            actionButton(R.drawable.ic_delete, "Очистить") { confirm("Очистить текст?", "Весь надиктованный текст будет удалён.", "Очистить") { editor.setText("") } },
            actionButton(R.drawable.ic_doc, "Сохранить", highlighted = true) { save() },
        ), lp().apply { topMargin = dp(10) })
        body.addView(text("Команды: «точка», «запятая», «вопросительный знак», «новая строка».\n\nБез интернета работает, если в телефоне скачан офлайн-язык распознавания речи (Настройки телефона → Google → Голосовой ввод → Офлайн-распознавание). Казахский офлайн поддерживается не на всех телефонах.", 12.5f, R.color.text2).apply { setPadding(dp(4), dp(14), dp(4), dp(20)) })

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            hint.text = "На телефоне нет сервиса распознавания речи"
            micBox.alpha = 0.4f
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (listening) stop()
    }

    private fun renderLangs() {
        langRow.removeAllViews()
        langs.forEach { (code, name) ->
            langRow.addView(chip(name, code == lang) {
                lang = code
                renderLangs()
                if (listening) { stop(); start() }
            }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
    }

    private fun drawMic() {
        micBox.background = rounded(if (listening) col(R.color.danger) else col(R.color.accent), 55)
        micBox.scaleX = if (listening) 1.08f else 1f
        micBox.scaleY = micBox.scaleX
        if (::hint.isInitialized) hint.text = I18n.tr(if (listening) "Слушаю… Нажмите, чтобы остановить" else "Нажмите и говорите")
    }

    private fun toggle() {
        if (listening) { stop(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("На телефоне нет сервиса распознавания речи"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
        } else start()
    }

    private fun create(): SpeechRecognizer {
        recognizer?.destroy()
        val r = if (onDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
        recognizer = r
        return r
    }

    private fun start() {
        listening = true
        drawMic()
        listen()
    }

    private fun listen() {
        val r = recognizer ?: create()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        try { r.startListening(i) } catch (e: Exception) { stop(); toast("Не удалось включить микрофон") }
    }

    private fun stop() {
        listening = false
        try { recognizer?.stopListening() } catch (e: Exception) {}
        partial.text = ""
        drawMic()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (listening) { val s = 1.04f + (rmsdB.coerceIn(0f, 10f) / 60f); micBox.scaleX = s; micBox.scaleY = s }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(b: Bundle?) {
            partial.text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
        }

        override fun onResults(b: Bundle?) {
            val s = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            partial.text = ""
            if (!s.isNullOrBlank()) append(s)
            if (listening) listen() // диктуем дальше без остановки
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (listening) listen()
                12, 13 -> { // язык не поддерживается / не скачан на телефоне
                    if (onDevice) { onDevice = false; create(); if (listening) listen() }
                    else { stop(); toast("Этот язык не скачан для распознавания речи на телефоне") }
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    stop()
                    TrDialog(this@VoiceActivity).setTitle("Нужен офлайн-язык")
                        .setMessage("Чтобы диктовать без интернета, скачайте язык: Настройки телефона → Google → Голосовой ввод → Офлайн-распознавание речи. Или включите интернет.")
                        .setPositiveButton("OK", null).show()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { stop(); toast("Нужен доступ к микрофону") }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { create(); if (listening) editor.postDelayed({ if (listening) listen() }, 400) }
                else -> if (listening) editor.postDelayed({ if (listening) { create(); listen() } }, 300)
            }
        }
    }

    /** Добавляет фразу в текст: голосовые команды → знаки, заглавная буква в начале предложения. */
    private fun append(raw: String) {
        var s = " $raw "
        val cmds = listOf(
            "вопросительный знак" to "?", "восклицательный знак" to "!", "точка с запятой" to ";",
            "новая строка" to "\n", "с новой строки" to "\n", "новый абзац" to "\n\n",
            "двоеточие" to ":", "запятая" to ",", "точка" to ".",
            "question mark" to "?", "new line" to "\n", "comma" to ",", "period" to ".", "full stop" to ".",
            "нүкте" to ".", "үтір" to ",", "жаңа жол" to "\n",
        )
        for ((w, p) in cmds) s = s.replace(Regex("\\s+$w(?=\\s)", RegexOption.IGNORE_CASE), p)
        s = s.trim()
        if (s.isEmpty()) return
        val cur = editor.text.toString()
        val needCap = cur.isBlank() || cur.trimEnd().lastOrNull()?.let { it == '.' || it == '!' || it == '?' || it == '\n' } == true || cur.endsWith("\n")
        if (needCap && s.first().isLetter()) s = s.first().uppercaseChar() + s.substring(1)
        val sep = when {
            cur.isEmpty() || cur.endsWith("\n") || s.first() in ".,!?;:\n" -> ""
            else -> " "
        }
        editor.append(sep + s)
        editor.setSelection(editor.text.length)
    }

    private fun save() {
        val text = editor.text.toString().trim()
        if (text.isEmpty()) { toast("Сначала надиктуйте текст"); return }
        if (listening) stop()
        val base = Ocr.titleFrom(text) ?: "Диктовка ${Files.stamp()}"
        val f = Files.newDoc(this, base, "txt")
        f.writeText(text)
        History.add(this, Kind.OCR, f)
        // На экране текста: перевод, Word, PDF, Excel
        startActivity(Intent(this, OcrActivity::class.java).putExtra("text_file", f.absolutePath))
    }
}
