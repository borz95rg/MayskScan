package com.maysk.scan

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Резервная копия всей Истории одним файлом .zip: документы, подписи, текст для поиска, настройки.
 * Файл можно сохранить на телефон, отправить себе в Telegram/на почту и восстановить на новом телефоне.
 * PIN-код блокировки в копию не попадает.
 */
object Backup {
    private const val VERSION = 1

    class Result(val entries: Int, val bytes: Long)

    fun create(ctx: Context, out: File, onFile: (Int, Int) -> Unit = { _, _ -> }): Result {
        val entries = History.all(ctx).filter { it.kind == Kind.QR || it.file.exists() }
        val signs = Signatures.all(ctx)
        val index = File(ctx.filesDir, "index")
        val total = entries.size + signs.size
        var done = 0
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            fun putFile(name: String, f: File) {
                zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            val arr = JSONArray()
            for (e in entries) arr.put(History.toJson(e).put("file", e.file.name))
            put("manifest.json", JSONObject().put("app", "Maysk Scan").put("version", VERSION)
                .put("created", System.currentTimeMillis()).put("entries", entries.size).toString().toByteArray())
            put("history.json", arr.toString().toByteArray())
            val prefs = Prefs(ctx)
            put("settings.json", JSONObject()
                .put("ocrLang", prefs.ocrLang).put("autoEnhance", prefs.autoEnhance).put("tables", prefs.tables)
                .put("autoName", prefs.autoName).put("autoCrop", prefs.autoCrop).put("themeMode", prefs.themeMode)
                .put("accent", prefs.accent).put("uiLang", prefs.uiLang).toString().toByteArray())
            val written = HashSet<String>()
            for (e in entries) {
                onFile(++done, total)
                if (e.kind == Kind.QR && !e.file.exists()) continue
                if (written.add(e.file.name)) putFile("docs/${e.file.name}", e.file)
                val idx = File(index, "${e.id}.txt")
                if (idx.exists()) putFile("index/${idx.name}", idx)
            }
            for (s in signs) { onFile(++done, total); putFile("signatures/${s.name}", s) }
        }
        return Result(entries.size, out.length())
    }

    /** Восстановление: добавляет недостающие документы, не удаляя текущие. Возвращает число добавленных записей. */
    fun restore(ctx: Context, uri: Uri, onFile: (Int) -> Unit = {}): Int {
        val docs = History.docsDir(ctx)
        val signDir = Signatures.dir(ctx)
        val indexDir = File(ctx.filesDir, "index").apply { mkdirs() }
        val renamed = HashMap<String, File>()
        var historyJson: String? = null
        var settingsJson: String? = null
        var isBackup = false
        var n = 0
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // Только имя файла — защита от путей вида ../../
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    if (name.isEmpty() || name.startsWith(".")) continue
                    when {
                        entry.name == "manifest.json" -> { isBackup = zip.readBytes().toString(Charsets.UTF_8).contains("Maysk Scan") }
                        entry.name == "history.json" -> historyJson = zip.readBytes().toString(Charsets.UTF_8)
                        entry.name == "settings.json" -> settingsJson = zip.readBytes().toString(Charsets.UTF_8)
                        entry.name.startsWith("docs/") -> {
                            var f = File(docs, name)
                            if (f.exists()) {
                                val base = name.substringBeforeLast('.')
                                val ext = name.substringAfterLast('.', "")
                                var i = 2
                                while (f.exists()) f = File(docs, "$base ($i)${if (ext.isNotEmpty()) ".$ext" else ""}").also { i++ }
                            }
                            f.outputStream().use { zip.copyTo(it) }
                            renamed[name] = f
                            onFile(++n)
                        }
                        entry.name.startsWith("signatures/") -> { val f = File(signDir, name); if (!f.exists()) f.outputStream().use { zip.copyTo(it) } }
                        entry.name.startsWith("index/") -> { val f = File(indexDir, name); if (!f.exists()) f.outputStream().use { zip.copyTo(it) } }
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalStateException("Не удалось открыть файл")
        if (!isBackup || historyJson == null) throw IllegalStateException("Это не резервная копия Maysk Scan")

        val arr = JSONArray(historyJson)
        val list = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val file = renamed[o.optString("file")]
            val kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.PDF)
            if (file == null && kind != Kind.QR) continue
            o.put("path", file?.absolutePath ?: File(docs, o.optString("file")).absolutePath)
            list.add(History.fromJson(o))
        }
        settingsJson?.let { s ->
            try {
                val o = JSONObject(s)
                val p = Prefs(ctx)
                p.ocrLang = o.optString("ocrLang", p.ocrLang)
                p.autoEnhance = o.optBoolean("autoEnhance", p.autoEnhance)
                p.tables = o.optBoolean("tables", p.tables)
                p.autoName = o.optBoolean("autoName", p.autoName)
                p.autoCrop = o.optBoolean("autoCrop", p.autoCrop)
            } catch (e: Exception) {}
        }
        return History.importAll(ctx, list)
    }
}
