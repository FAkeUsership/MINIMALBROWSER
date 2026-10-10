package com.minimal.browser.phone

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.minimal.browser.Bookmark
import com.minimal.browser.Fmt
import com.minimal.browser.BrowserApp
import com.minimal.browser.DataStore
import com.minimal.browser.DownloadRow
import com.minimal.browser.HistoryEntry
import com.minimal.browser.Prefs
import com.minimal.browser.R
import com.minimal.browser.TabManager
import com.minimal.browser.TabRow
import com.minimal.browser.UrlBar
import com.minimal.browser.ui.UiKeys
import com.minimal.browser.ui.dp
import com.minimal.browser.ui.icon
import com.minimal.browser.ui.roundRect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ------------------------------------------------------------------ */
/*  shared pieces used by every phone screen                           */
/* ------------------------------------------------------------------ */

private fun Context.matchWrap(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

internal fun Context.sectionLabel(text: String): TextView =
    label(text.uppercase(Locale.ROOT), 11f, Palette.INK_FAINT, Type.title).apply {
        letterSpacing = 0.18f
        setPadding(dp(4), dp(22), 0, dp(10))
    }

internal fun Context.displayTitle(text: String, size: Float = 36f): TextView =
    label(text, size, Palette.INK, Type.display).apply {
        letterSpacing = -0.035f
        setLineSpacing(0f, 0.92f)
    }

/** A card that holds several rows separated by hairlines. */
internal fun Context.card(children: List<View>): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(24, Palette.CARD, Palette.LINE)
        clipToOutline = true
        children.forEachIndexed { i, child ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(Palette.LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(child, matchWrap())
        }
    }

/** One tappable row with a title, optional subtitle, and optional trailing view. */
internal fun Context.rowView(
    title: String,
    subtitle: String? = null,
    trailing: View? = null,
    onTap: (() -> Unit)? = null,
    leading: View? = null
): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(14), dp(14))
    }
    leading?.let {
        row.addView(it, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(14) })
    }
    val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    text.addView(label(title, 16f, Palette.INK, Type.title).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    })
    if (!subtitle.isNullOrBlank()) {
        text.addView(label(subtitle, 13f, Palette.INK_SOFT).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
    }
    row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    trailing?.let {
        row.addView(it, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(10) })
    }
    if (onTap != null) {
        row.setOnClickListener { onTap() }
        row.springPress(0.985f)
    }
    return row
}

internal fun Context.switchView(checked: Boolean, onChange: (Boolean) -> Unit): SwitchCompat =
    SwitchCompat(this).apply {
        isChecked = checked
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Palette.ACCENT, Palette.PAPER_DEEP)
        )
        thumbTintList = ColorStateList.valueOf(Palette.WHITE)
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

internal fun Context.iconChip(res: Int, description: String, tint: Int = Palette.INK, onTap: () -> Unit): ImageView =
    ImageView(this).apply {
        background = roundRect(99, Palette.CARD, Palette.LINE)
        icon(res, tint)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onTap() }
        springPress(0.86f)
    }

