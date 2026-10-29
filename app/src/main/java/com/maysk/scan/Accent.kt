package com.maysk.scan

import android.content.Context
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate

/** Цвет акцента (кнопки, значки, подсветка) и режим темы — меняются в настройках. */
object Accent {
    class Option(val name: String, val color: Int)

    val COLORS = listOf(
        Option("Синий", 0xFF1E88FF.toInt()),
        Option("Бирюзовый", 0xFF00B8A9.toInt()),
        Option("Фиолетовый", 0xFF8B5CF6.toInt()),
        Option("Зелёный", 0xFF22C55E.toInt()),
        Option("Оранжевый", 0xFFFF8A1E.toInt()),
        Option("Розовый", 0xFFF0508C.toInt()),
        Option("Красный", 0xFFEF4444.toInt()),
    )

    @Volatile private var index = -1

    private fun idx(ctx: Context): Int {
        if (index < 0) index = Prefs(ctx).accent.coerceIn(0, COLORS.size - 1)
        return index
    }

    fun set(ctx: Context, i: Int) {
        Prefs(ctx).accent = i
        index = i
    }

    fun color(ctx: Context): Int = COLORS[idx(ctx)].color

    fun dark(ctx: Context): Int {
        val c = color(ctx)
        return Color.rgb((Color.red(c) * 0.78f).toInt(), (Color.green(c) * 0.78f).toInt(), (Color.blue(c) * 0.82f).toInt())
    }

    /** Светящаяся рамка карточек: акцент, чуть приглушённый. */
    fun glow(ctx: Context): Int {
        val c = color(ctx)
        val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.argb(255, (Color.red(c) * 0.8f).toInt(), (Color.green(c) * 0.8f).toInt(), (Color.blue(c) * 0.85f).toInt())
        else Color.argb(255, (Color.red(c) + 255 * 2) / 3, (Color.green(c) + 255 * 2) / 3, (Color.blue(c) + 255 * 2) / 3)
    }

    /** Полупрозрачный акцент для подсветки выбранного. */
    fun soft(ctx: Context): Int = (color(ctx) and 0x00FFFFFF) or 0x33000000

    val THEMES = listOf("Тёмная", "Светлая", "Как в системе")

    fun applyTheme(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }
}
