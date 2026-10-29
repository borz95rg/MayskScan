package com.maysk.scan

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Виджет на рабочий стол: Скан, Текст из фото, QR, Удостоверение — в одно касание. */
class ScanWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_scan)
        views.setOnClickPendingIntent(R.id.w_scan, open(ctx, ScanActivity::class.java, 1))
        views.setOnClickPendingIntent(R.id.w_ocr, open(ctx, OcrActivity::class.java, 2))
        views.setOnClickPendingIntent(R.id.w_qr, open(ctx, QrActivity::class.java, 3))
        views.setOnClickPendingIntent(R.id.w_id, open(ctx, IdCardActivity::class.java, 4))
        for (id in ids) manager.updateAppWidget(id, views)
    }

    private fun open(ctx: Context, cls: Class<*>, code: Int): PendingIntent {
        val main = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val target = Intent(ctx, cls)
        // Под экраном инструмента — главный экран, чтобы «Назад» вела в приложение
        return PendingIntent.getActivities(ctx, code, arrayOf(main, target), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
