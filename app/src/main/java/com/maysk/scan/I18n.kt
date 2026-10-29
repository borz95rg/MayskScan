package com.maysk.scan

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Язык интерфейса: русский (как написано в коде), казахский, английский.
 * Переводы лежат в assets/i18n/<язык>.json: точные строки и шаблоны с числами/именами.
 */
object I18n {
    val LANGS = listOf("ru" to "Русский", "kk" to "Қазақша", "en" to "English")

    @Volatile var lang = "ru"
        private set
    private var exact: Map<String, String> = emptyMap()
    private var patterns: List<Pair<Pattern, String>> = emptyList()

    fun init(ctx: Context) = load(ctx, Prefs(ctx).uiLang)

    @Synchronized
    fun load(ctx: Context, code: String) {
        lang = code
        if (code == "ru") { exact = emptyMap(); patterns = emptyList(); return }
        try {
            val json = JSONObject(ctx.assets.open("i18n/$code.json").use { it.readBytes().toString(Charsets.UTF_8) })
            val e = json.getJSONObject("e")
            exact = e.keys().asSequence().associateWith { e.getString(it) }
            val p = json.getJSONArray("p")
            patterns = (0 until p.length()).map { i ->
                val a = p.getJSONArray(i)
                Pattern.compile(a.getString(0), Pattern.DOTALL) to a.getString(1)
            }
        } catch (x: Exception) {
            exact = emptyMap(); patterns = emptyList()
        }
    }

    fun tr(s: CharSequence?): String {
        val str = s?.toString() ?: return ""
        if (lang == "ru" || str.isEmpty()) return str
        exact[str]?.let { return it }
        for ((re, repl) in patterns) {
            val m = re.matcher(str)
            if (m.matches()) return try { m.replaceFirst(repl) } catch (e: Exception) { str }
        }
        return str
    }

    /** Переводит все надписи на экране (то, что выставлено напрямую в коде). */
    fun translateTree(v: View) {
        if (lang == "ru") return
        when (v) {
            is EditText -> v.hint?.let { h -> val t = tr(h); if (t != h.toString()) v.hint = t }
            is TextView -> {
                val cur = v.text
                if (cur !is android.text.Spanned || cur.getSpans(0, cur.length, Any::class.java).isEmpty()) {
                    val t = tr(cur)
                    if (t != cur.toString()) v.text = t
                }
            }
            is ViewGroup -> for (i in 0 until v.childCount) translateTree(v.getChildAt(i))
        }
    }
}

/** Диалог, который сам переводит заголовок, текст и кнопки. */
class TrDialog(ctx: Context) : AlertDialog.Builder(ctx) {
    override fun setTitle(title: CharSequence?): AlertDialog.Builder = super.setTitle(I18n.tr(title))
    override fun setMessage(message: CharSequence?): AlertDialog.Builder = super.setMessage(I18n.tr(message))
    override fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setPositiveButton(I18n.tr(text), listener)
    override fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setNegativeButton(I18n.tr(text), listener)
    override fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setNeutralButton(I18n.tr(text), listener)
    override fun setItems(items: Array<out CharSequence>?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setItems(items?.map { I18n.tr(it) }?.toTypedArray<CharSequence>(), listener)
}
