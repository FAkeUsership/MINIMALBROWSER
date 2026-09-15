package com.minimal.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.minimal.browser.R

/** One ordinary, immediately-tappable button in the left rail. */
class RailButton(
    context: Context,
    @DrawableRes iconRes: Int,
    label: String
) : LinearLayout(context) {

    var onTap: (() -> Unit)? = null

    private val icon = ImageView(context)
    private val text = TextView(context)
    private var active = false

    @DrawableRes
    private var iconResCached: Int = iconRes

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap?.invoke() }

        icon.setImageResource(iconRes)
        addView(icon, LayoutParams(context.dp(20), context.dp(20)))

        text.text = label
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        text.gravity = Gravity.CENTER
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(2)
        })

        applyStyle()
    }

    /* ---------------- styling (.rbtn / .rbtn.active) ---------------- */

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        applyStyle()
    }

    fun isActive() = active

    private fun applyStyle() {
        val fg = if (active) Ink.BLACK else Ink.MUTED
        background = if (active) context.roundRect(12, Color.WHITE) else null
        icon.icon(iconResCached, fg)
        text.setTextColor(fg)
        text.setTypeface(text.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Remember the icon so re-tinting on active/inactive is cheap. */
    fun rememberIcon(@DrawableRes res: Int) {
        iconResCached = res
        applyStyle()
    }
}
