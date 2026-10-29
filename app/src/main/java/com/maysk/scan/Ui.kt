package com.maysk.scan

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.FileProvider
import java.io.File

// ---------- Размеры и цвета ----------

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.col(@ColorRes id: Int): Int = when (id) {
    // Цвет акцента выбирается в настройках
    R.color.accent -> Accent.color(this)
    R.color.accent2 -> Accent.dark(this)
    R.color.glow -> Accent.glow(this)
    else -> ContextCompat.getColor(this, id)
}

fun Context.fontBold(): Typeface = ResourcesCompat.getFont(this, R.font.montserrat_bold) ?: Typeface.DEFAULT_BOLD
fun Context.fontHeavy(): Typeface = ResourcesCompat.getFont(this, R.font.montserrat_extrabold) ?: Typeface.DEFAULT_BOLD

fun Context.rounded(color: Int, radiusDp: Int = 20, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp.toFloat())
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

/** Стеклянная карточка: тёмно-синий фон и тонкая светящаяся рамка. */
fun Context.cardBg(radiusDp: Int = 20, glow: Boolean = false): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(col(R.color.card2), col(R.color.card))).apply {
        cornerRadius = dpf(radiusDp.toFloat())
        setStroke(dp(1), if (glow) col(R.color.glow) else col(R.color.stroke))
    }

fun Context.accentBg(radiusDp: Int = 16): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(col(R.color.accent), col(R.color.accent2))).apply {
        cornerRadius = dpf(radiusDp.toFloat())
    }

/** Нажимаемый фон с волной. */
fun Context.clickable(bg: Drawable, radiusDp: Int = 20): Drawable {
    val mask = rounded(Color.WHITE, radiusDp)
    return RippleDrawable(ColorStateList.valueOf(0x332F7BDB), bg, mask)
}

// ---------- Простые элементы ----------

fun Context.text(s: CharSequence, sp: Float = 15f, @ColorRes color: Int = R.color.text, bold: Boolean = false, heavy: Boolean = false): TextView =
    TextView(this).apply {
        text = if (s is String) I18n.tr(s) else s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(col(color))
        if (heavy) typeface = fontHeavy() else if (bold) typeface = fontBold()
    }

fun Context.icon(@DrawableRes res: Int, sizeDp: Int = 24, @ColorRes tint: Int = R.color.text): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(col(tint))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

/** Цветной квадрат со значком — как на главном экране дизайна. */
fun Context.iconTile(@DrawableRes res: Int, @ColorRes colorRes: Int, sizeDp: Int = 46): FrameLayout =
    FrameLayout(this).apply {
        val c = col(colorRes)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c, darker(c))).apply {
            cornerRadius = dpf(sizeDp * 0.3f)
        }
        val iv = ImageView(context).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        addView(iv, FrameLayout.LayoutParams(dp((sizeDp * 0.55f).toInt()), dp((sizeDp * 0.55f).toInt()), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

fun darker(c: Int): Int = Color.rgb((Color.red(c) * 0.7f).toInt(), (Color.green(c) * 0.7f).toInt(), (Color.blue(c) * 0.75f).toInt())

fun Context.vbox(paddingDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
}

fun Context.hbox(paddingDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
}

fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun View.margins(ctx: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    val p = (layoutParams as? ViewGroup.MarginLayoutParams) ?: lp()
    p.setMargins(ctx.dp(l), ctx.dp(t), ctx.dp(r), ctx.dp(b))
    layoutParams = p
    return this
}

fun Context.primaryButton(label: String, @DrawableRes iconRes: Int? = null, onClick: () -> Unit): View {
    val box = hbox().apply {
        gravity = Gravity.CENTER
        background = clickable(accentBg(16), 16)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
    if (iconRes != null) box.addView(icon(iconRes, 20, R.color.white_fixed).apply { margins(context, r = 8) })
    box.addView(text(label, 16f, R.color.white_fixed, bold = true))
    return box
}

fun Context.secondaryButton(label: String, @DrawableRes iconRes: Int? = null, onClick: () -> Unit): View {
    val box = hbox().apply {
        gravity = Gravity.CENTER
        background = clickable(cardBg(16), 16)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        isClickable = true
        setOnClickListener { onClick() }
    }
    if (iconRes != null) box.addView(icon(iconRes, 20, R.color.accent).apply { margins(context, r = 8) })
    box.addView(text(label, 15f, R.color.text, bold = true))
    return box
}

/** Кнопка нижней панели действий: значок над подписью. */
fun Context.actionButton(@DrawableRes iconRes: Int, label: String, highlighted: Boolean = false, onClick: () -> Unit): View {
    val box = vbox().apply {
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(10), dp(4), dp(10))
        background = clickable(if (highlighted) accentBg(16) else cardBg(16), 16)
        isClickable = true
        setOnClickListener { onClick() }
    }
    box.addView(icon(iconRes, 22, if (highlighted) R.color.white_fixed else R.color.accent))
    box.addView(text(label, 11.5f, if (highlighted) R.color.white_fixed else R.color.text).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        margins(context, t = 4)
    })
    return box
}

/** Ряд кнопок действий одинаковой ширины. */
fun Context.actionRow(vararg items: View): LinearLayout = hbox().apply {
    for ((i, v) in items.withIndex()) {
        addView(v, lp(0, WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
    }
}

fun Context.chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
    text(label, 13.5f, if (selected) R.color.white_fixed else R.color.text2, bold = selected).apply {
        background = clickable(if (selected) accentBg(18) else rounded(col(R.color.card), 18, col(R.color.stroke)), 18)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

fun Context.chipRow(): Pair<HorizontalScrollView, LinearLayout> {
    val row = hbox()
    val sc = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }
    return sc to row
}

/** Строка настроек / списка: значок, заголовок, подпись, справа — значение или стрелка. */
fun Context.listRow(@DrawableRes iconRes: Int?, title: String, subtitle: String? = null, right: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
    val row = hbox().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = clickable(cardBg(16), 16)
        if (onClick != null) setOnClickListener { onClick() }
    }
    if (iconRes != null) row.addView(icon(iconRes, 22, R.color.accent).apply { margins(context, r = 14) })
    val col = vbox()
    col.addView(text(title, 15f, R.color.text))
    if (subtitle != null) col.addView(text(subtitle, 12.5f, R.color.text2).apply { maxLines = 2 })
    row.addView(col, lp(0, WRAP_CONTENT, 1f))
    if (right != null) row.addView(right) else if (onClick != null) row.addView(icon(R.drawable.ic_chevron, 20, R.color.text2))
    return row
}

fun Context.switchView(on: Boolean, onChange: (Boolean) -> Unit): androidx.appcompat.widget.SwitchCompat =
    androidx.appcompat.widget.SwitchCompat(this).apply {
        isChecked = on
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, col(R.color.text2)))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(col(R.color.accent), col(R.color.stroke)))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

