package com.maysk.scan

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Защита PDF паролем и запретами; снятие пароля, если он известен. */
class ProtectActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        val body = screen("Защита PDF")

        val card = hbox(14).apply { background = cardBg(18) }
        card.addView(iconTile(R.drawable.ic_pdf, R.color.tile_red, 42))
        card.addView(text(src.name, 15f, bold = true), lp(0, WRAP, 1f).apply { leftMargin = dp(12) })
        body.addView(card)

        body.addView(sectionTitle("УСТАНОВИТЬ ПАРОЛЬ"))
        val pw = field("Пароль").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val pw2 = field("Повторите пароль").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        body.addView(pw)
        body.addView(pw2, lp().apply { topMargin = dp(8) })
        var noPrint = false
        var noCopy = false
        var noModify = true
        body.addView(listRow(R.drawable.ic_print, "Запретить печать", null, switchView(noPrint) { noPrint = it }), lp().apply { topMargin = dp(10) })
        body.addView(listRow(R.drawable.ic_copy, "Запретить копирование текста", null, switchView(noCopy) { noCopy = it }), lp().apply { topMargin = dp(8) })
        body.addView(listRow(R.drawable.ic_edit, "Запретить изменение", null, switchView(noModify) { noModify = it }), lp().apply { topMargin = dp(8) })
        body.addView(primaryButton("Защитить", R.drawable.ic_lock) {
            val p = pw.text.toString()
            when {
                p.length < 4 -> toast("Пароль — не меньше 4 символов")
                p != pw2.text.toString() -> toast("Пароли не совпадают")
                else -> run(src, "Ставлю пароль…", " (защищён)") { out -> PdfTools.protect(src, out, p, !noPrint, !noCopy, !noModify) }
            }
        }, lp().apply { topMargin = dp(14) })
        body.addView(text("Запомните пароль: без него файл не открыть.", 12.5f, R.color.text2).apply { setPadding(dp(4), dp(8), 0, 0) })

        body.addView(sectionTitle("СНЯТЬ ПАРОЛЬ"))
        val old = field("Текущий пароль файла").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        body.addView(old)
        body.addView(secondaryButton("Снять пароль", R.drawable.ic_unlock) {
            val p = old.text.toString()
            if (p.isEmpty()) toast("Введите пароль") else run(src, "Снимаю пароль…", " (без пароля)") { out -> PdfTools.unlock(src, p, out) }
        }, lp().apply { topMargin = dp(10) })
    }

    private fun run(src: File, msg: String, suffix: String, work: (File) -> Unit) {
        lifecycleScope.launch {
            showProgress(msg)
            try {
                val out = Files.newDoc(this@ProtectActivity, Files.baseName(src.name).replace(" (защищён)", "").replace(" (без пароля)", "") + suffix, "pdf")
                withContext(Dispatchers.IO) { work(out); Files.exportCopy(this@ProtectActivity, out) }
                History.add(this@ProtectActivity, Kind.PDF, out)
                hideProgress()
                toast("Готово: ${out.name}")
                if (suffix.contains("без")) startActivity(Intent(this@ProtectActivity, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, out.absolutePath))
                else share(listOf(out), "application/pdf")
                finish()
            } catch (e: Throwable) {
                hideProgress()
                fail(if (e.message?.contains("password", true) == true) IllegalStateException("Неверный пароль") else e)
            }
        }
    }
}
