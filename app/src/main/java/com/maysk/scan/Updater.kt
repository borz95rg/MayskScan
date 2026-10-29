package com.maysk.scan

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка обновлений через облачную базу Maysk (как у Maysk TV).
 * Новая версия скачивается через браузер — приложение само ничего не устанавливает.
 */
object Updater {
    class Info(val versionCode: Int, val versionName: String, val url: String)

    fun installUrl(): String = "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/releases/MayskScan.apk"

    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_URL.isEmpty()) return@withContext null
        try {
            val url = URL("${BuildConfig.SUPABASE_URL}/rest/v1/app_settings?select=key,value&key=in.(scan_version_code,scan_version_name,scan_apk_url)")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
            val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(body)
            val map = HashMap<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("key")] = o.optString("value")
            }
            val code = map["scan_version_code"]?.toIntOrNull() ?: return@withContext null
            if (code <= BuildConfig.VERSION_CODE) return@withContext null
            Info(code, map["scan_version_name"] ?: "", map["scan_apk_url"]?.ifEmpty { null } ?: installUrl())
        } catch (e: Exception) {
            null // нет интернета — просто молчим
        }
    }
}

/** Печать PDF через системную печать Android (принтер, «Сохранить как PDF»). */
object Printer {
    fun print(ctx: Context, pdf: File) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                val info = PrintDocumentInfo.Builder(pdf.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
                cb.onLayoutFinished(info, true)
            }

            override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
                try {
                    pdf.inputStream().use { input -> FileOutputStream(dest.fileDescriptor).use { input.copyTo(it) } }
                    cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    cb.onWriteFailed(e.message)
                }
            }
        }
        pm.print(pdf.nameWithoutExtension, adapter, null)
    }
}
