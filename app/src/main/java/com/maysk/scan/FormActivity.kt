package com.maysk.scan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Заполнение PDF-бланков (поля формы): текст, галочки, выбор из списка. */
class FormActivity : BaseActivity() {

    private enum class Type { TEXT, CHECK, RADIO, CHOICE }

    private class Field(val name: String, val label: String, val type: Type, var value: String, val options: List<String>, val multiline: Boolean)

    private lateinit var src: File
    private val fields = ArrayList<Field>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        src = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        val body = screen("Заполнить форму")
        lifecycleScope.launch {
            showProgress("Ищу поля в документе…")
            try {
                val list = withContext(Dispatchers.IO) { read(src) }
                hideProgress()
                fields.addAll(list)
                build(body)
            } catch (e: Throwable) { fail(e) }
        }
    }

    private fun read(f: File): List<Field> {
        PdfTools.open(f).use { doc ->
            val form = doc.documentCatalog.acroForm ?: return emptyList()
            return form.fieldTree.mapNotNull { fl -> toField(fl) }
        }
    }

    private fun toField(f: PDField): Field? {
        val label = (f.alternateFieldName?.takeIf { it.isNotBlank() } ?: f.partialName ?: f.fullyQualifiedName ?: "Поле").trim()
        return when (f) {
            is PDTextField -> if (f.isReadOnly) null else Field(f.fullyQualifiedName, label, Type.TEXT, f.value ?: "", emptyList(), f.isMultiline)
            is PDCheckBox -> Field(f.fullyQualifiedName, label, Type.CHECK, if (f.isChecked) "1" else "", emptyList(), false)
            is PDRadioButton -> Field(f.fullyQualifiedName, label, Type.RADIO, f.value ?: "", f.onValues.toList().filter { it != "Off" }, false)
            is PDChoice -> Field(f.fullyQualifiedName, label, Type.CHOICE, f.value.firstOrNull() ?: "", f.optionsDisplayValues.ifEmpty { f.options }, false)
            else -> null
        }
    }

    private fun build(body: LinearLayout) {
        if (fields.isEmpty()) {
            body.addView(text("В этом PDF нет полей для заполнения.\n\nЕсли это скан бланка — впишите текст поверх с помощью инструмента «Текст и рисунок».", 15f, R.color.text2))
            body.addView(primaryButton("Открыть «Текст и рисунок»", R.drawable.ic_edit) {
                startActivity(Intent(this, PdfAnnotateActivity::class.java).putExtra(EXTRA_FILE, src.absolutePath).putExtra("mode", "draw"))
                finish()
            }, lp().apply { topMargin = dp(16) })
            return
        }
        body.addView(text("Найдено полей: ${fields.size}. Заполните нужные и нажмите «Сохранить».", 13.5f, R.color.text2))
        fields.forEach { f ->
            body.addView(text(f.label, 13f, R.color.text2, bold = true).apply { setPadding(dp(4), dp(14), 0, dp(6)) })
            when (f.type) {
                Type.TEXT -> {
                    val e: EditText = field("", f.value, multiline = f.multiline)
                    e.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) { f.value = s?.toString() ?: "" }
                    })
                    body.addView(e)
                }
                Type.CHECK -> body.addView(listRow(R.drawable.ic_check, "Отметить", null, switchView(f.value.isNotEmpty()) { f.value = if (it) "1" else "" }))
                Type.RADIO, Type.CHOICE -> {
                    val (sc, row) = chipRow()
                    fun fill() {
                        row.removeAllViews()
                        f.options.forEach { o -> row.addView(chip(o, o == f.value) { f.value = o; fill() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) }) }
                    }
                    fill()
                    body.addView(sc)
                }
            }
        }
        var flatten = false
        body.addView(listRow(R.drawable.ic_lock, "Запретить дальнейшие правки", "Поля станут обычным текстом", switchView(false) { flatten = it }), lp().apply { topMargin = dp(18) })
        body.addView(primaryButton("Сохранить", R.drawable.ic_save) { save(flatten) }, lp().apply { topMargin = dp(12) })
        body.addView(View(this), lp(MATCH, dp(40)))
    }

    private fun save(flatten: Boolean) {
        lifecycleScope.launch {
            showProgress("Сохраняю…")
            try {
                val out = Files.newDoc(this@FormActivity, Files.baseName(src.name) + " (заполнено)", "pdf")
                withContext(Dispatchers.IO) {
                    PdfTools.open(src).use { doc -> fill(this@FormActivity, doc, fields, flatten); doc.save(out) }
                    Files.exportCopy(this@FormActivity, out)
                }
                History.add(this@FormActivity, Kind.PDF, out)
                hideProgress()
                TrDialog(this@FormActivity).setTitle("Готово!")
                    .setMessage("«${out.name}» сохранён в Истории.")
                    .setPositiveButton("Открыть") { _, _ -> startActivity(Intent(this@FormActivity, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, out.absolutePath)); finish() }
                    .setNeutralButton("Поделиться") { _, _ -> share(listOf(out), "application/pdf") }
                    .setNegativeButton("Закрыть", null).show()
            } catch (e: Throwable) { fail(e) }
        }
    }

    companion object {
        private fun fill(ctx: Context, doc: PDDocument, fields: List<Field>, flatten: Boolean) {
            val form = doc.documentCatalog.acroForm ?: return
            // Шрифт с кириллицей и казахскими буквами — иначе русский текст в полях не встанет
            val font = ctx.assets.open("fonts/NotoSans-Regular.ttf").use { PDType0Font.load(doc, it, false) }
            val dr = form.defaultResources ?: PDResources().also { form.defaultResources = it }
            val fontName: COSName = dr.add(font)
            var needAppearances = false
            for (f in fields) {
                val pf = form.getField(f.name) ?: continue
                try {
                    when (pf) {
                        is PDTextField -> {
                            val da = pf.defaultAppearance ?: ""
                            val size = Regex("([0-9.]+)\\s+Tf").find(da)?.groupValues?.get(1) ?: "0"
                            pf.defaultAppearance = "/${fontName.name} $size Tf 0 g"
                            try { pf.value = f.value } catch (e: Exception) {
                                pf.cosObject.setString(COSName.V, f.value); needAppearances = true
                            }
                        }
                        is PDCheckBox -> if (f.value.isNotEmpty()) pf.check() else pf.unCheck()
                        is PDRadioButton -> if (f.value.isNotEmpty()) pf.value = f.value
                        is PDChoice -> if (f.value.isNotEmpty()) pf.setValue(f.value)
                    }
                } catch (e: Exception) {
                    // поле с необычным оформлением — пропускаем, остальные сохраняем
                }
            }
            if (needAppearances) form.needAppearances = true
            if (flatten && !needAppearances) try { form.flatten() } catch (e: Exception) {}
        }
    }
}
