package com.maysk.scan

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.security.MessageDigest

/** Блокировка входа: PIN-код и отпечаток пальца. Запирается, если приложение свернули больше чем на 30 секунд. */
object Lock {
    @Volatile var unlocked = false
    private var started = 0
    private var leftAt = 0L
    private const val GRACE_MS = 30_000L

    fun init(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (started == 0 && leftAt > 0 && System.currentTimeMillis() - leftAt > GRACE_MS) unlocked = false
                started++
            }
            override fun onActivityStopped(a: Activity) {
                started = maxOf(0, started - 1)
                if (started == 0) leftAt = System.currentTimeMillis()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    fun enabled(ctx: Context) = Prefs(ctx).pinHash.isNotEmpty()
    fun needed(ctx: Context) = enabled(ctx) && !unlocked

    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("maysk-scan:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun biometricAvailable(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * Экран PIN-кода. Режимы:
 * обычный — вход в приложение; "setup" — придумать новый PIN; "verify" — подтвердить PIN (например, чтобы выключить блокировку).
 */
class LockActivity : BaseActivity() {

    private var entered = ""
    private var first: String? = null
    private lateinit var titleText: TextView
    private lateinit var dots: LinearLayout
    private val setup get() = intent.getBooleanExtra("setup", false)
    private val verify get() = intent.getBooleanExtra("verify", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(48), dp(24), dp(24)) }
        root.addView(ImageView(this).apply { setImageResource(R.drawable.wolf) }, LinearLayout.LayoutParams(dp(96), dp(96)))
        titleText = text(if (setup) "Придумайте PIN-код из 4 цифр" else "Введите PIN-код", 18f, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(titleText, lp(WRAP, WRAP).apply { topMargin = dp(18) })
        dots = hbox().apply { gravity = Gravity.CENTER }
        root.addView(dots, lp(WRAP, WRAP).apply { topMargin = dp(20) })
        root.addView(View(this), lp(MATCH, 0, 1f))

        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del")
        keys.chunked(3).forEach { rowKeys ->
            val row = hbox().apply { gravity = Gravity.CENTER }
            rowKeys.forEach { k -> row.addView(key(k), LinearLayout.LayoutParams(dp(78), dp(78)).apply { setMargins(dp(10), dp(8), dp(10), dp(8)) }) }
            root.addView(row)
        }
        if (setup || verify) {
            root.addView(text("Отмена", 15f, R.color.text2).apply {
                setPadding(dp(16), dp(16), dp(16), dp(8))
                setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
            })
        }
        setContentView(root)
        drawDots()
        if (!setup && Prefs(this).biometric && Lock.biometricAvailable(this)) root.post { askBiometric() }
    }

    private fun key(k: String): View {
        val box = FrameLayout(this).apply { background = clickable(rounded(col(R.color.card), 39, col(R.color.stroke)), 39) }
        val v: View = when (k) {
            "del" -> icon(R.drawable.ic_back, 26, R.color.text2)
            "bio" -> if (!setup && Prefs(this).biometric && Lock.biometricAvailable(this)) icon(R.drawable.ic_lock, 26, R.color.accent) else View(this).also { box.background = null }
            else -> text(k, 26f, bold = true)
        }
        box.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        box.setOnClickListener {
            when (k) {
                "del" -> { entered = entered.dropLast(1); drawDots() }
                "bio" -> if (box.background != null) askBiometric()
                else -> if (entered.length < 4) { entered += k; drawDots(); if (entered.length == 4) box.postDelayed({ check() }, 120) }
            }
        }
        return box
    }

    private fun drawDots() {
        dots.removeAllViews()
        repeat(4) { i ->
            dots.addView(View(this).apply {
                background = if (i < entered.length) rounded(col(R.color.accent), 8) else rounded(0, 8, col(R.color.text2), 2)
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { setMargins(dp(9), 0, dp(9), 0) })
        }
    }

    private fun check() {
        val pin = entered
        entered = ""
        if (setup) {
            val f = first
            if (f == null) { first = pin; titleText.text = "Повторите PIN-код"; drawDots(); return }
            if (f != pin) { first = null; titleText.text = "Не совпало. Придумайте PIN-код ещё раз"; drawDots(); return }
            Prefs(this).pinHash = Lock.hash(pin)
            Lock.unlocked = true
            toast("Блокировка включена")
            setResult(Activity.RESULT_OK); finish(); return
        }
        if (Lock.hash(pin) == Prefs(this).pinHash) success()
        else { titleText.text = "Неверный PIN-код"; drawDots(); dots.animate().translationX(20f).setDuration(60).withEndAction { dots.animate().translationX(0f).setDuration(60).start() }.start() }
    }

    private fun success() {
        if (!verify) Lock.unlocked = true
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun askBiometric() {
        if (isFinishing) return
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { success() }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Maysk Scan")
            .setSubtitle(I18n.tr("Приложите палец, чтобы войти"))
            .setNegativeButtonText(I18n.tr("Ввести PIN-код"))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        try { prompt.authenticate(info) } catch (e: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (setup || verify) { setResult(Activity.RESULT_CANCELED); finish() }
        else finishAffinity() // без PIN-кода в приложение не попасть
    }
}