fun Context.field(hint: String, value: String = "", multiline: Boolean = false): EditText = EditText(this).apply {
    this.hint = I18n.tr(hint)
    setText(value)
    setTextColor(col(R.color.text))
    setHintTextColor(col(R.color.text2))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    background = rounded(col(R.color.card), 14, col(R.color.stroke))
    setPadding(dp(14), dp(12), dp(14), dp(12))
    if (multiline) {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        gravity = Gravity.TOP or Gravity.START
        minLines = 3
    } else {
        isSingleLine = true
    }
}

fun Context.sectionTitle(s: String): TextView = text(s, 13f, R.color.text2, bold = true).apply {
    setPadding(dp(4), dp(18), dp(4), dp(8))
}

fun Context.toast(s: String) = Toast.makeText(this, I18n.tr(s), Toast.LENGTH_SHORT).show()

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(java.util.Locale("ru"), if (I18n.lang == "en") "%.1f MB" else "%.1f МБ", bytes / 1048576.0)
    bytes >= 1024 -> String.format(java.util.Locale("ru"), if (I18n.lang == "en") "%.1f KB" else "%.1f КБ", bytes / 1024.0)
    else -> if (I18n.lang == "en") "$bytes B" else "$bytes Б"
}

fun formatDate(ms: Long): String = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru")).format(java.util.Date(ms))

// ---------- Базовый экран ----------

/**
 * Общая основа экранов: верхняя панель «назад + заголовок + кнопки», выбор файлов,
 * сохранение через системное окно, окно ожидания.
 */
open class BaseActivity : AppCompatActivity() {

    lateinit var topBar: LinearLayout
    lateinit var titleView: TextView
    private var progress: Dialog? = null
    private var progressText: TextView? = null

