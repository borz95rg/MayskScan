package com.maysk.scan

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DummyCtx.app = applicationContext
        // Тёмная тема по умолчанию, светлую можно включить в настройках
        AppCompatDelegate.setDefaultNightMode(
            if (Prefs(this).darkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        PDFBoxResourceLoader.init(applicationContext)
    }
}
