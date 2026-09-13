package com.minimal.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.minimal.browser.Prefs
import com.minimal.browser.R

/**
 * The right-hand menu drawer (`#drawer` + `.backdrop`) — a scrim plus a 330dp
 * panel that slides in from the right.
 */
class MenuDrawer(context: Context) : FrameLayout(context) {

    interface Callback {
        fun onNewTab()
        fun onNewPrivateTab()
        fun onOpenTabs()
        fun onOpenHistory()
        fun onOpenBookmarks()
        fun onOpenDownloads()
        fun onToggleFullScreen()
        fun onToggleShields(on: Boolean)
        fun onToggleTheme(on: Boolean)
        fun onToggleBookmark()
        fun onShare()
        fun onPrint()
        fun onOpenSettings()
        fun onFindInPage()
    }

    var callback: Callback? = null

    private val scrim: View
    private val panel: LinearLayout
    private val menuBox: LinearLayout
    private var shieldsSwitch: androidx.appcompat.widget.SwitchCompat? = null
    private var themeSwitch: androidx.appcompat.widget.SwitchCompat? = null
    private var bookmarkRow: LinearLayout? = null
    private var tabsCountLabel: TextView? = null

    private var open = false
    private var showRunnable: Runnable? = null

    init {
        visibility = GONE

        scrim = View(context).apply {
            setBackgroundColor(Ink.SCRIM)
            alpha = 0f
            setOnClickListener { close() }
        }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ink.SHELL2)
        }
        panel.background = android.graphics.drawable.LayerDrawable(
            arrayOf(
                android.graphics.drawable.ColorDrawable(Ink.EDGE),
                android.graphics.drawable.InsetDrawable(
                    android.graphics.drawable.ColorDrawable(Ink.SHELL2), context.dp(1), 0, 0, 0
                )
            )
        )
        addView(panel, LayoutParams(context.dp(330), LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END
        })

        /* ---------- header ---------- */
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(18), context.dp(16), context.dp(18), context.dp(16))
        }
        head.addView(TextView(context).apply {
            text = "U"
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = context.roundRect(10, Color.WHITE)
        }, LinearLayout.LayoutParams(context.dp(34), context.dp(34)).apply { rightMargin = context.dp(10) })

        val who = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        who.addView(TextView(context).apply {
            text = "Your profile"
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
        })
        who.addView(TextView(context).apply {
            text = "no sync · everything stays on this device"
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        head.addView(who, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f })

        head.addView(ImageView(context).apply {
            icon(R.drawable.ic_x, Ink.MUTED)
            contentDescription = context.getString(R.string.cd_close)
            isClickable = true
            isFocusable = true
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(context.dp(20), context.dp(20)))

        panel.addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(View(context).apply { setBackgroundColor(Ink.EDGE) },
            LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1)))

        /* ---------- menu ---------- */
        val scroll = ScrollView(context)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
            weight = 1f
        })

        menuBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(18))
        }
        scroll.addView(menuBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        buildMenu()

        translationX = context.dp(330f)
    }

    /* ------------------------------------------------------------------ */

    private fun buildMenu() {
        menuBox.removeAllViews()

        menuBox += label("Create")
        menuBox += item(R.drawable.ic_plus, "New tab", "Ctrl+T") { callback?.onNewTab(); close() }
        menuBox += item(R.drawable.ic_eye, "New private (incognito) tab", null) { callback?.onNewPrivateTab(); close() }

        menuBox += separator()
        menuBox += label("Library")
        val tabsRow = item(R.drawable.ic_tabs, "Tabs", "0") { callback?.onOpenTabs(); close() }
        tabsCountLabel = tabsRow.findViewWithTag("sub") as? TextView
        menuBox += tabsRow
        menuBox += item(R.drawable.ic_history, "History", null) { callback?.onOpenHistory(); close() }
        bookmarkRow = item(R.drawable.ic_book, "Bookmark this page", null) { callback?.onToggleBookmark(); close() }
        menuBox += bookmarkRow!!
        menuBox += item(R.drawable.ic_download, "Downloads", null) { callback?.onOpenDownloads(); close() }

        menuBox += separator()
        menuBox += label("Controls")
        menuBox += item(R.drawable.ic_globe, "Page-only mode", "hold Web for 5 seconds") {
            callback?.onToggleFullScreen(); close()
        }
        menuBox += item(R.drawable.ic_search, "Find in page", null) { callback?.onFindInPage(); close() }

        val shields = switchItem(R.drawable.ic_shield, "Block ads & trackers", Prefs.shieldsOn) { on ->
            Prefs.shieldsOn = on
            callback?.onToggleShields(on)
        }
        shieldsSwitch = shields.findViewWithTag("switch") as? androidx.appcompat.widget.SwitchCompat
        menuBox += shields

        val theme = switchItem(R.drawable.ic_moon, "Black & white theme", Prefs.bwTheme) { on ->
            Prefs.bwTheme = on
            callback?.onToggleTheme(on)
        }
        themeSwitch = theme.findViewWithTag("switch") as? androidx.appcompat.widget.SwitchCompat
        menuBox += theme

        menuBox += separator()
        menuBox += item(R.drawable.ic_share, "Share…", null) { callback?.onShare() }
        menuBox += item(R.drawable.ic_print, "Print…", null) { callback?.onPrint() }
        menuBox += item(R.drawable.ic_gear, "Settings", null) { callback?.onOpenSettings(); close() }
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text.uppercase()
        setTextColor(Ink.DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.1f
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(4))
    }

    private fun separator() = View(context).apply {
        setBackgroundColor(Ink.EDGE)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1)).apply {
            topMargin = context.dp(10)
            bottomMargin = context.dp(2)
            leftMargin = context.dp(12)
            rightMargin = context.dp(12)
        }
    }

    private fun item(@DrawableRes icon: Int, title: String, sub: String?, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), context.dp(11), context.dp(12), context.dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(context).apply {
            icon(icon, Ink.MUTED)
        }, LinearLayout.LayoutParams(context.dp(19), context.dp(19)).apply { rightMargin = context.dp(13) })

        row.addView(TextView(context).apply {
            text = title
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f })

        if (sub != null) {
            row.addView(TextView(context).apply {
                text = sub
                setTextColor(Ink.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                tag = "sub"
            })
        }
        return row
    }

    private fun switchItem(
        @DrawableRes icon: Int,
        title: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        }
        row.addView(ImageView(context).apply {
            icon(icon, Ink.MUTED)
        }, LinearLayout.LayoutParams(context.dp(19), context.dp(19)).apply { rightMargin = context.dp(13) })

        row.addView(TextView(context).apply {
            text = title
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f })

        row.addView(androidx.appcompat.widget.SwitchCompat(context).apply {
            isChecked = initial
            tag = "switch"
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        })
        return row
    }

    /* ------------------------------------------------------------------ */

    fun isOpen() = open

    fun toggle() {
        if (open) close() else show()
    }

    fun show() {
        if (open) return
        open = true
        buildMenu()
        visibility = VISIBLE
        panel.translationX = context.dp(330f)
        scrim.alpha = 0f
        panel.animate().translationX(0f).setDuration(250).start()
        scrim.animate().alpha(1f).setDuration(200).start()
    }

    fun close() {
        if (!open) return
        open = false
        showRunnable?.let { removeCallbacks(it) }
        panel.animate().translationX(context.dp(330f)).setDuration(250).start()
        scrim.animate().alpha(0f).setDuration(200).withEndAction {
            visibility = GONE
        }.start()
    }

    /** Used before page-only mode so no drawer frame remains for an animation. */
    fun closeImmediately() {
        open = false
        showRunnable?.let { removeCallbacks(it) }
        panel.animate().cancel()
        scrim.animate().cancel()
        panel.translationX = context.dp(330f)
        scrim.alpha = 0f
        visibility = GONE
    }

    /** Called when the tab set or bookmark state changes. */
    fun syncState(tabCount: Int, bookmarked: Boolean) {
        tabsCountLabel?.text = tabCount.toString()
        bookmarkRow?.let { row ->
            val label = row.getChildAt(1) as? TextView
            label?.text = if (bookmarked) "Remove bookmark" else "Bookmark this page"
        }
        shieldsSwitch?.isChecked = Prefs.shieldsOn
        themeSwitch?.isChecked = Prefs.bwTheme
    }
}

private operator fun LinearLayout.plusAssign(child: View) {
    addView(child)
}
