package com.maysk.scan

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DummyCtx.app = applicationContext
        // Тёмная тема по умолчанию, светлую можно включить в настройках
        Accent.applyTheme(Prefs(this).themeMode)
        I18n.init(this)
        PDFBoxResourceLoader.init(applicationContext)
        Lock.init(this)
    }
}
