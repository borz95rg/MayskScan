package com.maysk.scan

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Files {

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "html", "htm" -> "text/html"
            "rtf" -> "application/rtf"
            "csv" -> "text/csv"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "zip" -> "application/zip"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }

    fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    fun share(ctx: Context, files: List<File>, mime: String = mimeOf(files.firstOrNull()?.name ?: "")) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(ctx, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = mime
        intent.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "Поделиться"))
    }

    fun shareText(ctx: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(intent, "Поделиться"))
    }

    /** Открыть файл другим приложением (Word, Excel и т.п.). */
    fun openWith(ctx: Context, f: File) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, f), mimeOf(f.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(intent, "Открыть с помощью"))
        } catch (e: Exception) {
            ctx.toast("Нет приложения для этого файла")
        }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "file").name
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Копирует выбранный файл во временную папку приложения (с исходным именем). */
    fun copyToCache(ctx: Context, uri: Uri): File {
        val name = sanitize(displayName(ctx, uri))
        val dir = File(ctx.cacheDir, "in_${System.nanoTime()}").apply { mkdirs() }
        val f = File(dir, name)
        ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("Не удалось открыть файл")
        return f
    }

    fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "file" }

    fun baseName(name: String): String = name.substringBeforeLast('.', name)

    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    /** Новый файл в папке документов: «имя.ext», «имя (2).ext»… */
    fun newDoc(ctx: Context, base: String, ext: String): File {
        val dir = History.docsDir(ctx)
        val clean = sanitize(base)
        var f = File(dir, "$clean.$ext")
        var n = 2
        while (f.exists()) {
            f = File(dir, "$clean ($n).$ext"); n++
        }
        return f
    }

    fun tempFile(ctx: Context, ext: String): File =
        File(ctx.cacheDir, "tmp_${System.nanoTime()}.$ext")

    /** Копия в папку, выбранную в настройках (если выбрана). */
    fun exportCopy(ctx: Context, f: File) {
        val tree = Prefs(ctx).exportTree
        if (tree.isEmpty()) return
        try {
            val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: return
            val out = dir.createFile(mimeOf(f.name), f.name) ?: return
            ctx.contentResolver.openOutputStream(out.uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        } catch (e: Exception) {
            // папку могли удалить — копия просто не сохранится
        }
    }

    fun clearCache(ctx: Context) {
        ctx.cacheDir.listFiles()?.forEach { if (it.name.startsWith("in_") || it.name.startsWith("tmp_") || it.name.startsWith("photo_")) it.deleteRecursively() }
    }
}
