package com.minimal.browser

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.StorageController

/**
 * Minimal — a landscape, monochrome, privacy-first browser shell around the
 * Mozilla GeckoView engine.
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

    /* ---------------- state ---------------- */
    private var current = Screen.HOME
    private var previousScreen = Screen.HOME
    /** App page-only mode, entered after holding Web for five seconds. */
    private var fullScreen = false
    /** A web page's own HTML/video full screen request. */
    private var videoFullScreen = false

    private var lastExitAt = 0L
    private var firstHoldHintDone = false

    private enum class Screen { HOME, WEB, TABS, SETTINGS, LIST }

    /* ---------------- activity results ---------------- */
    private var pendingPermissionCallback: GeckoSession.PermissionDelegate.Callback? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        if (granted) pendingPermissionCallback?.grant() else pendingPermissionCallback?.reject()
        pendingPermissionCallback = null
    }

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
        // Do not turn a Gecko startup failure into an Activity crash. The user can
        // still open Settings/Home and gets a clear message on the Web screen.
        BrowserApp.runtime?.let { TabManager.init(it) }
        TabManager.host = this

        // Landscape is also locked in the manifest (android:screenOrientation).
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(buildUi())
        wireUp()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                this@MainActivity.handleBack()
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
        }

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
        webScreen.onTabs = { show(Screen.TABS) }
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
        listScreen.onOpenUrl = { openUrl(it); show(Screen.WEB) }
        listScreen.onDeleteBookmark = { syncDrawer() }
    }

    /* ================================================================== */
    /*  screen switching (the HTML `show(name)`)                           */
    /* ================================================================== */

    private fun show(screen: Screen) {
        if (screen != Screen.LIST) previousScreen = current
        current = screen

        // A GeckoView compositor should only own a visible Web surface. Releasing
        // it before Home/Tabs/Settings avoids attaching a native renderer to a GONE
        // view, an especially fragile path on Android 14+.
        if (screen != Screen.WEB) TabManager.detach()

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
                var tab = TabManager.active
                if (BrowserApp.engineReady) {
                    // The safe order is: configure delegates -> open session -> attach
                    // the visible GeckoView. In particular, do not open a session with
                    // an unset ContentDelegate (GeckoView Bug 1758212 workaround).
                    if (tab == null) tab = openNewTabSafely()
                    if (tab != null) TabManager.attach(webScreen.geckoView)
                } else {
                    explainEngineUnavailable()
                }
                topCrumb.text = tab?.host?.ifEmpty { "loading" } ?: getString(R.string.crumb_browser)
                webScreen.syncTo(tab)
            }

            Screen.TABS -> {
                rail.setActive(rail.tabs); topTitle.text = "Tabs"
                TabManager.active?.let { TabManager.captureThumbnail(it) }
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
     * Normal browser screens use normal Android system bars. In page-only mode
     * system bars are immersive and any edge reveal automatically disappears
     * again, like other Android full-screen apps.
     */
    private fun applySystemUi(pageOnly: Boolean = fullScreen || videoFullScreen) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !pageOnly)
        if (pageOnly) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.hide(WindowInsetsCompat.Type.displayCutout())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.show(WindowInsetsCompat.Type.displayCutout())
        }
    }

    /** Enter page-only mode after the Web rail button has been held for 5 seconds. */
    private fun enterPageOnly() {
        if (fullScreen) return
        // Do not hide every control around a failed engine start. Establish a
        // usable tab first, then enter the intentionally chrome-free page view.
        if (!engineReady()) return
        if (TabManager.active == null && openNewTabSafely() == null) return
        fullScreen = true
        drawer.closeImmediately()
        webScreen.cancelEditing()
        show(Screen.WEB)
    }

    /** Android Back or a page double tap restores the usual browser UI. */
    private fun exitPageOnly() {
        if (!fullScreen) return
        fullScreen = false
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
        if (videoFullScreen) {
            TabManager.exitPageFullScreen()
            return
        }
        if (fullScreen) {
            // Physical/gesture Back is the direct way out of the clean page view.
            exitPageOnly()
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
    /*  engine availability                                                */
    /* ================================================================== */

    /** Keeps an engine-start failure from becoming a second, avoidable crash. */
    private fun engineReady(): Boolean {
        if (BrowserApp.engineReady) return true
        explainEngineUnavailable()
        return false
    }

    private fun explainEngineUnavailable() {
        toast.say(BrowserApp.startupFailure
            ?.let { getString(R.string.t_engine_failed, it) }
            ?: getString(R.string.t_engine_failed_generic))
    }

    /**
     * Native session startup is the point that previously took down the Activity.
     * Keep the failure in this one boundary so a bad/old device state leaves the
     * browser shell usable instead of crashing the process.
     */
    private fun openNewTabSafely(url: String? = null, privateTab: Boolean = false): Tab? {
        if (!BrowserApp.engineReady) {
            explainEngineUnavailable()
            return null
        }
        return try {
            TabManager.newTab(url, private = privateTab)
        } catch (e: Throwable) {
            Log.e("MinimalBrowser", "Unable to open GeckoSession", e)
            toast.say(getString(R.string.t_engine_session_failed))
            null
        }
    }

    private fun loadActiveSafely(url: String): Boolean {
        return try {
            TabManager.loadInActive(url)
            true
        } catch (e: Throwable) {
            Log.e("MinimalBrowser", "Unable to load URL in GeckoSession", e)
            toast.say(getString(R.string.t_engine_session_failed))
            false
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
        if (!engineReady()) return
        val resolved = if (url.startsWith("http")) url else UrlBar.resolve(url)
        if (resolved.isEmpty()) return
        if (TabManager.active == null) {
            if (openNewTabSafely(resolved) == null) return
        } else if (!loadActiveSafely(resolved)) {
            return
        }
        show(Screen.WEB)
    }

    /** @return true only when a real session was opened. */
    private fun newTab(): Boolean {
        if (!engineReady()) return false
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
        if (!engineReady()) return
        if (openNewTabSafely(null, privateTab = true) == null) return
        toast.say("Private tab — nothing will be recorded")
        show(Screen.WEB)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (!BrowserApp.engineReady) {
            bootstrap()
            return
        }
        intent ?: run { bootstrap(); return }
        val uri = intent.dataString
        val query = intent.getStringExtra("query")
        when {
            !uri.isNullOrEmpty() && uri.startsWith("http") -> openUrl(uri)
            !query.isNullOrBlank() -> openUrl(UrlBar.resolve(query))
            else -> bootstrap()
        }
    }

    private fun bootstrap() {
        if (!BrowserApp.engineReady) {
            show(Screen.HOME)
            return
        }
        val restored = try {
            TabManager.restore(this)
        } catch (e: Throwable) {
            Log.e("MinimalBrowser", "Could not restore GeckoSession", e)
            toast.say(getString(R.string.t_engine_session_failed))
            false
        }
        if (restored) {
            TabManager.tabs.firstOrNull()?.let { TabManager.switchTo(it) }
        } else {
            openNewTabSafely()
        }
        show(Screen.HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent?.dataString
        if (!uri.isNullOrEmpty() && uri.startsWith("http")) {
            openUrl(uri)
        }
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

    override fun onActiveTabChanged(tab: Tab) {
        if (current == Screen.WEB) webScreen.syncTo(tab)
        if (current == Screen.WEB) topCrumb.text = tab.host.ifEmpty { "loading" }
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
        // counted in the database; the home footer reads it back
    }

    override fun onEnterVideoFullScreen(fullScreen: Boolean) {
        videoFullScreen = fullScreen
        applyFullScreen()
    }

    override fun onDownloadStarted(fileName: String) {
        toast.say("Downloading $fileName")
    }

    override fun onRequestAndroidPermissions(
        permissions: Array<String>,
        cb: GeckoSession.PermissionDelegate.Callback
    ) {
        pendingPermissionCallback = cb
        permissionLauncher.launch(permissions)
    }

    override fun onTabWantsToClose(tab: Tab) {
        TabManager.close(tab)
        toast.say(getString(R.string.t_tab_closed))
        if (TabManager.tabs.isEmpty()) show(Screen.HOME)
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
        if (tab.url.isEmpty()) return
        Thread {
            val db = DataStore.get(this)
            if (db.isBookmarked(tab.url)) {
                db.removeBookmark(tab.url)
                runOnUiThread { toast.say("Bookmark removed") }
            } else {
                db.addBookmark(tab.url, tab.title)
                runOnUiThread { toast.say("Bookmarked") }
            }
            runOnUiThread { syncDrawer() }
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
                    val db = DataStore.get(this)
                    var flags = 0L
                    if (checked[0]) {
                        db.clearHistory(); flags = flags or StorageController.ClearFlags.ALL
                    }
                    if (checked[1]) flags = flags or StorageController.ClearFlags.COOKIES
                    if (checked[2]) flags = flags or StorageController.ClearFlags.ALL_CACHES
                    if (checked[3]) db.clearBlocked()
                    if (flags != 0L) {
                        BrowserApp.runtime?.storageController?.clearData(flags)
                    }
                    runOnUiThread { toast.say("Browsing data cleared") }
                }.start()
            }
            .show()
    }

    override fun onShowAbout() {
        AlertDialog.Builder(this, R.style.Theme_Minimal_Dialog)
            .setTitle("Minimal Browser ${BuildConfig.VERSION_NAME}")
            .setMessage("Open-source browser (MPL 2.0)\nEngine: Mozilla GeckoView")
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
        // Do not attach GeckoView behind the Home/Tabs/Settings screens. It is
        // attached synchronously by show(WEB) only when its Surface is visible.
        if (current == Screen.WEB) TabManager.attach(webScreen.geckoView)
        applySystemUi()
        homeScreen.refresh()
    }

    override fun onPause() {
        super.onPause()
        TabManager.persist()
        TabManager.detach()
    }

    override fun onDestroy() {
        super.onDestroy()
        TabManager.host = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemUi()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            BrowserApp.runtime?.configurationChanged(newConfig)
        } catch (_: Exception) {
        }
    }
}