/** Title row used by Library and Settings: back chip plus a display heading. */
internal fun PhoneActivity.screenHeader(title: String, subtitle: String? = null): LinearLayout {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), 0, dp(22), 0)
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    top.addView(iconChip(R.drawable.ic_back, "Back") {
        onBackPressedDispatcher.onBackPressed()
    })
    column.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    column.addView(displayTitle(title, 38f).apply { setPadding(0, dp(14), 0, 0) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (subtitle != null) column.addView(label(subtitle, 14f, Palette.INK_SOFT).apply {
        setPadding(0, dp(6), 0, dp(4))
    })
    return column
}

/** Runs [work] off the UI thread and delivers the result on the UI thread. */
internal fun <T> PhoneActivity.runAsync(work: () -> T, fallback: T, done: (T) -> Unit) {
    val result = runCatching {
        chromeStore.execute {
            val value = runCatching(work).getOrElse { fallback }
            main.post { if (!isDestroyed) done(value) }
        }
    }
    if (result.isFailure) done(fallback)
}

/** A sliding segmented control. The indicator glides between segments. */
class Segmented(
    context: Context,
    private val labels: List<String>,
    private val onPick: (Int) -> Unit
) : FrameLayout(context) {

    private val pad = context.dp(4)
    private val indicator = View(context).apply { background = context.roundRect(22, Palette.INK) }
    private val texts = labels.mapIndexed { index, text ->
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Type.title
            setTextColor(Palette.INK_SOFT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { select(index, notify = true) }
            springPress(0.97f)
        }
    }
    var selected = 0
        private set

    init {
        background = context.roundRect(26, Palette.PAPER_DEEP)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        texts.forEach { row.addView(it) }
        addView(indicator)
        addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48)))
        select(0, notify = false, animate = false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val seg = segmentWidth()
        indicator.layoutParams = FrameLayout.LayoutParams(seg, (h - pad * 2).coerceAtLeast(1), Gravity.START or Gravity.CENTER_VERTICAL)
            .apply { leftMargin = pad }
        indicator.translationX = selected * seg.toFloat()
    }

    private fun segmentWidth(): Int = ((width - pad * 2) / labels.size.coerceAtLeast(1)).coerceAtLeast(1)

    fun select(index: Int, notify: Boolean = false, animate: Boolean = true) {
        selected = index.coerceIn(0, labels.size - 1)
        texts.forEachIndexed { i, t ->
            t.setTextColor(if (i == selected) Palette.PAPER else Palette.INK_SOFT)
        }
        val target = selected * segmentWidth().toFloat()
        indicator.animate().cancel()
        if (animate && width > 0) {
            indicator.animate().translationX(target).setDuration(360)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
        } else {
            indicator.translationX = target
        }
        if (notify) onPick(selected)
    }
}

/* ------------------------------------------------------------------ */
/*  Home                                                               */
/* ------------------------------------------------------------------ */

/**
 * Start page: a large greeting, a search capsule, quick access tiles from
 * bookmarks, a resume card for the last session, and recent history.
 */
class HomeView(private val activity: PhoneActivity) : ScrollView(activity) {

