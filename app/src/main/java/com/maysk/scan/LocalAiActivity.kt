package com.maysk.scan

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Optional fully on-device assistant. The model is imported by the user and never bundled in APK. */
class LocalAiActivity : AppCompatActivity() {
    private lateinit var modelStatus: TextView
    private lateinit var answer: TextView
    private lateinit var question: EditText
    private lateinit var send: Button
    // LiteRT-LM engine, conversation, streaming and close share one native-owner thread.
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private val aiDispatcher = aiExecutor.asCoroutineDispatcher()
    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var modelFile: File? = null
    private var documentContext: String = ""
    private val aiSupported: Boolean get() = android.os.Process.is64Bit() && Build.SUPPORTED_ABIS.contains("arm64-v8a")

    private val chooseModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        documentContext = intent.getStringExtra("document_text").orEmpty().take(4500)
        buildUi()
        val saved = getSharedPreferences("local_ai", MODE_PRIVATE).getString("model_path", null)
        modelFile = saved?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
        updateModelStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "Локальный ИИ MayskScan"
            textSize = 21f
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "Модель работает на телефоне. После импорта ей не нужен интернет; документ не отправляется в облако. В запрос попадает до 4 500 символов текста документа. При импорте модель копируется в папку приложения; скачанный файл после импорта можно удалить."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 8)
        })
        modelStatus = TextView(this).apply { textSize = 13f; setTextColor(Color.DKGRAY) }
        root.addView(modelStatus)
        root.addView(Button(this).apply {
            text = "Страница модели Qwen3-0.6B (около 614 МБ)"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/litert-community/Qwen3-0.6B/tree/main")))
            }
        })
        root.addView(Button(this).apply {
            text = "Выбрать скачанный файл .litertlm"
            setOnClickListener { chooseModel.launch(arrayOf("*/*")) }
        })
        answer = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(12, 12, 12, 12)
            text = if (documentContext.isBlank()) "Задайте вопрос модели." else "Документ прикреплён к контексту (${documentContext.length} символов). Задайте вопрос."
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(answer) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "Скопировать ответ"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MayskScan AI", answer.text))
                toast("Ответ скопирован — его можно вставить в документ")
            }
        })
        question = EditText(this).apply {
            hint = "Например: кратко объясни документ и найди риски…"
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP
            setTextColor(Color.BLACK)
        }
        root.addView(question)
        send = Button(this).apply {
            text = "Спросить"
            isEnabled = false
            setOnClickListener { askModel() }
        }
        root.addView(send)
        setContentView(root)
    }

    private fun importModel(uri: Uri) {
        lifecycleScope.launch {
            send.isEnabled = false
            modelStatus.text = "Копирую модель в защищённую папку приложения…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = getExternalFilesDir("models") ?: File(filesDir, "models")
                    dir.mkdirs()
                    val displayName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        .orEmpty()
                    require(displayName.endsWith(".litertlm", ignoreCase = true)) { "Выберите файл с расширением .litertlm" }
                    val target = File(dir, "mayskscan-model.litertlm")
                    contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: error("Не удалось прочитать выбранный файл")
                    require(target.length() > 0) { "Файл модели пуст" }
                    target
                }
            }
            result.onSuccess {
                modelFile = it
                getSharedPreferences("local_ai", MODE_PRIVATE).edit().putString("model_path", it.absolutePath).apply()
                closeEngine()
                updateModelStatus()
                toast("Модель импортирована. При первом запуске она загрузится в память телефона.")
            }.onFailure {
                modelStatus.text = "Не удалось импортировать модель"
                toast(it.message ?: "Ошибка импорта")
            }
        }
    }

    private fun updateModelStatus() {
        val model = modelFile
        val memory = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(memory::getMemoryInfo)
        val availableGb = info.availMem / (1024.0 * 1024.0 * 1024.0)
        if (!aiSupported) {
            modelStatus.text = "Локальная нейросеть LiteRT-LM требует 64-битный ARM-процессор (arm64-v8a). Этот APK/процессор поддерживает только: ${Build.SUPPORTED_ABIS.joinToString()} . Офисные функции остаются доступны."
            send.isEnabled = false
        } else if (model == null) {
            modelStatus.text = "Модель не установлена. Импортируйте совместимый файл .litertlm (например, Qwen3-0.6B из LiteRT Community). Свободная RAM сейчас: %.1f ГБ. Модель требует ещё несколько ГБ памяти при запуске.".format(availableGb)
            send.isEnabled = false
        } else {
            modelStatus.text = "Модель готова: ${model.name} · %.0f МБ. Свободная RAM сейчас: %.1f ГБ.".format(model.length() / (1024.0 * 1024.0), availableGb)
            send.isEnabled = true
        }
    }

    private fun askModel() {
        val prompt = question.text.toString().trim()
        if (prompt.isBlank()) { toast("Введите вопрос"); return }
        if (!aiSupported) { toast("Для локальной нейросети нужен 64-битный ARM-процессор"); return }
        val model = modelFile ?: run { toast("Сначала выберите модель"); return }
        send.isEnabled = false
        answer.text = "Загружаю локальную модель…\n"
        lifecycleScope.launch {
            runCatching {
                val fullPrompt = buildString {
                    append("Ты — локальный офисный помощник MayskScan. Отвечай по-русски, точно и структурированно. Если данных в документе нет, прямо скажи об этом. Не выдумывай реквизиты и факты.\n\n")
                    if (documentContext.isNotBlank()) {
                        append("КОНТЕКСТ ДОКУМЕНТА:\n")
                        append(documentContext)
                        append("\n\n")
                    }
                    append("ВОПРОС ПОЛЬЗОВАТЕЛЯ:\n")
                    append(prompt)
                }
                answer.text = ""
                withContext(aiDispatcher) {
                    if (engine == null) {
                        val created = Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU(), cacheDir = cacheDir.absolutePath))
                        created.initialize()
                        engine = created
                        conversation = created.createConversation()
                    }
                    conversation!!.sendMessageAsync(fullPrompt).collect { message ->
                        withContext(Dispatchers.Main) { answer.append(message.toString()) }
                    }
                }
            }.onFailure {
                if (!isFinishing && !isDestroyed) answer.append("\n\nОшибка локальной модели: ${it.message ?: "неизвестная ошибка"}")
            }
            if (!isFinishing && !isDestroyed) send.isEnabled = modelFile?.isFile == true
        }
    }

    private fun closeEngine() {
        aiExecutor.execute {
            val oldConversation = conversation
            val oldEngine = engine
            conversation = null
            engine = null
            runCatching { oldConversation?.close() }
            runCatching { oldEngine?.close() }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        aiExecutor.execute {
            val oldConversation = conversation
            val oldEngine = engine
            conversation = null
            engine = null
            runCatching { oldConversation?.close() }
            runCatching { oldEngine?.close() }
            aiDispatcher.close()
        }
        super.onDestroy()
    }
}
