package com.minimal.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat

/**
 * The white pill toast from the HTML (`.toast`), anchored to the bottom-centre
 * of the viewport instead of the whole window so it never sits behind the rail.
 */
class AppToast(context: Context) : AppCompatTextView(context) {

    private val hideRunnable = Runnable { hide() }

    init {
        text = ""
        setTextColor(Color.BLACK)
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        isSingleLine = true
        maxLines = 1
        ViewCompat.setBackground(this, context.pillWhite())
        val h = dp(10)
        val v = dp(18)
        setPadding(v, h, v, h)
        alpha = 0f
        translationY = dp(20f)
        visibility = GONE
    }

    fun say(message: String) {
        if (message.isEmpty()) return
        text = message
        visibility = VISIBLE
        removeCallbacks(hideRunnable)
        animate().cancel()
        animate().alpha(1f).translationY(0f).setDuration(250).start()
        postDelayed(hideRunnable, 2400)
    }

    fun hide() {
        removeCallbacks(hideRunnable)
        animate().cancel()
        animate().alpha(0f).translationY(dp(20f)).setDuration(250).withEndAction {
            visibility = GONE
        }.start()
    }
}
