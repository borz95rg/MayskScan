package com.maysk.scan

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Calendar
import java.util.Locale

/** Библиотека офисных документов: Word, Excel, PowerPoint. Хранится в общей истории приложения. */
object Docs {
    val OFFICE_EXT = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "doc", "xls", "ppt")

    enum class Type(val title: String, val letter: String, val color: Int, val ext: String) {
        WORD("Word", "W", 0xFF2B7BFF.toInt(), "docx"),
        EXCEL("Excel", "X", 0xFF1FB36A.toInt(), "xlsx"),
        POWERPOINT("PowerPoint", "P", 0xFFF2762E.toInt(), "pptx"),
    }

    class Template(val type: Type, val title: String, val hint: String, val baseName: String)

    val TEMPLATES = listOf(
        Template(Type.WORD, "Заявление", "Шапка, текст, подпись", "Заявление"),
        Template(Type.WORD, "Резюме", "Опыт, образование, навыки", "Резюме"),
        Template(Type.WORD, "Отчёт", "Введение, результаты, выводы", "Отчёт"),
        Template(Type.EXCEL, "План / факт", "Месяц, план, факт, отклонение", "План_факт"),
        Template(Type.EXCEL, "Бюджет", "Статьи доходов и расходов", "Бюджет"),
        Template(Type.POWERPOINT, "Презентация", "Титул, 3 слайда, итоги", "Презентация"),
    )

    fun typeOf(name: String): Type? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "docx", "doc", "odt" -> Type.WORD
        "xlsx", "xls", "ods" -> Type.EXCEL
        "pptx", "ppt", "odp" -> Type.POWERPOINT
        else -> null
    }

    fun isOffice(e: Entry): Boolean = e.kind != Kind.QR && e.file.extension.lowercase(Locale.ROOT) in OFFICE_EXT

    fun list(ctx: Context): List<Entry> = History.all(ctx).filter { isOffice(it) }

    /** Адрес исходного файла, если документ открыт из другого места (Диск, Загрузки…). */
    fun sourceUri(e: Entry): String? = e.extra.takeIf { it.startsWith("uri:") }?.removePrefix("uri:")

    /** «Облако» — если файл лежит у облачного провайдера (Google Диск и т.п.). */
    fun isCloud(e: Entry): Boolean {
        val u = sourceUri(e) ?: return false
        val host = runCatching { Uri.parse(u).authority.orEmpty() }.getOrDefault("").lowercase(Locale.ROOT)
        return host.contains("docs.storage") || host.contains("drive") || host.contains("dropbox") ||
            host.contains("onedrive") || host.contains("yandex") || host.contains("mail.ru") || host.contains("cloud")
    }

    private fun fileNameFor(base: String, ext: String): String = Files.sanitize(base).removeSuffix(".$ext")

    /** Новый пустой документ. */
    fun create(ctx: Context, type: Type, name: String): Entry {
        val file = Files.newDoc(ctx, fileNameFor(name, type.ext), type.ext)
        file.outputStream().use { out ->
            when (type) {
                Type.WORD -> OfficeWriter.docx("\u200B", out)
                Type.EXCEL -> OfficeWriter.xlsx(listOf("Лист1" to List(20) { List(8) { " " } }), out)
                Type.POWERPOINT -> OfficeWriter.pptxText(listOf("Заголовок\nПодзаголовок"), out)
            }
        }
        return History.add(ctx, Kind.CONVERT, file, file.name)
    }

    fun createFromTemplate(ctx: Context, t: Template): Entry {
        val file = Files.newDoc(ctx, t.baseName, t.type.ext)
        file.outputStream().use { out ->
            when (t.title) {
                "Заявление" -> OfficeWriter.docx(
                    "Директору ____________________\nот ____________________\n\nЗАЯВЛЕНИЕ\n\nПрошу ____________________.\n\nДата: ____________    Подпись: ____________",
                    out)
                "Резюме" -> OfficeWriter.docx(
                    "Фамилия Имя Отчество\nТелефон · Почта · Город\n\nОпыт работы\nДолжность, компания, годы\n\nОбразование\nУчебное заведение, специальность, год\n\nНавыки\nПеречислите ключевые навыки",
                    out)
                "Отчёт" -> OfficeWriter.docx(
                    "Отчёт о проделанной работе\n\nВведение\nЦель и период отчёта.\n\nРезультаты\nЧто сделано.\n\nВыводы\nИтоги и планы.",
                    out)
                "План / факт" -> OfficeWriter.xlsx(listOf("Лист1" to listOf(
                    listOf("Месяц", "План", "Факт", "Отклонение"),
                    listOf("Январь", "100", "105", "5"),
                    listOf("Февраль", "120", "118", "-2"),
                    listOf("Март", "130", "135", "5"),
                    listOf("Апрель", "140", "142", "2"),
                    listOf("Май", "150", "148", "-2"),
                    listOf("Июнь", "160", "162", "2"),
                )), out)
                "Бюджет" -> OfficeWriter.xlsx(listOf("Лист1" to listOf(
                    listOf("Статья", "Сумма"),
                    listOf("Доходы", ""),
                    listOf("Зарплата", "0"),
                    listOf("Прочее", "0"),
                    listOf("Расходы", ""),
                    listOf("Жильё", "0"),
                    listOf("Питание", "0"),
                    listOf("Транспорт", "0"),
                )), out)
                else -> OfficeWriter.pptxText(listOf(
                    "Название презентации\nПодзаголовок и автор",
                    "О чём пойдёт речь\n• Пункт 1\n• Пункт 2\n• Пункт 3",
                    "Основная часть\n• Ключевая мысль\n• Факты и цифры",
                    "Итоги\n• Главный вывод\n• Следующие шаги",
                ), out)
            }
        }
        return History.add(ctx, Kind.CONVERT, file, file.name)
    }

    fun duplicate(ctx: Context, e: Entry): Entry {
        val ext = e.file.extension
        val copy = Files.newDoc(ctx, Files.baseName(e.file.name) + " — копия", ext)
        e.file.copyTo(copy, overwrite = true)
        return History.add(ctx, Kind.CONVERT, copy, copy.name)
    }

    /** Переименование: меняет и имя в списке, и сам файл (расширение сохраняется). Возвращает false, если имя занято. */
    fun rename(ctx: Context, e: Entry, newBase: String): Boolean {
        val ext = e.file.extension
        val clean = Files.sanitize(newBase).removeSuffix(".$ext")
        if (clean.isBlank()) return false
        val target = File(e.file.parentFile, "$clean.$ext")
        if (target.absolutePath == e.path) return true
        if (target.exists()) return false
        if (!e.file.renameTo(target)) return false
        History.renameFile(ctx, e, target)
        return true
    }

    /**
     * Регистрирует документ, открытый с телефона или из облака: копия лежит в приложении,
     * изменения записываются и в исходный файл.
     */
    fun registerExternal(ctx: Context, cached: File, uri: Uri): Entry {
        val tag = "uri:$uri"
        val existing = History.all(ctx).firstOrNull { it.extra == tag && it.file.exists() }
        if (existing != null) {
            runCatching { cached.copyTo(existing.file, overwrite = true) }
            return existing
        }
        val ext = cached.extension.lowercase(Locale.ROOT)
        val dest = Files.newDoc(ctx, Files.baseName(cached.name), ext)
        cached.copyTo(dest, overwrite = true)
        return History.add(ctx, Kind.CONVERT, dest, dest.name, tag)
    }

    // ---------- Подписи ----------

    fun dateLabel(ms: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val time = java.text.SimpleDateFormat("HH:mm", Locale("ru")).format(java.util.Date(ms))
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "Сегодня, $time"
            sameYear && dayDiff == 1 -> "Вчера, $time"
            else -> java.text.SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(java.util.Date(ms))
        }
    }
}
