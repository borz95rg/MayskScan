package com.maysk.scan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Настройки приложения. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var darkTheme: Boolean
        get() = p.getBoolean("dark", true)
        set(v) = p.edit().putBoolean("dark", v).apply()

    /** Языки распознавания: "auto" или коды Tesseract через «+», например "rus+eng". */
    var ocrLang: String
        get() = p.getString("ocr_lang", "auto") ?: "auto"
        set(v) = p.edit().putString("ocr_lang", v).apply()

    var autoEnhance: Boolean
        get() = p.getBoolean("auto_enhance", true)
        set(v) = p.edit().putBoolean("auto_enhance", v).apply()

    var tables: Boolean
        get() = p.getBoolean("tables", true)
        set(v) = p.edit().putBoolean("tables", v).apply()

    /** Папка для копий (выбирается через системное окно), пусто — только в приложении. */
    var exportTree: String
        get() = p.getString("export_tree", "") ?: ""
        set(v) = p.edit().putString("export_tree", v).apply()

    var exportTreeName: String
        get() = p.getString("export_tree_name", "") ?: ""
        set(v) = p.edit().putString("export_tree_name", v).apply()

    var firstRunDone: Boolean
        get() = p.getBoolean("first_run", false)
        set(v) = p.edit().putBoolean("first_run", v).apply()

    var tessDataVersion: Int
        get() = p.getInt("tess_ver", 0)
        set(v) = p.edit().putInt("tess_ver", v).apply()

    fun lastPage(key: String): Int = p.getInt("page_$key", 0)
    fun setLastPage(key: String, page: Int) = p.edit().putInt("page_$key", page).apply()

    fun bookmarks(key: String): Set<Int> =
        (p.getString("bm_$key", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }.toSortedSet()

    fun setBookmarks(key: String, pages: Set<Int>) = p.edit().putString("bm_$key", pages.sorted().joinToString(",")).apply()

    /** Называть сканы по тексту документа. */
    var autoName: Boolean
        get() = p.getBoolean("auto_name", true)
        set(v) = p.edit().putBoolean("auto_name", v).apply()

    /** Тема: 0 — тёмная, 1 — светлая, 2 — как в системе. */
    var themeMode: Int
        get() = p.getInt("theme_mode", if (p.getBoolean("dark", true)) 0 else 1)
        set(v) = p.edit().putInt("theme_mode", v).apply()

    /** Цвет акцента (индекс в Accent.COLORS). */
    var accent: Int
        get() = p.getInt("accent", 0)
        set(v) = p.edit().putInt("accent", v).apply()

    /** Язык интерфейса: ru, kk, en. */
    var uiLang: String
        get() = p.getString("ui_lang", "ru") ?: "ru"
        set(v) = p.edit().putString("ui_lang", v).apply()

    /** Блокировка: хэш PIN-кода (пусто — выключена) и вход по отпечатку. */
    var pinHash: String
        get() = p.getString("pin_hash", "") ?: ""
        set(v) = p.edit().putString("pin_hash", v).apply()

    var biometric: Boolean
        get() = p.getBoolean("biometric", true)
        set(v) = p.edit().putBoolean("biometric", v).apply()

    var nightPdf: Boolean
        get() = p.getBoolean("night_pdf", false)
        set(v) = p.edit().putBoolean("night_pdf", v).apply()
}

/** Тип записи в истории. */
enum class Kind(val title: String) {
    SCAN("Сканы"), OCR("OCR"), PDF("PDF"), QR("QR"), CONVERT("Конвертации"), IMAGE("Изображения")
}

data class Entry(
    val id: Long,
    val kind: Kind,
    var name: String,
    val path: String,
    val created: Long,
    var favorite: Boolean = false,
    /** Текст QR-кода или короткое описание. */
    val extra: String = "",
) {
    val file: File get() = File(path)
    val size: Long get() = file.takeIf { it.exists() }?.length() ?: 0L
}

/**
 * История: все сканы, PDF, тексты и QR. Хранится в files/history.json,
 * сами файлы — в files/docs.
 */
object History {
    private const val FILE = "history.json"
    private var cache: MutableList<Entry>? = null

    fun docsDir(ctx: Context): File = File(ctx.filesDir, "docs").apply { mkdirs() }

    @Synchronized
    fun all(ctx: Context): List<Entry> = load(ctx).sortedByDescending { it.created }

    @Synchronized
    fun add(ctx: Context, kind: Kind, file: File, name: String = file.name, extra: String = ""): Entry {
        val list = load(ctx)
        val e = Entry(System.currentTimeMillis() + list.size, kind, name, file.absolutePath, System.currentTimeMillis(), false, extra)
        list.add(e)
        save(ctx)
        TextIndex.schedule(ctx, e)
        return e
    }

    @Synchronized
    fun update(ctx: Context, e: Entry) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == e.id }
        if (i >= 0) list[i] = e
        save(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, e: Entry) {
        val list = load(ctx)
        list.removeAll { it.id == e.id }
        TextIndex.delete(ctx, e)
        // Файл удаляем, только если на него больше не ссылаются
        if (list.none { it.path == e.path }) File(e.path).delete()
        save(ctx)
    }

    @Synchronized
    fun findByPath(ctx: Context, path: String): Entry? = load(ctx).firstOrNull { it.path == path }

    private fun load(ctx: Context): MutableList<Entry> {
        cache?.let { return it }
        val f = File(ctx.filesDir, FILE)
        val list = ArrayList<Entry>()
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val e = Entry(
                        o.getLong("id"),
                        runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.PDF),
                        o.getString("name"),
                        o.getString("path"),
                        o.getLong("created"),
                        o.optBoolean("fav"),
                        o.optString("extra"),
                    )
                    if (e.kind == Kind.QR || File(e.path).exists()) list.add(e)
                }
            } catch (e: Exception) {
                // повреждённый файл истории — начинаем заново
            }
        }
        cache = list
        return list
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        for (e in cache ?: return) {
            arr.put(JSONObject().apply {
                put("id", e.id); put("kind", e.kind.name); put("name", e.name); put("path", e.path)
                put("created", e.created); put("fav", e.favorite); put("extra", e.extra)
            })
        }
        val f = File(ctx.filesDir, FILE)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }
}
