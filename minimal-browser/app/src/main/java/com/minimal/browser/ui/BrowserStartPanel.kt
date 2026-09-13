package com.minimal.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.minimal.browser.R
import com.minimal.browser.SearchEngines

/**
 * Native start surface shown over a genuinely blank Gecko tab.
 *
 * A blank Gecko document is normally an all-white page. Keeping a small native
 * surface above it makes a new Web tab feel intentional, gives the user a
 * clear search affordance, and avoids pretending that an empty page is content.
 * It disappears before any real navigation, so pages remain entirely Gecko
 * rendered.
 */
class BrowserStartPanel(context: Context) : FrameLayout(context) {

    var onSearch: (() -> Unit)? = null

    private val modeLabel: TextView
    private val engineLabel: TextView

    init {
        setBackgroundColor(Ink.SHELL)
        isClickable = true
        isFocusable = true

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(28), context.dp(24), context.dp(28), context.dp(24))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            leftMargin = context.dp(24)
            rightMargin = context.dp(24)
        })

        val mark = TextView(context).apply {
            text = "M"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = context.roundRect(18, Color.WHITE)
        }
        column.addView(mark, LinearLayout.LayoutParams(context.dp(64), context.dp(64)))

        column.addView(TextView(context).apply {
            text = "Minimal Browser"
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(14)
        })

        modeLabel = TextView(context).apply {
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
        }
        column.addView(modeLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(5)
        })

        val searchButton = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundRect(16, Ink.PANEL, Ink.EDGE2)
            setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = "Search or type a URL"
            setOnClickListener { onSearch?.invoke() }
        }
        searchButton.addView(ImageView(context).apply {
            icon(R.drawable.ic_search, Ink.MUTED)
        }, LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply {
            rightMargin = context.dp(12)
        })
        searchButton.addView(TextView(context).apply {
            text = "Search or type a URL"
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
        })
        searchButton.addView(TextView(context).apply {
            text = "⌕"
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
        column.addView(searchButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(24)
        })

        engineLabel = TextView(context).apply {
            setTextColor(Ink.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
        }
        column.addView(engineLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(12)
        })

        column.addView(TextView(context).apply {
            text = "Your tabs, history, downloads, and privacy controls are in the menu ⋮"
            setTextColor(Ink.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(22)
        })

        sync(privateTab = false)
    }

    fun sync(privateTab: Boolean) {
        modeLabel.text = if (privateTab) {
            "Private tab · this tab is not kept in browser history"
        } else {
            "A clean, focused place to start"
        }
        engineLabel.text = "Search with ${SearchEngines.current().label.substringBefore(" (")}"
    }
}
