package com.minimal.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.minimal.browser.R

/**
 * The 66dp left rail (`aside.rail`) with the monogram, Home / Web / Tabs at the
 * top and Settings pinned to the bottom — exactly as in the HTML.
 */
class RailView(context: Context) : FrameLayout(context) {

    val home: RailButton
    val web: RailButton
    val tabs: RailButton
    val settings: RailButton

    private val buttons: List<RailButton>

    init {
        // 1dp right border drawn as a backing layer, like `border-right:1px solid var(--edge)`
        setBackgroundColor(Ink.EDGE)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ink.SHELL2)
            val p = context.dp(14)
            setPadding(0, p, 0, p)
        }
        addView(column, LayoutParams(context.dp(65), LayoutParams.MATCH_PARENT))

        column.addView(logo(), LinearLayout.LayoutParams(context.dp(38), context.dp(38)).apply {
            bottomMargin = context.dp(12)
        })

        home = make(R.drawable.ic_home, context.getString(R.string.rail_home))
        web = make(R.drawable.ic_globe, context.getString(R.string.rail_web))
        tabs = make(R.drawable.ic_tabs, context.getString(R.string.rail_tabs))

        column.addView(home, buttonParams())
        column.addView(web, buttonParams())
        column.addView(tabs, buttonParams())

        // .spacer { flex:1 }
        column.addView(
            android.view.View(context),
            LinearLayout.LayoutParams(1, 0).apply { weight = 1f }
        )

        settings = make(R.drawable.ic_gear, context.getString(R.string.rail_settings))
        column.addView(settings, buttonParams())

        buttons = listOf(home, web, tabs, settings)
    }

    private fun buttonParams() = LinearLayout.LayoutParams(context.dp(50), context.dp(46)).apply {
        bottomMargin = context.dp(6)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun make(iconRes: Int, label: String) =
        RailButton(context, iconRes, label).also { it.rememberIcon(iconRes) }

    private fun logo() = TextView(context).apply {
        text = "M"
        setTextColor(Color.BLACK)
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        background = context.roundRect(12, Color.WHITE)
    }

    /** Marks `which` active and clears the others, like the HTML `.active` class. */
    fun setActive(which: RailButton) {
        buttons.forEach { it.setActive(it === which) }
    }

    fun clearActive() {
        buttons.forEach { it.setActive(false) }
    }
}
