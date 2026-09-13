package com.minimal.browser

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.minimal.browser.ui.AppToast
import com.minimal.browser.ui.HomeScreen
import com.minimal.browser.ui.Ink
import com.minimal.browser.ui.ListScreen
import com.minimal.browser.ui.MenuDrawer
import com.minimal.browser.ui.RailView
import com.minimal.browser.ui.SettingsScreen
import com.minimal.browser.ui.TabsScreen
import com.minimal.browser.ui.UiKeys
import com.minimal.browser.ui.WebScreen
import com.minimal.browser.ui.dp
import com.minimal.browser.ui.icon
import com.minimal.browser.ui.roundRect
import org.mozilla.geckoview.GeckoView

/**
 * Minimal — a landscape, monochrome, privacy-first browser shell around
 * the bundled Mozilla GeckoView engine.
 *
 * Page-only mode:
 *   • hold the **Web** rail button for exactly 5 seconds → only the page stays
 *   • Android Back or a double tap on the page → normal browser controls return
 *   • Android system bars are immersive only while the page-only mode is active
 */
class MainActivity : AppCompatActivity(), TabManager.Host,
    MenuDrawer.Callback, SettingsScreen.Callback {

    /* ---------------- screens ---------------- */
    private lateinit var rail: RailView
    private lateinit var topBar: LinearLayout
    private lateinit var topDivider: View
    private lateinit var topTitle: TextView
    private lateinit var topCrumb: TextView
    private lateinit var shieldPill: LinearLayout
    private lateinit var shieldDot: View
    private lateinit var shieldLabel: TextView

    private lateinit var viewport: FrameLayout
    private lateinit var homeScreen: HomeScreen
    private lateinit var webScreen: WebScreen
    private lateinit var tabsScreen: TabsScreen
    private lateinit var settingsScreen: SettingsScreen
    private lateinit var listScreen: ListScreen
    private lateinit var drawer: MenuDrawer
    private lateinit var toast: AppToast
    /** Created only after the bundled engine is ready and Web is visible. */
    private var geckoView: GeckoView? = null

    /* ---------------- state ---------------- */
    private var current = Screen.HOME
    private var previousScreen = Screen.HOME
    /** App page-only mode, entered after holding Web for five seconds. */
    private var fullScreen = false
    /** A web page's own HTML/video full-screen request. */
    private var videoFullScreen = false
    /** Saved tabs are restored only after a user actually needs the engine. */
    private var restoreAttempted = false
    private var lastEngineFailure: String? = null

    private var lastExitAt = 0L
    private var firstHoldHintDone = false

    private enum class Screen { HOME, WEB, TABS, SETTINGS, LIST }

    /* ---------------- activity results ---------------- */
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { Backup.export(this, it, toast::say) } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { Backup.importFrom(this, it, toast::say) } }

    /* ================================================================== */
    /*  setup                                                              */
    /* ================================================================== */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        // The bundled runtime and GeckoView are both created lazily at the Web
        // boundary. Home, Tabs, and Settings never start native browser code.
        TabManager.host = this

        // Landscape is also locked in the manifest (android:screenOrientation).
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Do not force the display to stay on forever: web video can manage its
        // own wake behavior, while idle browsing should follow device timeout.

        setContentView(buildUi())
        wireUp()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                this@MainActivity.handleBack()
            }
        })

        handleLaunchIntent(intent)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ink.SHELL)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(body, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        /* ---------- left rail ---------- */
        rail = RailView(this)
        body.addView(rail, LinearLayout.LayoutParams(dp(66), LinearLayout.LayoutParams.MATCH_PARENT))

        /* ---------- main column ---------- */
        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ink.SHELL)
        }
        body.addView(mainCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            weight = 1f
        })

        topBar = buildTopBar()
        mainCol.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ))
        topDivider = View(this).apply { setBackgroundColor(Ink.EDGE) }
        mainCol.addView(topDivider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        viewport = FrameLayout(this).apply { setBackgroundColor(Ink.SHELL) }
        mainCol.addView(viewport, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0
        ).apply { weight = 1f })

        /* ---------- the four screens ---------- */
        homeScreen = HomeScreen(this)
        webScreen = WebScreen(this)
        tabsScreen = TabsScreen(this)
        settingsScreen = SettingsScreen(this).also { it.callback = this }
        listScreen = ListScreen(this)

        listOf(homeScreen, webScreen, tabsScreen, settingsScreen, listScreen).forEach {
            it.visibility = View.GONE
            viewport.addView(it, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        /* ---------- drawer + toast ---------- */
        drawer = MenuDrawer(this).also { it.callback = this }
        root.addView(drawer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        toast = AppToast(this)
        root.addView(toast, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(18)
        })

        return root
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ink.SHELL2)
            setPadding(dp(16), 0, dp(16), 0)
        }

        topTitle = TextView(this).apply {
            text = "Home"
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
        }
        bar.addView(topTitle)

        topCrumb = TextView(this).apply {
            text = getString(R.string.crumb_browser)
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.NORMAL)
        }
        bar.addView(topCrumb, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(14) })

        val spacer = View(this)
        bar.addView(spacer, LinearLayout.LayoutParams(0, 1).apply { weight = 1f })

        /* shield pill */
        shieldPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(99, Ink.PANEL, Ink.EDGE2)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
        }
        shieldDot = View(this).apply { background = roundRect(99, Color.WHITE) }
        shieldPill.addView(shieldDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(7) })
        shieldLabel = TextView(this).apply {
            text = getString(R.string.shields_on)
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
        }
        shieldPill.addView(shieldLabel)
        shieldPill.setOnClickListener { toggleShieldsFromPill() }
        bar.addView(shieldPill)

        /* menu button */
        val menu = ImageView(this).apply {
            icon(R.drawable.ic_menu, Ink.MUTED)
            contentDescription = getString(R.string.cd_menu)
            isClickable = true
            isFocusable = true
            setOnClickListener { drawer.toggle() }
        }
        bar.addView(menu, LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(10) })

        return bar
    }

    private fun wireUp() {
        /* ---- rail ---- */
        rail.home.onTap = { leaveFullScreenIfNeeded(); show(Screen.HOME) }
        rail.web.onTap = { show(Screen.WEB) }
        rail.tabs.onTap = { leaveFullScreenIfNeeded(); show(Screen.TABS) }
        rail.settings.onTap = { leaveFullScreenIfNeeded(); show(Screen.SETTINGS) }

        rail.web.onHoldProgressStart = {
            if (!firstHoldHintDone && !Prefs.holdHintShown) {
                firstHoldHintDone = true
                Prefs.holdHintShown = true
                toast.say(getString(R.string.t_hold_hint))
            }
        }
        rail.web.onHoldComplete = { enterPageOnly() }

        /* ---- web screen ---- */
        webScreen.onBack = { TabManager.goBack() }
        webScreen.onForward = { TabManager.goForward() }
        webScreen.onReload = { TabManager.reload() }
        webScreen.onTabs = { leaveFullScreenIfNeeded(); show(Screen.TABS) }
        webScreen.onMenu = { drawer.toggle() }
        // In page-only mode the content is visually clean. A double tap anywhere
        // on the page restores normal browser controls.
        webScreen.onPageDoubleTap = { if (fullScreen) exitPageOnly() }
        webScreen.onSubmitAddress = { openInput(it) }
        webScreen.onAddressFocused = { UiKeys.hideKeyboard(homeScreen) }

        /* ---- home ---- */
        homeScreen.onSubmitSearch = { openInput(it) }
        homeScreen.onOpenUrl = { target ->
            if (target == "__private__") newPrivateTab()
            else openUrl(target)
        }

        /* ---- tabs ---- */
        tabsScreen.onSelect = { tab -> TabManager.switchTo(tab); show(Screen.WEB) }
        tabsScreen.onClose = { tab ->
            TabManager.close(tab)
            toast.say(getString(R.string.t_tab_closed))
        }
        tabsScreen.onNewTab = {
            if (newTab()) toast.say(getString(R.string.t_new_tab))
        }

        /* ---- lists ---- */
        listScreen.onOpenUrl = { openUrl(it) }
        listScreen.onDeleteBookmark = { syncDrawer() }
    }

    /* ================================================================== */
    /*  screen switching (the HTML `show(name)`)                           */
    /* ================================================================== */

    private fun show(screen: Screen) {
        if (screen != Screen.LIST) previousScreen = current
        current = screen

        // A Gecko compositor owns a surface only while the Web screen is visible.
        // Release first, while the view is still attached, then remove it from UI.
        if (screen != Screen.WEB) {
            TabManager.detach()
            webScreen.detachGeckoView()
        }

        homeScreen.visibility = if (screen == Screen.HOME) View.VISIBLE else View.GONE
        webScreen.visibility = if (screen == Screen.WEB) View.VISIBLE else View.GONE
        tabsScreen.visibility = if (screen == Screen.TABS) View.VISIBLE else View.GONE
        settingsScreen.visibility = if (screen == Screen.SETTINGS) View.VISIBLE else View.GONE
        listScreen.visibility = if (screen == Screen.LIST) View.VISIBLE else View.GONE

        when (screen) {
            Screen.HOME -> {
                rail.setActive(rail.home); topTitle.text = "Home"
                topCrumb.text = getString(R.string.crumb_browser)
                homeScreen.refresh()
                UiKeys.hideKeyboard(homeScreen)
            }

            Screen.WEB -> {
                rail.setActive(rail.web)
                topTitle.text = "Web"
                var tab: Tab? = null
                if (ensureBrowserReady()) {
                    tab = TabManager.active ?: openNewTabSafely()
                    attachActiveTabToVisibleView(tab)
                }
                topCrumb.text = tab?.host?.ifEmpty { "loading" } ?: getString(R.string.crumb_browser)
                webScreen.syncTo(tab)
            }

            Screen.TABS -> {
                rail.setActive(rail.tabs); topTitle.text = "Tabs"
                // Tab cards intentionally use their lightweight preview artwork.
                // Do not make a full compositor readback just to open this screen.
                tabsScreen.refresh(TabManager.tabs, TabManager.active?.id)
                topCrumb.text = "${TabManager.tabs.size} open tabs"
            }

            Screen.SETTINGS -> {
                rail.setActive(rail.settings); topTitle.text = "Settings"
                topCrumb.text = "preferences"
                settingsScreen.render()
            }

            Screen.LIST -> { /* rail keeps whatever was selected before */ }
        }

        applyFullScreen()
        syncDrawer()
    }

    private fun showList(mode: ListMode) {
        when (mode) {
            ListMode.HISTORY -> listScreen.showHistory()
            ListMode.BOOKMARKS -> listScreen.showBookmarks()
            ListMode.DOWNLOADS -> listScreen.showDownloads()
        }
        show(Screen.LIST)
    }

    private enum class ListMode { HISTORY, BOOKMARKS, DOWNLOADS }

    /* ================================================================== */
    /*  FULL SCREEN                                                        */
    /* ================================================================== */

    /**
     * Applies true page-only mode. There is no app control, divider, toast, or
     * system bar over the web page — it matches the clean reference screenshot.
     */
    private fun applyFullScreen() {
        val pageOnly = fullScreen || videoFullScreen
        val onWeb = current == Screen.WEB

        rail.visibility = if (pageOnly) View.GONE else View.VISIBLE
        topBar.visibility = if (pageOnly) View.GONE else View.VISIBLE
        topDivider.visibility = if (pageOnly) View.GONE else View.VISIBLE
        webScreen.setFullScreen(pageOnly && onWeb)

        // A toast, even for a short time, would violate the "only the page"
        // contract. This also suppresses any later background callback toast.
        toast.setSuppressed(pageOnly)
        if (!pageOnly) webScreen.syncTo(TabManager.active)
        applySystemUi(pageOnly)
    }

    /**
     * Normal browser screens use normal Android system bars. Page-only mode
     * starts with both bars hidden, but deliberately uses Android's DEFAULT
     * inset behavior rather than sticky/transient immersive behavior. The
     * default keeps a gesture-navigation Back gesture active while bars are
     * hidden, so one Back reaches the AndroidX callback below instead of first
     * being consumed only to reveal a transient navigation bar.
     */
    private fun applySystemUi(pageOnly: Boolean = fullScreen || videoFullScreen) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !pageOnly)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        if (pageOnly) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.hide(WindowInsetsCompat.Type.displayCutout())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.show(WindowInsetsCompat.Type.displayCutout())
        }
    }

    /** Enter page-only mode after the Web rail button has been held for 5 seconds. */
    private fun enterPageOnly() {
        if (fullScreen) return
        // Do not hide every control around a failed engine start. Establish a
        // usable tab first, then enter the intentionally chrome-free page view.
        if (!ensureBrowserReady()) return
        if (TabManager.active == null && openNewTabSafely() == null) return
        fullScreen = true
        drawer.closeImmediately()
        webScreen.cancelEditing()
        show(Screen.WEB)
    }

    /**
     * Android Back or a page double tap restores browser chrome and ordinary
     * system bars. If page content also entered native video/full-screen while
     * app page-only mode was active, leave that state in the same Back action;
     * otherwise the page's full-screen state could consume the first exit.
     */
    private fun exitPageOnly() {
        if (!fullScreen) return
        fullScreen = false
        if (videoFullScreen) {
            videoFullScreen = false
            TabManager.exitPageFullScreen()
        }
        webScreen.cancelEditing()
        applyFullScreen()
    }

    private fun leaveFullScreenIfNeeded() = exitPageOnly()

    /* ================================================================== */
    /*  back handling                                                      */
    /* ================================================================== */

    private fun handleBack() {
        if (drawer.isOpen()) {
            drawer.close()
            return
        }
        if (fullScreen) {
            // App page-only mode has priority over a page's own video state so a
            // single physical/gesture Back always restores browser controls.
            exitPageOnly()
            return
        }
        if (videoFullScreen) {
            TabManager.exitPageFullScreen()
            return
        }
        if (current == Screen.LIST) {
            show(previousScreen)
            return
        }
        if (TabManager.goBack()) return

        // double-tap to exit
        val now = System.currentTimeMillis()
        if (now - lastExitAt < 2000) {
            TabManager.persist()
            finish()
        } else {
            lastExitAt = now
            toast.say("Press back again to close Minimal Browser")
        }
    }

    /* ================================================================== */
    /*  browser availability                                               */
    /* ================================================================== */

    /**
     * One guarded bundled-engine boundary. A failed native startup leaves the
     * Home and Settings screens usable and avoids a crash on Web navigation.
     */
    private fun ensureBrowserReady(): Boolean {
        val runtime = BrowserApp.ensureRuntime(this)
        if (runtime == null) {
            explainEngineUnavailable()
            return false
        }
        TabManager.init(runtime)
        restoreTabsIfNeeded()
        return true
    }

    /** Restore URL-only ordinary tabs after, never before, Gecko is available. */
    private fun restoreTabsIfNeeded() {
        if (restoreAttempted) return
        restoreAttempted = true
        // A process-wide runtime can outlive an Activity recreation. Its live
        // tabs are already the source of truth, so never duplicate them from
        // the persisted URL snapshot in the replacement Activity.
        if (TabManager.tabs.isNotEmpty()) return
        val restored = runCatching { TabManager.restore(this) }.getOrElse { error ->
            Log.e("MinimalBrowser", "Could not restore bundled-engine tabs", error)
            false
        }
        if (restored && TabManager.active == null) {
            TabManager.tabs.firstOrNull()?.let { TabManager.switchTo(it) }
        }
    }

    private fun explainEngineUnavailable() {
        val detail = BrowserApp.startupFailure.orEmpty()
        if (detail == lastEngineFailure) return
        lastEngineFailure = detail
        val base = getString(R.string.t_engine_failed)
        toast.say(if (detail.isBlank()) base else "$base ($detail)")
    }

    private fun openNewTabSafely(url: String? = null, privateTab: Boolean = false): Tab? {
        if (!ensureBrowserReady()) return null
        return try {
            TabManager.newTab(url, privateMode = privateTab)
        } catch (error: Throwable) {
            Log.e("MinimalBrowser", "Unable to create bundled-engine tab", error)
            toast.say(getString(R.string.t_engine_session_failed))
            null
        }
    }

    private fun loadActiveSafely(url: String): Boolean = try {
        if (TabManager.loadInActive(url)) true else {
            toast.say(getString(R.string.t_engine_session_failed))
            false
        }
    } catch (error: Throwable) {
        Log.e("MinimalBrowser", "Unable to load URL in bundled engine", error)
        toast.say(getString(R.string.t_engine_session_failed))
        false
    }

    /** Creates the one visible compositor view on demand. */
    private fun ensureGeckoView(): GeckoView? {
        geckoView?.let { return it }
        return try {
            object : GeckoView(this@MainActivity) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    // Observe before Gecko dispatches to its native child surface;
                    // this keeps the required page-only double tap reliable.
                    webScreen.observePageTouch(event)
                    return super.dispatchTouchEvent(event)
                }
            }.also {
                // GeckoView 153 constructs a SurfaceView by default and wires its
                // SurfaceHolder listener during construction. Keep that initialized
                // default for the documented high-performance backend. Explicitly
                // resetting BACKEND_SURFACE_VIEW here recreates the surface after
                // the listener was registered and can leave the compositor black.
                geckoView = it
            }
        } catch (error: Throwable) {
            Log.e("MinimalBrowser", "Unable to create GeckoView", error)
            toast.say(getString(R.string.t_engine_session_failed))
            null
        }
    }

    /** Moves the lone visible GeckoView into WebScreen, then attaches its session. */
    private fun attachActiveTabToVisibleView(tab: Tab?) {
        val view = ensureGeckoView() ?: return
        webScreen.attachGeckoView(view)
        webScreen.post {
            if (current == Screen.WEB && TabManager.active === tab) {
                // TabManager handles a not-yet-attached view by reposting after
                // Android gives it a window, rather than binding a hidden surface.
                TabManager.attach(view)
            }
        }
    }

    /* ================================================================== */
    /*  navigation                                                         */
    /* ================================================================== */

    private fun openInput(raw: String) {
        val url = UrlBar.resolve(raw)
        if (url.isEmpty()) return
        if (UrlBar.isUrl(raw)) {
            toast.say(getString(R.string.t_opening_fmt, UrlBar.hostOf(url)))
        } else {
            toast.say(getString(R.string.t_searching_fmt, raw))
        }
        openUrl(url)
    }

    private fun openUrl(url: String) {
        val resolved = UrlBar.resolve(url)
        if (resolved.isEmpty()) return
        val scheme = runCatching { Uri.parse(resolved).scheme?.lowercase() }.getOrNull()
        if (scheme !in setOf("http", "https", "about", "data", "blob")) {
            if (!External.openUri(this, resolved)) toast.say("No app can open this link")
            return
        }
        if (TabManager.active == null) {
            if (openNewTabSafely(resolved) == null) return
        } else if (!loadActiveSafely(resolved)) {
            return
        }
        show(Screen.WEB)
    }

    /** @return true only when a real bundled-engine tab was opened. */
    private fun newTab(): Boolean {
        val target = when (Prefs.homepageMode) {
            HomePageModes.BLANK -> "about:blank"
            HomePageModes.CUSTOM -> Prefs.customHomeUrl.ifBlank { "about:blank" }
            else -> null
        }
        if (openNewTabSafely(target) == null) return false
        show(Screen.HOME)
        return true
    }

    private fun newPrivateTab() {
        if (openNewTabSafely(null, privateTab = true) == null) return
        toast.say("Private tab — nothing will be recorded")
        show(Screen.WEB)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        intent ?: run { bootstrap(); return }
        val uri = intent.dataString
        val query = intent.getStringExtra("query")
        when {
            !uri.isNullOrEmpty() -> openUrl(uri)
            !query.isNullOrBlank() -> openInput(query)
            else -> bootstrap()
        }
    }

    private fun bootstrap() {
        // Home deliberately does not construct the bundled native engine. Saved
        // URLs are restored only if the user opens Web or follows a link.
        show(Screen.HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent.dataString
        if (!uri.isNullOrEmpty()) openUrl(uri)
        else intent.getStringExtra("query")?.takeIf { it.isNotBlank() }?.let(::openInput)
    }

    /* ================================================================== */
    /*  TabManager.Host                                                    */
    /* ================================================================== */

    override fun activeContext(): Context = this

    override fun onTabsChanged() {
        if (current == Screen.TABS) tabsScreen.refresh(TabManager.tabs, TabManager.active?.id)
        when (current) {
            Screen.TABS -> topCrumb.text = "${TabManager.tabs.size} open tabs"
            Screen.WEB -> topCrumb.text =
                TabManager.active?.host?.ifEmpty { "loading" } ?: topCrumb.text
            else -> { /* crumb unchanged */ }
        }
        syncDrawer()
    }

    override fun onActiveTabChanged(tab: Tab?) {
        if (current == Screen.WEB) {
            attachActiveTabToVisibleView(tab)
            webScreen.syncTo(tab)
            topCrumb.text = tab?.host?.ifEmpty { "loading" } ?: getString(R.string.crumb_browser)
        }
        syncDrawer()
    }

    override fun onProgress(tab: Tab, progress: Int) {
        if (TabManager.active === tab) webScreen.setProgress(progress)
    }

    override fun onPageFinished(tab: Tab, successful: Boolean) {
        if (TabManager.active === tab) webScreen.syncTo(tab)
    }

    override fun onSecurityChanged(tab: Tab) {
        if (TabManager.active === tab) webScreen.syncTo(tab)
    }

    override fun onBlockedOnPage(tab: Tab) {
        if (TabManager.active === tab && current == Screen.WEB) webScreen.syncTo(tab)
    }

    override fun onBlockedTotal(kind: String, host: String) {
        // Counted in the database; the Home footer reads it back.
    }

    override fun onEnterVideoFullScreen(fullScreen: Boolean) {
        // A hidden/inactive tab must not hide the ordinary Home/Tabs UI because
        // of a delayed content callback after its surface was released.
        if (current != Screen.WEB) {
            if (!fullScreen) videoFullScreen = false
            return
        }
        videoFullScreen = fullScreen
        applyFullScreen()
    }

    override fun onDownloadStarted(fileName: String) {
        toast.say("Downloading $fileName")
    }

    override fun onTabWantsToClose(tab: Tab) {
        TabManager.close(tab)
        if (TabManager.tabs.isEmpty()) {
            leaveFullScreenIfNeeded()
            show(Screen.HOME)
        } else {
            toast.say(getString(R.string.t_tab_closed))
        }
    }

    override fun onSessionRecovered(tab: Tab) {
        if (TabManager.active === tab && current == Screen.WEB) {
            attachActiveTabToVisibleView(tab)
            webScreen.syncTo(tab)
            if (!fullScreen && !videoFullScreen) toast.say("Page renderer restarted")
        }
    }

    override fun onSessionFailure(tab: Tab, reason: String) {
        if (TabManager.active === tab) webScreen.syncTo(tab)
        if (!fullScreen && !videoFullScreen) toast.say(reason)
    }

    /* ================================================================== */
    /*  MenuDrawer.Callback                                                */
    /* ================================================================== */

    override fun onNewTab() {
        if (newTab()) toast.say(getString(R.string.t_new_tab))
    }

    override fun onNewPrivateTab() = newPrivateTab()

    override fun onOpenTabs() {
        leaveFullScreenIfNeeded(); show(Screen.TABS)
    }

    override fun onOpenHistory() {
        leaveFullScreenIfNeeded(); showList(ListMode.HISTORY)
    }

    override fun onOpenBookmarks() {
        leaveFullScreenIfNeeded(); showList(ListMode.BOOKMARKS)
    }

    override fun onOpenDownloads() {
        leaveFullScreenIfNeeded(); showList(ListMode.DOWNLOADS)
    }

    override fun onToggleFullScreen() {
        toast.say(getString(R.string.t_hold_web_hint))
    }

    override fun onToggleShields(on: Boolean) {
        (application as BrowserApp).applyShields()
        syncShieldPill()
        webScreen.syncTo(TabManager.active)
        toast.say(if (on) getString(R.string.shields_on) else getString(R.string.shields_off))
    }

    override fun onToggleTheme(on: Boolean) {
        (application as BrowserApp).applyTheme()
        toast.say(if (on) "Black & white theme on" else "System colours")
    }

    override fun onToggleBookmark() {
        val tab = TabManager.active ?: return
        val url = tab.url
        val title = tab.title
        if (url.isEmpty()) return
        Thread {
            val message = runCatching {
                val db = DataStore.get(this)
                if (db.isBookmarked(url)) {
                    db.removeBookmark(url)
                    "Bookmark removed"
                } else {
                    db.addBookmark(url, title)
                    "Bookmarked"
                }
            }.getOrElse { "Could not update bookmark" }
            runOnUiThread {
                toast.say(message)
                syncDrawer()
            }
        }.start()
    }

    override fun onShare() {
        val tab = TabManager.active ?: return
        External.share(this, tab.url, tab.title)
    }

    override fun onPrint() {
        val ok = Printer.printCurrentPage(this, TabManager.active?.session)
        toast.say(if (ok) "Preparing print…" else "Nothing to print yet")
    }

    override fun onOpenSettings() {
        leaveFullScreenIfNeeded(); show(Screen.SETTINGS)
    }

    override fun onFindInPage() {
        val tab = TabManager.active ?: return
        show(Screen.WEB)
        val field = EditText(this).apply {
            hint = "Find in page"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.BLACK)
            setHintTextColor(0xFF8A8A8A.toInt())
            isSingleLine = true
        }
        AlertDialog.Builder(this, R.style.Theme_Minimal_Dialog)
            .setTitle("Find in page")
            .setView(field)
            .setNegativeButton("Clear") { _, _ -> TabManager.clearFind() }
            .setNeutralButton("Previous") { _, _ ->
                TabManager.findInPage(field.text?.toString().orEmpty(), forward = false)
            }
            .setPositiveButton("Find") { _, _ ->
                TabManager.findInPage(field.text?.toString().orEmpty())
            }
            .show()
    }

    /* ================================================================== */
    /*  SettingsScreen.Callback                                            */
    /* ================================================================== */

    override fun onExportData() {
        exportLauncher.launch("minimal-backup-${System.currentTimeMillis()}.json")
    }

    override fun onImportData() {
        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    override fun onClearData() {
        val labels = arrayOf("History", "Cookies & site data", "Cache", "Blocked-request log")
        val checked = booleanArrayOf(true, true, true, false)
        AlertDialog.Builder(this, R.style.Theme_Minimal_Dialog)
            .setTitle("Clear browsing data")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                Thread {
                    val databaseCleared = runCatching {
                        val db = DataStore.get(this)
                        if (checked[0]) db.clearHistory()
                        if (checked[3]) db.clearBlocked()
                    }.isSuccess
                    runOnUiThread {
                        // Keep the Gecko storage operation on the same UI boundary
                        // as session lifecycle changes.
                        TabManager.clearEngineData(
                            clearCookies = checked[1],
                            clearCache = checked[2],
                            clearHistory = checked[0]
                        )
                        toast.say(if (databaseCleared) "Browsing data cleared" else "Some browsing data could not be cleared")
                    }
                }.start()
            }
            .show()
    }

    override fun onShowAbout() {
        AlertDialog.Builder(this, R.style.Theme_Minimal_Dialog)
            .setTitle("Minimal Browser ${BuildConfig.VERSION_NAME}")
            .setMessage("Privacy-first Android browser\nEngine: Mozilla GeckoView (bundled ARM64)")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun toast(message: String) {
        toast.say(message)
    }

    /* ================================================================== */
    /*  shields pill + drawer sync                                         */
    /* ================================================================== */

    private fun toggleShieldsFromPill() {
        Prefs.shieldsOn = !Prefs.shieldsOn
        (application as BrowserApp).applyShields()
        syncShieldPill()
        webScreen.syncTo(TabManager.active)
        syncDrawer()
    }

    private fun syncShieldPill() {
        val on = Prefs.shieldsOn
        shieldLabel.text = getString(if (on) R.string.shields_on else R.string.shields_off)
        shieldLabel.setTextColor(if (on) Ink.TEXT else Ink.MUTED)
        shieldDot.background = roundRect(99, if (on) Color.WHITE else Ink.MUTED)
    }

    private fun syncDrawer() {
        val tab = TabManager.active
        val bookmarked = tab?.url?.let { url ->
            try {
                DataStore.get(this).isBookmarked(url)
            } catch (e: Exception) {
                false
            }
        } ?: false
        drawer.syncState(TabManager.tabs.size, bookmarked)
    }

    /* ================================================================== */
    /*  lifecycle                                                          */
    /* ================================================================== */

    override fun onResume() {
        super.onResume()
        if (current == Screen.WEB && ensureBrowserReady()) {
            attachActiveTabToVisibleView(TabManager.active)
            TabManager.resumeActive()
        }
        applySystemUi()
        homeScreen.refresh()
    }

    override fun onPause() {
        TabManager.persist()
        TabManager.pauseActive()
        TabManager.detach()
        webScreen.detachGeckoView()
        super.onPause()
    }

    override fun onDestroy() {
        TabManager.detach()
        webScreen.detachGeckoView()
        TabManager.host = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemUi()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // The manifest handles configuration changes in place; forward them to
        // the long-lived bundled runtime instead of recreating native sessions.
        runCatching { BrowserApp.runtime?.configurationChanged(newConfig) }
            .onFailure { Log.w("MinimalBrowser", "could not update Gecko configuration", it) }
    }
}
