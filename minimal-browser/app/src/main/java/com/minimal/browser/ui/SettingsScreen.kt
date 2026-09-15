package com.minimal.browser.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.minimal.browser.BrowserApp
import com.minimal.browser.BuildConfig
import com.minimal.browser.HomePageModes
import com.minimal.browser.Prefs
import com.minimal.browser.R
import com.minimal.browser.SearchEngines
import java.util.Locale

/**
 * `#s-settings` — the two-column settings screen: a 210dp section nav on the
 * left, the panel on the right. Every row from the HTML is here and every one
 * of them actually does something.
 */
class SettingsScreen(context: Context) : LinearLayout(context) {

    interface Callback {
        fun onOpenDownloads()
        fun onOpenHistory()
        fun onOpenBookmarks()
        fun onExportData()
        fun onImportData()
        fun onClearData()
        fun onShowAbout()
        fun onStatusBarVisibilityChanged()
        fun onMobileKeyboardVisibilityChanged()
        fun toast(message: String)
    }

    var callback: Callback? = null

    private val navItems = mutableListOf<LinearLayout>()
    private val body: LinearLayout

    private val sections = listOf(
        Section(R.drawable.ic_eye, context.getString(R.string.set_appearance)) { appearancePanel() },
        Section(R.drawable.ic_shield, context.getString(R.string.set_privacy)) { privacyPanel() },
        Section(R.drawable.ic_search, context.getString(R.string.set_search)) { searchPanel() },
        Section(R.drawable.ic_home, context.getString(R.string.set_homepage)) { homePanel() },
        Section(R.drawable.ic_download, context.getString(R.string.set_downloads) ) { downloadsPanel() },
        Section(R.drawable.ic_file, context.getString(R.string.set_sync)) { syncPanel() },
        Section(R.drawable.ic_info, context.getString(R.string.set_about)) { aboutPanel() }
    )

    private class Section(
        @DrawableRes val icon: Int,
        val title: String,
        val build: () -> List<View>
    )

