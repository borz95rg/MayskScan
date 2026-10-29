package com.maysk.scan

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Принимает файлы из других приложений: «Открыть с помощью» и «Поделиться». */
class OpenActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Maysk Scan", back = true)
        val uris = incoming()
        if (uris.isEmpty()) { toast("Файл не получен"); finish(); return }
        lifecycleScope.launch {
            showProgress("Открываю…")
            try {
                val files = withContext(Dispatchers.IO) { uris.map { Files.copyToCache(this@OpenActivity, it) } }
                hideProgress()
                route(files)
            } catch (e: Throwable) {
                hideProgress()
                toast("Не удалось открыть файл")
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun incoming(): List<Uri> {
        val i = intent
        return when (i.action) {
            Intent.ACTION_VIEW -> listOfNotNull(i.data)
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE -> (
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            ) ?: emptyList()
            else -> listOfNotNull(i.data)
        }
    }

    private fun isPdf(f: File): Boolean = try {
        f.inputStream().use { s -> val b = ByteArray(4); s.read(b) == 4 && String(b) == "%PDF" }
    } catch (e: Exception) { false }

    private fun route(files: List<File>) {
        val pdfs = files.filter { isPdf(it) }
        val images = files - pdfs.toSet()
        when {
            pdfs.size == 1 && images.isEmpty() -> go(Intent(this, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, pdfs[0].absolutePath))
            pdfs.size > 1 && images.isEmpty() -> {
                toast("Открываю первый файл. Для объединения — инструмент «Объединить»")
                go(Intent(this, PdfViewerActivity::class.java).putExtra(EXTRA_FILE, pdfs[0].absolutePath))
            }
            images.isNotEmpty() -> {
                val paths = images.map { it.absolutePath }.toTypedArray()
                choose("Что сделать с ${if (paths.size == 1) "фото" else "${paths.size} фото"}?", listOf(
                    "Распознать текст",
                    "Сделать PDF (как скан)",
                    "Пакетная обработка",
                    "Сканировать QR / штрихкод",
                )) { which ->
                    when (which) {
                        0 -> go(Intent(this, OcrActivity::class.java).putExtra("images", paths))
                        1 -> go(Intent(this, ScanActivity::class.java).putExtra("images", paths))
                        2 -> go(Intent(this, BatchActivity::class.java).putExtra("images", paths))
                        else -> go(Intent(this, QrActivity::class.java).putExtra("image", paths[0]))
                    }
                }
            }
            else -> { toast("Этот тип файла не поддерживается"); finish() }
        }
    }

    private fun go(i: Intent) {
        startActivity(i)
        finish()
    }
}