    private var onPicked: ((List<Uri>) -> Unit)? = null
    private val pickOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked?.invoke(listOf(uri))
    }
    private val pickMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked?.invoke(uris)
    }

    private var onCreated: ((Uri) -> Unit)? = null
    private var createMime = "*/*"
    private val createDoc = registerForActivityResult(object : ActivityResultContracts.CreateDocument("*/*") {
        override fun createIntent(context: Context, input: String) = super.createIntent(context, input).setType(createMime)
    }) { uri -> if (uri != null) onCreated?.invoke(uri) }

    private var onPhoto: ((Uri?) -> Unit)? = null
    private var photoUri: Uri? = null
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        onPhoto?.invoke(if (ok) photoUri else null)
    }

    private var onCamPerm: ((Boolean) -> Unit)? = null
    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { onCamPerm?.invoke(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(col(R.color.bg)))
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        // Перевод надписей, выставленных напрямую (язык интерфейса — в настройках)
        if (I18n.lang != "ru" && view != null) {
            view.viewTreeObserver.addOnGlobalLayoutListener { I18n.translateTree(view) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Блокировка входа (PIN / отпечаток)
        if (this !is LockActivity && this !is SplashActivity && Lock.needed(this)) {
            startActivity(android.content.Intent(this, LockActivity::class.java))
        }
    }

    /** Экран с верхней панелью. Возвращает контейнер для содержимого. */
    fun screen(title: String, scroll: Boolean = true, back: Boolean = true): LinearLayout {
        val root = vbox()
        topBar = hbox().apply { setPadding(dp(8), dp(10), dp(8), dp(10)) }
        if (back) {
            topBar.addView(iconButton(R.drawable.ic_back) { onBackPressedDispatcher.onBackPressed() })
        } else {
            topBar.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        }
        titleView = text(title, 19f, R.color.text, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
        }
        topBar.addView(titleView, lp(0, WRAP_CONTENT, 1f))
        root.addView(topBar, lp())
        val content = vbox(16)
        if (scroll) {
            val sv = ScrollView(this).apply { isFillViewport = true; addView(content) }
            root.addView(sv, lp(MATCH_PARENT, 0, 1f))
        } else {
            root.addView(content, lp(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        return content
    }

    fun iconButton(@DrawableRes res: Int, @ColorRes tint: Int = R.color.text, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            val iv = icon(res, 24, tint)
            addView(iv, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            background = clickable(ColorDrawable(Color.TRANSPARENT), 22)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { onClick() }
        }

    fun addTopAction(@DrawableRes res: Int, onClick: () -> Unit) {
        topBar.addView(iconButton(res, R.color.text, onClick))
    }

    // ---------- Файлы ----------

    fun pick(mimes: Array<String>, multiple: Boolean = false, cb: (List<Uri>) -> Unit) {
        onPicked = cb
        try {
            if (multiple) pickMany.launch(mimes) else pickOne.launch(mimes)
        } catch (e: Exception) {
            toast("Нет приложения для выбора файлов")
        }
    }

    /** Сохранить копию файла в выбранное пользователем место (Загрузки, Google Диск и т.д.). */
    fun saveAs(file: File, mime: String, name: String = file.name) {
        createMime = mime
        onCreated = { uri ->
            try {
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                toast("Сохранено")
            } catch (e: Exception) {
                toast("Не удалось сохранить: ${e.message}")
            }
        }
        try {
            createDoc.launch(name)
        } catch (e: Exception) {
            toast("Нет приложения для сохранения файлов")
        }
    }

    fun withCamera(cb: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            cb(); return
        }
        onCamPerm = { ok -> if (ok) cb() else toast("Нужен доступ к камере") }
        camPerm.launch(android.Manifest.permission.CAMERA)
    }

    /** Снимок камерой телефона во временный файл. */
    fun photo(cb: (Uri?) -> Unit) = withCamera {
        val f = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        photoUri = uri
        onPhoto = cb
        try {
            takePhoto.launch(uri)
        } catch (e: Exception) {
            toast("Нет приложения камеры")
        }
    }

    fun share(files: List<File>, mime: String) = Files.share(this, files, mime)

    // ---------- Ожидание и диалоги ----------

    fun showProgress(msg: String) {
        if (progress == null) {
            val box = hbox(20)
            box.addView(ProgressBar(this).apply {
                indeterminateTintList = ColorStateList.valueOf(col(R.color.accent))
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            progressText = text(msg, 15f).apply { setPadding(dp(16), 0, 0, 0) }
            box.addView(progressText, lp(0, WRAP_CONTENT, 1f))
            progress = TrDialog(this).setView(box).setCancelable(false).create()
            progress?.show()
        } else {
            progressText?.text = I18n.tr(msg)
        }
    }

    fun hideProgress() {
        progress?.dismiss()
        progress = null
    }

    fun confirm(title: String, message: String, yes: String = "Да", action: () -> Unit) {
        TrDialog(this).setTitle(title).setMessage(message)
            .setPositiveButton(yes) { _, _ -> action() }
            .setNegativeButton("Отмена", null).show()
    }

    fun ask(title: String, hint: String, value: String = "", inputType: Int = InputType.TYPE_CLASS_TEXT, ok: (String) -> Unit) {
        val f = field(hint, value).apply { this.inputType = inputType }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f) }
        TrDialog(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> ok(f.text.toString()) }
            .setNegativeButton("Отмена", null).show()
    }

    fun choose(title: String, items: List<String>, cb: (Int) -> Unit) {
        TrDialog(this).setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> cb(which) }
            .setNegativeButton("Отмена", null).show()
    }

    fun fail(e: Throwable) {
        hideProgress()
        val msg = when {
            e is OutOfMemoryError -> "Не хватает памяти телефона. Попробуйте файл поменьше."
            e.message?.contains("password", true) == true -> "Файл защищён паролем или пароль неверный."
            else -> e.message ?: e.javaClass.simpleName
        }
        TrDialog(this).setTitle("Не получилось").setMessage(msg).setPositiveButton("OK", null).show()
    }
}