    private val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(8), dp(22), dp(20))
    }
    private val quickRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val resumeSlot = FrameLayout(activity)
    private val recentCard = LinearLayout(activity)
    private val search: EditText
    private var token = 0

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(column)

        column.addView(label(greeting(), 13f, Palette.INK_SOFT, Type.title).apply { letterSpacing = 0.02f })
        column.addView(activity.displayTitle("Where to,\nnext?", 42f).apply {
            setPadding(0, dp(6), 0, dp(20))
        })

        val capsule = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundRect(30, Palette.CARD, Palette.LINE)
            setPadding(dp(18), 0, dp(6), 0)
            elevation = dp(2).toFloat()
        }
        capsule.addView(ImageView(activity).apply {
            icon(R.drawable.ic_search, Palette.INK_FAINT)
            scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        search = EditText(activity).apply {
            hint = "Search or enter a link"
            setHintTextColor(Palette.INK_FAINT)
            setTextColor(Palette.INK)
            textSize = 16f
            typeface = Type.body
            background = null
            setPadding(dp(10), 0, 0, 0)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, _, _ ->
                val text = v.text.toString()
                UiKeys.hideKeyboard(v, clearFocus = true)
                activity.openInput(text)
                true
            }
            setOnFocusChangeListener { _, focused -> if (focused) UiKeys.showKeyboard(this) }
        }
        UiKeys.configureTextInput(search)
        capsule.addView(search, LinearLayout.LayoutParams(0, dp(58), 1f))
        capsule.addView(ImageView(activity).apply {
            background = context.roundRect(99, Palette.ACCENT)
            icon(R.drawable.ic_fwd, Palette.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Go"
            springPress(0.86f)
            setOnClickListener {
                val text = search.text.toString()
                UiKeys.hideKeyboard(search, clearFocus = true)
                activity.openInput(text)
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        column.addView(capsule, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))

        resumeSlot.visibility = GONE
        column.addView(resumeSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        column.addView(activity.sectionLabel("Quick access"))
        column.addView(quickRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(activity.sectionLabel("Recent"))
        recentCard.orientation = LinearLayout.VERTICAL
        column.addView(recentCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(mediaTip(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
    }

    private fun greeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour < 5 -> "Up late"
            hour < 12 -> "Good morning"
            hour < 18 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    private fun mediaTip(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.roundRect(24, Palette.PAPER_DEEP)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(View(context).apply {
            background = context.roundRect(99, Palette.ACCENT)
        }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) })
        addView(label("Videos on any page are found for you. When something is ready to save, the red button in the dock lights up.",
            13f, Palette.INK_SOFT).apply { setLineSpacing(dp(2).toFloat(), 1f) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Reloads bookmarks, history, and the saved-tab list off the UI thread. */
    fun refresh() {
        val my = ++token
        val ctx = activity.applicationContext
        activity.runAsync(
            work = {
                val db = DataStore.get(ctx)
                HomeData(db.bookmarks().take(8), db.history(10), db.loadTabs())
            },
            fallback = HomeData(emptyList(), emptyList(), emptyList())
        ) { data -> if (my == token) bind(data) }
    }

    private data class HomeData(
        val bookmarks: List<Bookmark>,
        val history: List<HistoryEntry>,
        val savedTabs: List<TabRow>
    )

    private fun bind(data: HomeData) {
        // Quick access: bookmarks first, then the most visited hosts.
        quickRow.removeAllViews()
        val quick = if (data.bookmarks.isNotEmpty()) data.bookmarks.map { it.url to it.title }
        else data.history.distinctBy { UrlBar.hostOf(it.url) }.take(8).map { it.url to it.title }
        if (quick.isEmpty()) {
            quickRow.addView(activity.label("Bookmarks and frequent sites will appear here.", 14f, Palette.INK_SOFT).apply {
                setPadding(dp(4), 0, 0, 0)
            })
        } else {
            quick.chunked(4).forEach { chunk ->
                val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { (url, title) -> line.addView(tile(url, title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
                repeat(4 - chunk.size) { line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
                quickRow.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            }
        }

        // Resume card: only when this launch has no open tabs but a saved session exists.
        resumeSlot.removeAllViews()
        if (TabManager.tabs.isEmpty() && data.savedTabs.isNotEmpty()) {
            resumeSlot.visibility = VISIBLE
            resumeSlot.addView(resumeCard(data.savedTabs.size))
            resumeSlot.enter()
        } else {
            resumeSlot.visibility = GONE
        }

        recentCard.removeAllViews()
        if (data.history.isEmpty()) {
            recentCard.addView(activity.label("Pages you visit will show up here.", 14f, Palette.INK_SOFT).apply {
                setPadding(dp(4), dp(6), 0, 0)
            })
        } else {
            val rows = data.history.take(6).map { entry ->
                context.rowView(
                    title = entry.title.ifBlank { UrlBar.hostOf(entry.url) },
                    subtitle = UrlBar.hostOf(entry.url) + "  ·  " + ago(entry.lastVisit),
                    onTap = { activity.openUrl(entry.url) }
                )
            }
            recentCard.addView(activity.card(rows).also { it.clipToOutline = true },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        column.enter()
    }

    private fun tile(url: String, title: String): View {
        val host = UrlBar.hostOf(url)
        val letter = host.trimStart('w', '.').firstOrNull()?.uppercaseChar()?.toString() ?: "•"
        val tint = listOf(Palette.INK, 0xFF2E2A26.toInt(), 0xFF2D4A44.toInt(), Palette.ACCENT)[
            (host.hashCode() and 0x7fffffff) % 4
        ]
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), 0, dp(2), 0)
            addView(TextView(context).apply {
                text = letter
                gravity = Gravity.CENTER
                textSize = 24f
                typeface = Type.display
                setTextColor(Palette.WHITE)
                background = context.roundRect(20, tint)
            }, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(label(title.ifBlank { host }, 12f, Palette.INK, Type.title).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            springPress(0.92f)
            setOnClickListener { activity.openUrl(url) }
        }
    }

    private fun resumeCard(count: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.roundRect(24, Palette.INK)
        setPadding(dp(18), dp(16), dp(12), dp(16))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Pick up where you left off", 16f, Palette.PAPER, Type.title))
            addView(label("$count ${if (count == 1) "tab" else "tabs"} from your last session", 13f, 0xB3F3F0E8.toInt()).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(context).apply {
            background = context.roundRect(99, Palette.ACCENT)
            icon(R.drawable.ic_fwd, Palette.WHITE)
            scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        springPress(0.97f)
        setOnClickListener { activity.resumeLastSession() }
    }

    private fun ago(ts: Long): String {
        val minutes = (System.currentTimeMillis() - ts) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 60 * 24 -> "${minutes / 60}h ago"
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Library: history, bookmarks, downloads                             */
/* ------------------------------------------------------------------ */

class LibraryView(private val activity: PhoneActivity) : FrameLayout(activity) {

    private val list = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(14), dp(22), dp(20))
    }
    private val segmented = Segmented(activity, listOf("History", "Bookmarks", "Downloads")) { select(it) }
    private var section = 0
    private var token = 0

    init {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(activity.screenHeader("Library"), LinearLayout.LayoutParams(-1, -2))
        column.addView(segmented, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(22); rightMargin = dp(22); topMargin = dp(18)
        })
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(list)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(column, FrameLayout.LayoutParams(-1, -1))
    }

    fun select(index: Int) {
        section = index
        segmented.select(index)
        render()
    }

    fun refresh() = render()

    private fun render() {
        val my = ++token
        val ctx = activity.applicationContext
        val current = section
        activity.runAsync(
            work = {
                val db = DataStore.get(ctx)
                when (current) {
                    0 -> db.history(200)
                    1 -> db.bookmarks()
                    else -> db.downloads()
                }
            },
            fallback = emptyList<Any>()
        ) { items -> if (my == token) bind(current, items) }
    }

    private fun bind(section: Int, items: List<Any>) {
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(emptyState(
                when (section) {
                    0 -> "No history yet"
                    1 -> "No bookmarks yet"
                    else -> "No downloads yet"
                },
                when (section) {
                    0 -> "Pages you visit are listed here, newest first."
                    1 -> "Use the menu to bookmark the page you are reading."
                    else -> "Files you save, including media from the dock, appear here."
                }
            ))
            return
        }
        val rows = items.map { item ->
            when (item) {
                is HistoryEntry -> context.rowView(
                    title = item.title.ifBlank { UrlBar.hostOf(item.url) },
                    subtitle = UrlBar.hostOf(item.url),
                    onTap = { activity.openUrl(item.url) }
                )
                is Bookmark -> context.rowView(
                    title = item.title.ifBlank { UrlBar.hostOf(item.url) },
                    subtitle = UrlBar.hostOf(item.url),
                    trailing = context.iconChip(R.drawable.ic_x, "Remove bookmark", Palette.INK_SOFT) {
                        removeBookmark(item.url)
                    },
                    onTap = { activity.openUrl(item.url) }
                )
                is DownloadRow -> context.rowView(
                    title = item.fileName,
                    subtitle = "${Fmt.bytes(item.bytes)}  ·  ${item.mime.substringBefore(';')}",
                    onTap = { activity.openDownloadedFile(item.localUri, item.mime) }
                )
                else -> View(context)
            }
        }
        list.addView(activity.card(rows), LinearLayout.LayoutParams(-1, -2))
        list.enter()
    }

    private fun removeBookmark(url: String) {
        val ctx = activity.applicationContext
        activity.chromeStore.execute {
            runCatching { DataStore.get(ctx).removeBookmark(url) }
            activity.main.post { if (!activity.isDestroyed) render() }
        }
    }

    private fun emptyState(title: String, body: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(40), dp(6), 0)
        addView(activity.displayTitle(title, 28f))
        addView(label(body, 15f, Palette.INK_SOFT).apply { setPadding(0, dp(10), 0, 0) })
    }
}

/* ------------------------------------------------------------------ */
/*  Settings                                                           */
/* ------------------------------------------------------------------ */

class SettingsView(private val activity: PhoneActivity) : FrameLayout(activity) {

    private val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(14), dp(22), dp(24))
    }

    init {
        val content = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(column)
        }
        val wrap = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(activity.screenHeader("Settings"), LinearLayout.LayoutParams(-1, -2))
        wrap.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(wrap, FrameLayout.LayoutParams(-1, -1))
    }

    private fun app() = activity.applicationContext as BrowserApp

    fun refresh() {
        column.removeAllViews()
        column.addView(activity.sectionLabel("Layout"))
        column.addView(Segmented(activity, listOf("Phone", "Tablet")) { index ->
            if (index == 1) activity.switchToTablet()
        }.apply { select(if (Prefs.tabletMode) 1 else 0, animate = false) }, LinearLayout.LayoutParams(-1, -2))
        column.addView(activity.label("Phone is portrait with the floating dock. Tablet is landscape with the side rail. The screen does not auto-rotate.",
            13f, Palette.INK_SOFT).apply { setPadding(dp(4), dp(10), dp(4), 0) })

        column.addView(activity.sectionLabel("Privacy"))
        column.addView(activity.card(listOf(
            activity.rowView("Shields", "Block trackers and dangerous sites",
                activity.switchView(Prefs.shieldsOn) { on -> Prefs.shieldsOn = on; app().applyShields() }),
            activity.rowView("Block ads", "Hide ads on pages",
                activity.switchView(Prefs.blockAds) { on -> Prefs.blockAds = on; app().applyShields() }),
            activity.rowView("Block fingerprinting", "Stop sites from identifying your device",
                activity.switchView(Prefs.blockFingerprinting) { on -> Prefs.blockFingerprinting = on; app().applyShields() }),
            activity.rowView("HTTPS only", "Upgrade to secure connections when available",
                activity.switchView(Prefs.httpsOnly) { on -> Prefs.httpsOnly = on; app().applyHttpsOnly() }),
            activity.rowView("JavaScript", "Turn off to load pages as plain documents",
                activity.switchView(Prefs.javaScriptEnabled) { on -> Prefs.javaScriptEnabled = on; app().applyJavaScript() }),
        )), LinearLayout.LayoutParams(-1, -2))

        column.addView(activity.sectionLabel("Reading"))
        column.addView(activity.card(listOf(
            activity.rowView("Desktop site", "Ask for the desktop version of pages",
                activity.switchView(Prefs.desktopUserAgent) { on -> Prefs.desktopUserAgent = on; app().applyUserAgent() }),
            activity.rowView("Keyboard", "Show the on-screen keyboard in text fields",
                activity.switchView(Prefs.showMobileKeyboard) { on -> Prefs.showMobileKeyboard = on }),
        )), LinearLayout.LayoutParams(-1, -2))

        column.addView(activity.sectionLabel("Text size"))
        val sizes = listOf(90 to "Small", 100 to "Default", 120 to "Large")
        val current = sizes.indexOfFirst { it.first == Prefs.textSizePercent }.let { if (it < 0) 1 else it }
        column.addView(Segmented(activity, sizes.map { it.second }) { index ->
            Prefs.textSizePercent = sizes[index].first
            app().applyTextSize()
        }.apply { select(current, animate = false) }, LinearLayout.LayoutParams(-1, -2))

        column.addView(activity.sectionLabel("Data"))
        column.addView(activity.card(listOf(
            activity.rowView("Clear browsing data", "History, cookies, cache", onTap = { activity.clearBrowsingData() }),
        )), LinearLayout.LayoutParams(-1, -2))

        column.addView(activity.sectionLabel("About"))
        val version = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        }.getOrNull() ?: ""
        column.addView(activity.card(listOf(
            activity.rowView("Minimal Browser $version", "Bundled Mozilla GeckoView engine"),
        )), LinearLayout.LayoutParams(-1, -2))
        column.enter()
    }
}
