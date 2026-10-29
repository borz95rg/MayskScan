package com.maysk.scan

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Приветственный экран: волк, «Maysk Scan», подготовка языков распознавания. */
class SplashActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0B1D38.toInt(), 0xFF050B15.toInt()))
        }
        val box = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(ImageView(this).apply { setImageResource(R.drawable.wolf) }, LinearLayout.LayoutParams(dp(170), dp(170)))
        val title = hbox().apply { gravity = Gravity.CENTER }
        title.addView(text("Maysk ", 34f, R.color.white_fixed, heavy = true))
        title.addView(text("Scan", 34f, R.color.accent, heavy = true))
        box.addView(title, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        box.addView(text("Умный сканер. Документы. Текст. PDF. QR.", 14f, R.color.text2).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
        }
        box.addView(bar, LinearLayout.LayoutParams(dp(140), dp(6)).apply { topMargin = dp(36) })
        root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                try { Ocr.ensureData(applicationContext) } catch (e: Exception) {}
                Files.clearCache(applicationContext)
            }
            TextIndex.scheduleMissing(applicationContext)
            val left = 900 - (System.currentTimeMillis() - started)
            if (left > 0) delay(left)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}

const val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
