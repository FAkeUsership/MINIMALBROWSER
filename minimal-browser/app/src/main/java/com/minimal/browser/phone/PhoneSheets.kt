package com.minimal.browser.phone

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.minimal.browser.DataStore
import com.minimal.browser.R
import com.minimal.browser.Tab
import com.minimal.browser.TabManager
import com.minimal.browser.UrlBar
import com.minimal.browser.ui.UiKeys
import com.minimal.browser.ui.dp
import com.minimal.browser.ui.icon
import com.minimal.browser.ui.roundRect

/** Shared tint for a host's letter avatar, so the same site always gets the same colour. */
internal fun hostTint(host: String): Int = listOf(
    Palette.INK, 0xFF2E2A26.toInt(), 0xFF2D4A44.toInt(), Palette.ACCENT
)[(host.hashCode() and 0x7fffffff) % 4]

internal fun hostLetter(host: String): String =
    host.trimStart('w', '.').firstOrNull()?.uppercaseChar()?.toString() ?: "•"

/* ------------------------------------------------------------------ */
/*  Tabs                                                               */
/* ------------------------------------------------------------------ */

/** Two-column grid of open tabs. The active tab has an accent ring. */
class TabsSheet(private val activity: PhoneActivity) : FrameLayout(activity) {

    private val grid = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(18), activity.dp(8), activity.dp(18), activity.dp(28))
    }
    private val subtitle = activity.label("", 14f, Palette.INK_SOFT)

    init {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(22), activity.dp(22), activity.dp(18), 0)
        }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(activity.displayTitle("Tabs", 36f))
        titles.addView(subtitle.apply { setPadding(0, activity.dp(4), 0, 0) })
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill("Private", Palette.INK, Palette.WHITE) { activity.sheet.dismiss(); activity.newBlankTab(true) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(44)).apply { rightMargin = activity.dp(8) })
        header.addView(pill("New", Palette.ACCENT, Palette.WHITE) { activity.sheet.dismiss(); activity.newBlankTab(false) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(44)))
        column.addView(header, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(grid)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = activity.dp(12) })
        addView(column, FrameLayout.LayoutParams(-1, -1))
        render()
    }

    private fun pill(text: String, fill: Int, ink: Int, onTap: () -> Unit): TextView =
        TextView(activity).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Type.title
            setTextColor(ink)
            setPadding(activity.dp(18), 0, activity.dp(18), 0)
            background = activity.roundRect(99, fill)
            springPress(0.92f)
            setOnClickListener { onTap() }
        }

    private fun render() {
        grid.removeAllViews()
        val tabs = TabManager.tabs.toList()
        val active = TabManager.active
        subtitle.text = "${tabs.size} open ${if (tabs.size == 1) "tab" else "tabs"}"
        if (tabs.isEmpty()) {
            grid.addView(activity.label("No tabs open. Start one with New.", 15f, Palette.INK_SOFT))
            return
        }
        tabs.chunked(2).forEachIndexed { row, pair ->
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { col, tab ->
                val index = row * 2 + col
                line.addView(card(tab, tab === active, index),
                    LinearLayout.LayoutParams(0, activity.dp(176), 1f).apply {
                        leftMargin = if (col == 0) 0 else activity.dp(6)
                        rightMargin = if (col == 0) activity.dp(6) else 0
                    })
            }
            if (pair.size == 1) line.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            grid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(12) })
        }
    }

    private fun card(tab: Tab, isActive: Boolean, index: Int): View {
        val host = UrlBar.hostOf(tab.url)
        val ink = tab.private
        val surface = FrameLayout(activity).apply {
            background = activity.roundRect(24, if (ink) Palette.INK else Palette.CARD,
                if (isActive) Palette.ACCENT else Palette.LINE)
            if (isActive) background = activity.roundRect(24, if (ink) Palette.INK else Palette.CARD, Palette.ACCENT, 2)
            clipToOutline = true
            springPress(0.96f)
            setOnClickListener {
                activity.sheet.dismiss()
                activity.openTab(tab)
            }
            alpha = 0f
            translationY = activity.dp(16).toFloat()
            animate().alpha(1f).translationY(0f).setStartDelay(index * 40L).setDuration(360).start()
        }
        val fg = if (ink) Palette.PAPER else Palette.INK
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
        }
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(activity).apply {
            text = if (ink) "•" else hostLetter(host)
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Type.display
            setTextColor(Palette.WHITE)
            background = activity.roundRect(12, if (ink) Palette.ACCENT else hostTint(host))
        }, LinearLayout.LayoutParams(activity.dp(30), activity.dp(30)))
        top.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(ImageView(activity).apply {
            icon(R.drawable.ic_x, if (ink) Palette.PAPER else Palette.INK_SOFT)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Close tab"
            springPress(0.8f)
            setOnClickListener { closeTab(tab) }
        }, LinearLayout.LayoutParams(activity.dp(30), activity.dp(30)))
        body.addView(top, LinearLayout.LayoutParams(-1, -2))
        body.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
        body.addView(TextView(activity).apply {
            text = tab.displayTitle
            textSize = 15f
            typeface = Type.title
            setTextColor(fg)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(activity.label(if (ink) "Private" else host.ifBlank { "New tab" }, 12f,
            if (ink) 0x99F3F0E8.toInt() else Palette.INK_SOFT).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, activity.dp(4), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        surface.addView(body, FrameLayout.LayoutParams(-1, -1))
        return surface
    }

    private fun closeTab(tab: Tab) {
        TabManager.close(tab)
        if (TabManager.tabs.isEmpty()) {
            activity.sheet.dismiss()
            activity.show(PhoneActivity.Screen.HOME)
        } else {
            render()
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Menu                                                               */
/* ------------------------------------------------------------------ */

/** The phone menu: everything that is not on the dock, plus the layout switch. */
class MenuSheet(private val activity: PhoneActivity) : FrameLayout(activity) {

    init {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(22), activity.dp(22), activity.dp(24))
        }
        column.addView(activity.displayTitle("Menu", 36f))
        column.addView(activity.label("Minimal Browser", 14f, Palette.INK_SOFT).apply {
            setPadding(0, activity.dp(4), 0, activity.dp(12))
        })

        val tab = TabManager.active
        val url = tab?.url.orEmpty()
        val canUseUrl = url.startsWith("http")

        column.addView(activity.card(listOf(
            entry(R.drawable.ic_plus, "New tab") { activity.newBlankTab(false) },
            entry(R.drawable.ic_eyeoff, "New private tab") { activity.newBlankTab(true) },
            entry(R.drawable.ic_home, "Start page") { activity.show(PhoneActivity.Screen.HOME) },
        )), LinearLayout.LayoutParams(-1, -2))

        column.addView(activity.sectionLabel("This page"))
        column.addView(activity.card(listOf(
            entry(R.drawable.ic_book, "Bookmark this page", enabled = canUseUrl) { toggleBookmark() },
            entry(R.drawable.ic_search, "Find on page", enabled = canUseUrl) { findOnPage() },
            entry(R.drawable.ic_share, "Share link", enabled = canUseUrl) { shareLink(url) },
        )), LinearLayout.LayoutParams(-1, -2))

        column.addView(activity.sectionLabel("Your stuff"))
        column.addView(activity.card(listOf(
            entry(R.drawable.ic_history, "History") { activity.showLibrary(0) },
            entry(R.drawable.ic_book, "Bookmarks") { activity.showLibrary(1) },
            entry(R.drawable.ic_download, "Downloads") { activity.showLibrary(2) },
        )), LinearLayout.LayoutParams(-1, -2))

        column.addView(activity.sectionLabel("App"))
        column.addView(activity.card(listOf(
            entry(R.drawable.ic_gear, "Settings") { activity.showSettings() },
        )), LinearLayout.LayoutParams(-1, -2))

        column.addView(TextView(activity).apply {
            text = "Switch to tablet mode"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Type.title
            setTextColor(Palette.INK)
            background = activity.roundRect(99, Palette.PAPER, Palette.INK, 2)
            setPadding(0, activity.dp(15), 0, activity.dp(15))
            springPress(0.97f)
            setOnClickListener { activity.switchToTablet() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(22) })
        column.addView(activity.label("Tablet mode is landscape with the side rail.", 12f, Palette.INK_FAINT).apply {
            gravity = Gravity.CENTER
            setPadding(0, activity.dp(8), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(column)
        }
        addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun entry(icon: Int, title: String, enabled: Boolean = true, onTap: () -> Unit): View {
        val chip = ImageView(activity).apply {
            background = activity.roundRect(99, Palette.PAPER_DEEP)
            icon(icon, if (enabled) Palette.INK else Palette.INK_FAINT)
            scaleType = ImageView.ScaleType.CENTER
        }
        return activity.rowView(
            title, null, leading = chip,
            onTap = if (enabled) ({ activity.sheet.dismiss(); onTap() }) else null
        ).also { if (!enabled) it.alpha = 0.4f }
    }

    private fun toggleBookmark() {
        val tab = TabManager.active ?: return
        val url = tab.url
        val title = tab.displayTitle
        val ctx = activity.applicationContext
        activity.chromeStore.execute {
            val store = DataStore.get(ctx)
            val already = store.isBookmarked(url)
            if (already) store.removeBookmark(url) else store.addBookmark(url, title)
            activity.main.post {
                if (!activity.isDestroyed) activity.toast.say(if (already) "Bookmark removed" else "Bookmarked")
            }
        }
    }

    private fun findOnPage() {
        val input = EditText(activity).apply {
            hint = "Text on this page"
            setSingleLine(true)
            setPadding(activity.dp(20), activity.dp(14), activity.dp(20), activity.dp(14))
            UiKeys.configureTextInput(this)
        }
        AlertDialog.Builder(activity, R.style.Theme_Minimal_Dialog)
            .setTitle("Find on page")
            .setView(input)
            .setPositiveButton("Find") { _, _ ->
                val query = input.text.toString()
                if (query.isNotBlank()) TabManager.findInPage(query)
            }
            .setNegativeButton("Cancel", null)
            .show()
        UiKeys.showKeyboard(input)
    }

    private fun shareLink(url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        activity.startActivity(Intent.createChooser(send, "Share link"))
    }
}