    private var selected = 0

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Ink.SHELL)

        /* ---------------- left nav (.setnav) ---------------- */
        val navOuter = LinearLayout(context).apply { setBackgroundColor(Ink.EDGE) }
        addView(navOuter, LayoutParams(context.dp(211), LayoutParams.MATCH_PARENT))

        val nav = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(Ink.SHELL2)
            setPadding(context.dp(10), context.dp(16), context.dp(10), context.dp(16))
        }
        navOuter.addView(nav, LayoutParams(context.dp(210), LayoutParams.MATCH_PARENT))

        sections.forEachIndexed { index, section ->
            val item = navItem(section)
            item.setOnClickListener { select(index) }
            navItems += item
            nav.addView(item, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(4)
            })
        }

        /* ---------------- body (.setbody) ---------------- */
        val scroll = ScrollView(context).apply { isFillViewport = false }
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT).apply { weight = 1f })

        body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(26), context.dp(22), context.dp(26), context.dp(30))
        }
        scroll.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        select(0)
    }

    private fun navItem(section: Section) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(13), context.dp(10), context.dp(13), context.dp(10))
        isClickable = true
        isFocusable = true
        addView(ImageView(context).apply {
            icon(section.icon, Ink.MUTED)
        }, LayoutParams(context.dp(17), context.dp(17)).apply { rightMargin = context.dp(11) })
        addView(TextView(context).apply {
            text = section.title
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        })
    }

    fun select(index: Int) {
        selected = index
        navItems.forEachIndexed { i, v ->
            val on = i == index
            v.background = if (on) context.getDrawable(R.drawable.bg_setnav_sel) else null
            val label = v.getChildAt(1) as? TextView
            val iv = v.getChildAt(0) as? ImageView
            label?.setTextColor(if (on) Ink.TEXT else Ink.MUTED)
            label?.setTypeface(label.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
            iv?.icon(sections[i].icon, if (on) Ink.TEXT else Ink.MUTED)
        }
        render()
    }

    /** Re-render the current panel (after a value changes elsewhere). */
    fun render() {
        body.removeAllViews()
        val section = sections[selected]
        body.addView(TextView(context).apply {
            text = section.title
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
        })
        body.addView(TextView(context).apply {
            text = descriptionFor(selected)
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(4)
            bottomMargin = context.dp(18)
        })
        section.build().forEach { body.addView(it) }
    }

    /** Update any currently-rendered custom text control after device changes. */
    fun refreshInputMode() = refreshTextInputs(this)

    private fun refreshTextInputs(view: View) {
        when (view) {
            is EditText -> UiKeys.configureTextInput(view)
            is ViewGroup -> for (index in 0 until view.childCount) {
                refreshTextInputs(view.getChildAt(index))
            }
        }
    }

    private fun descriptionFor(index: Int) = when (index) {
        0 -> "Make the browser look the way you like. Less is more."
        1 -> "Everything is blocked before the request leaves the device."
        2 -> "Used in the address bar and on the home screen."
        3 -> "What a new tab opens."
        4 -> "Where files land and what you have fetched."
        5 -> "Take your bookmarks and history with you."
        else -> "Minimal Browser · bundled Mozilla GeckoView engine"
    }

    /* ================================================================== */
    /*  panels                                                             */
    /* ================================================================== */

    private fun appearancePanel(): List<View> {
        val out = ArrayList<View>()
        out += group("Full screen")
        out += infoRow(
            R.drawable.ic_fullscreen,
            "Use the small top-right button",
            "Tap the corners button to enter page-only mode. It stays at the top-right as a small close button so you can always leave explicitly."
        )
        out += infoRow(
            R.drawable.ic_back,
            "Android Back or double-tap the page",
            "These also restore normal browser controls and Android system bars."
        )

        out += group("Keyboard & mouse")
        out += switchRow(
            R.drawable.ic_keyboard,
            "Show mobile keyboard",
            "Show Android's on-screen keyboard when you tap a text field. Turn it off for a desktop-style keyboard or mouse setup; a connected physical keyboard still keeps it hidden.",
            Prefs.showMobileKeyboard
        ) { on ->
            Prefs.showMobileKeyboard = on
            callback?.onMobileKeyboardVisibilityChanged()
            callback?.toast(if (on) "Mobile keyboard enabled" else "Mobile keyboard hidden")
        }
        out += infoRow(
            R.drawable.ic_keyboard,
            "External keyboard",
            "A connected USB or Bluetooth keyboard keeps the Android on-screen keyboard hidden. Enter and numpad Enter submit a search or address."
        )
        out += infoRow(
            R.drawable.ic_back,
            "Desktop-style navigation",
            "Ctrl+L focuses the address bar; Ctrl+T/W opens or closes a tab; Ctrl+Tab switches tabs; Alt+left/right and mouse side buttons navigate."
        )

        out += group("Android system bars")
        out += switchRow(
            R.drawable.ic_eyeoff,
            "Hide Android status bar",
            "Hides only the top clock/status bar on normal screens. Page-only mode always hides both Android bars.",
            Prefs.hideStatusBar
        ) { on ->
            Prefs.hideStatusBar = on
            callback?.onStatusBarVisibilityChanged()
            callback?.toast(if (on) "Android status bar hidden" else "Android status bar shown")
        }

        out += group("Theme")
        out += switchRow(
            R.drawable.ic_moon,
            "Black & white theme",
            "Monochrome everywhere — on by design",
            Prefs.bwTheme
        ) { on ->
            Prefs.bwTheme = on
            app().applyTheme()
        }
        out += seekRow(
            R.drawable.ic_file,
            "Text size",
            "Page zoom default",
            80, 140, Prefs.textSizePercent
        ) { value ->
            Prefs.textSizePercent = value
            app().applyTextSize()
        }
        return out
    }

    private fun privacyPanel(): List<View> {
        val out = ArrayList<View>()
        out += group("Privacy & Shields")
        out += switchRow(
            R.drawable.ic_shield, "Block ads & trackers", "Aggressive · blocks before page load", Prefs.blockAds
        ) { on ->
            Prefs.blockAds = on
            app().applyShields()
        }
        out += switchRow(
            R.drawable.ic_eye, "Block fingerprinting", "Disable page location APIs and known fingerprint endpoints", Prefs.blockFingerprinting
        ) { on ->
            Prefs.blockFingerprinting = on
            app().applyShields()
        }
        out += switchRow(
            R.drawable.ic_lock, "Upgrade to HTTPS", "Always use secure connections", Prefs.httpsOnly
        ) { on ->
            Prefs.httpsOnly = on
            app().applyHttpsOnly()
            callback?.toast(if (on) "HTTPS-first on" else "HTTPS-first off")
        }

        out += group("Content")
        out += switchRow(
            R.drawable.ic_globe, "JavaScript", "Most sites need it; turn it off for maximum quiet", Prefs.javaScriptEnabled
        ) { on ->
            Prefs.javaScriptEnabled = on
            app().applyJavaScript()
        }
        out += switchRow(
            R.drawable.ic_file, "Desktop site", "Request the desktop version of every page", Prefs.desktopUserAgent
        ) { on ->
            Prefs.desktopUserAgent = on
            app().applyUserAgent()
        }
        out += switchRow(
            R.drawable.ic_shield, "Shields master switch", "Turns every protection off at once", Prefs.shieldsOn
        ) { on ->
            Prefs.shieldsOn = on
            app().applyShields()
        }
        return out
    }

    private fun searchPanel(): List<View> {
        val out = ArrayList<View>()
        out += group("Search")
        val current = SearchEngines.ALL.indexOfFirst { it.id == Prefs.searchEngine }.coerceAtLeast(0)
        out += spinnerRow(
            R.drawable.ic_search, "Search engine", "Used in the address bar",
            SearchEngines.ALL.map { it.label }, current
        ) { position ->
            Prefs.searchEngine = SearchEngines.ALL[position].id
        }
        out += infoRow(
            R.drawable.ic_info,
            "Search shortcut",
            "Type a plain word in the address bar to search, or a domain (example.com) to go straight there. https:// is added automatically."
        )
        return out
    }

    private fun homePanel(): List<View> {
        val out = ArrayList<View>()
        out += group("Homepage")
        val labels = listOf("Minimal home (default)", "Blank page", "Custom URL")
        val current = when (Prefs.homepageMode) {
            HomePageModes.BLANK -> 1
            HomePageModes.CUSTOM -> 2
            else -> 0
        }
        out += spinnerRow(
            R.drawable.ic_home, "New tabs open", "What a new tab loads", labels, current
        ) { position ->
            Prefs.homepageMode = when (position) {
                1 -> HomePageModes.BLANK
                2 -> HomePageModes.CUSTOM
                else -> HomePageModes.INTERNAL
            }
            render()
        }
        if (Prefs.homepageMode == HomePageModes.CUSTOM) {
            out += textRow(
                R.drawable.ic_globe, "Custom homepage", "Loaded in every new tab",
                Prefs.customHomeUrl, "https://example.com"
            ) { value ->
                Prefs.customHomeUrl = value.trim()
            }
        }
        out += infoRow(
            R.drawable.ic_eye, "Private tabs",
            "Private tabs are excluded from app history and session restore, and are dropped when closed."
        )
        return out
    }

    private fun downloadsPanel(): List<View> {
        val out = ArrayList<View>()
        out += group("Downloads")
        out += actionRow(R.drawable.ic_download, "Open downloads", "Saved to the public Downloads folder", "Open") {
            callback?.onOpenDownloads()
        }
        out += infoRow(
            R.drawable.ic_file, "Where files go",
            "Android’s Downloads service saves them so they appear in your Files app."
        )
        return out
    }

    private fun syncPanel(): List<View> {
        val out = ArrayList<View>()
        out += group("Sync & backup")
        out += actionRow(R.drawable.ic_share, "Export bookmarks & history", "Saves a JSON file you choose", "Export…") {
            callback?.onExportData()
        }
        out += actionRow(R.drawable.ic_download, "Import from a backup", "Restores bookmarks from a JSON file", "Import…") {
            callback?.onImportData()
        }
        out += actionRow(R.drawable.ic_book, "Bookmarks", "Everything you saved", "Open") {
            callback?.onOpenBookmarks()
        }
        out += actionRow(R.drawable.ic_history, "History", "Everything you opened", "Open") {
            callback?.onOpenHistory()
        }
        return out
    }

    private fun aboutPanel(): List<View> {
        val out = ArrayList<View>()
        out += group("Data")
        out += actionRow(
            R.drawable.ic_history, "Clear browsing data", "History, cookies, cache", "Clear…"
        ) {
            callback?.onClearData()
        }
        out += group("About")
        out += infoRow(R.drawable.ic_info, "Version", "Minimal Browser ${BuildConfig.VERSION_NAME} · open-source (MPL 2.0)")
        out += infoRow(R.drawable.ic_globe, "Engine", "Mozilla GeckoView · bundled ARM64")
        out += infoRow(R.drawable.ic_shield, "Block list", "Local request-level EasyList-style rules")
        return out
    }

    /* ================================================================== */
    /*  row builders                                                       */
    /* ================================================================== */

    private fun app() = (context.applicationContext as BrowserApp)

    private fun group(title: String): View {
        val wrap = LinearLayout(context).apply { orientation = VERTICAL }
        wrap.addView(TextView(context).apply {
            text = title.uppercase(Locale.US)
            setTextColor(Ink.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(6)
            bottomMargin = context.dp(10)
        })
        return wrap
    }

    /** Wrap a control into an `.srow`. */
    private fun row(
        @DrawableRes iconRes: Int,
        title: String,
        subtitle: String?,
        trailing: View?
    ): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.getDrawable(R.drawable.bg_srow)
            setPadding(context.dp(15), context.dp(13), context.dp(15), context.dp(13))
        }
        val ic = ImageView(context).apply {
            icon(iconRes, Ink.TEXT)
            background = context.getDrawable(R.drawable.bg_srow_ic)
            val p = context.dp(8)
            setPadding(p, p, p, p)
        }
        row.addView(ic, LayoutParams(context.dp(34), context.dp(34)).apply { rightMargin = context.dp(14) })

        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(TextView(context).apply {
            text = title
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(typeface, Typeface.BOLD)
        })
        if (subtitle != null) {
            texts.addView(TextView(context).apply {
                text = subtitle
                setTextColor(Ink.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(1)
            })
        }
        row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 1f })

        if (trailing != null) {
            row.addView(trailing, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = context.dp(14) })
        }
        val outer = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(row)
        }
        outer.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(9)
        }
        return outer
    }

    private fun infoRow(@DrawableRes icon: Int, title: String, subtitle: String? = null) =
        row(icon, title, subtitle, null)

    private fun switchRow(
        @DrawableRes icon: Int,
        title: String,
        subtitle: String?,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val switch = androidx.appcompat.widget.SwitchCompat(context).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        return row(icon, title, subtitle, switch)
    }

    private fun seekRow(
        @DrawableRes icon: Int,
        title: String,
        subtitle: String?,
        min: Int,
        max: Int,
        initial: Int,
        onChange: (Int) -> Unit
    ): View {
        val value = TextView(context).apply {
            text = "$initial%"
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.END
            minWidth = context.dp(40)
        }
        val seek = SeekBar(context).apply {
            this.max = max - min
            progress = initial - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress + min
                    value.text = "$v%"
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        val trailing = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(seek, LayoutParams(context.dp(130), LayoutParams.WRAP_CONTENT))
            addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = context.dp(8)
            })
        }
        return row(icon, title, subtitle, trailing)
    }

    private fun spinnerRow(
        @DrawableRes icon: Int,
        title: String,
        subtitle: String?,
        options: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ): View {
        val spinner = Spinner(context).apply {
            background = context.getDrawable(R.drawable.bg_sel)
            setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(Ink.PANEL2))
            val pad = context.dp(10)
            setPadding(pad, context.dp(7), pad, context.dp(7))
            adapter = ArrayAdapter(context, R.layout.spinner_item, options).apply {
                setDropDownViewResource(R.layout.spinner_dropdown)
            }
            setSelection(selectedIndex.coerceIn(0, options.size - 1), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onPick(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        return row(icon, title, subtitle, spinner)
    }

    private fun textRow(
        @DrawableRes icon: Int,
        title: String,
        subtitle: String?,
        initial: String,
        hint: String,
        onCommit: (String) -> Unit
    ): View {
        val field = EditText(context).apply {
            setText(initial)
            this.hint = hint
            setHintTextColor(Ink.DIM)
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            isSingleLine = true
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            UiKeys.configureTextInput(this)
            background = context.getDrawable(R.drawable.bg_sel)
            val pad = context.dp(10)
            setPadding(pad, context.dp(7), pad, context.dp(7))
            minWidth = context.dp(180)
            setOnFocusChangeListener { _, focused -> if (!focused) onCommit(text?.toString().orEmpty()) }
            setOnEditorActionListener { _, _, _ ->
                onCommit(text?.toString().orEmpty()); true
            }
        }
        return row(icon, title, subtitle, field)
    }

    private fun actionRow(
        @DrawableRes icon: Int,
        title: String,
        subtitle: String?,
        buttonLabel: String,
        onClick: () -> Unit
    ): View {
        val button = TextView(context).apply {
            text = buttonLabel
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            background = context.getDrawable(R.drawable.bg_sel)
            val padH = context.dp(14)
            setPadding(padH, context.dp(7), padH, context.dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        return row(icon, title, subtitle, button)
    }
}
