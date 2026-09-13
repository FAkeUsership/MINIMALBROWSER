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
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
 * Firefox (GeckoView) engine.
 *
 * Full screen mode (the one extra feature on top of the HTML mock):
 *   • hold the **Web** rail button for 5 s  → everything hides, only the page stays
 *   • press **Back** twice (or double-tap the back pill) → the rail peeks back for 4 s
 *   • hold **Web** again → chrome is restored
 */
class MainActivity : AppCompatActivity(), TabManager.Host,
    MenuDrawer.Callback, SettingsScreen.Callback {

    /* ---------------- screens ---------------- */
    private lateinit var rail: RailView
    private lateinit var topBar: LinearLayout
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
    private var fullScreen = false
    private var peek = false
    private var videoFullScreen = false

    private val main = Handler(Looper.getMainLooper())
    private val peekTimer = Runnable { setPeek(false); toast.say(getString(R.string.t_controls_hidden)) }
    private var lastBackAt = 0L
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
        TabManager.init(BrowserApp.requireRuntime())
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
        mainCol.addView(View(this).apply { setBackgroundColor(Ink.EDGE) },
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
        // Tapping Web must NOT leave full screen — the spec is explicit: once in
        // full screen, controls return only via a double back press.
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
        rail.web.onHoldComplete = { toggleFullScreen() }

        /* ---- web screen ---- */
        webScreen.onBack = { TabManager.goBack() }
        webScreen.onForward = { TabManager.goForward() }
        webScreen.onReload = { TabManager.reload() }
        webScreen.onTabs = { show(Screen.TABS) }
        webScreen.onMenu = { drawer.toggle() }
        webScreen.onBackPill = { handleBackPill() }
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
            newTab()
            toast.say(getString(R.string.t_new_tab))
            show(Screen.HOME)
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
                val tab = TabManager.active
                // The session used to be attached only in onResume, so tapping **Web**
                // after launch (or after returning from Settings) showed an empty
                // GeckoView until the activity was resumed again.
                TabManager.attach(webScreen.geckoView)
                if (tab == null && BrowserApp.engineReady) TabManager.newTab(null)
                if (!BrowserApp.engineReady) {
                    toast.say(BrowserApp.startupFailure
                        ?.let { getString(R.string.t_engine_failed, it) }
                        ?: getString(R.string.t_engine_failed_generic))
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
     * Port of the HTML `applyFullscreen()`:
     * rail hidden unless peeking, top bar always hidden in full screen, and on
     * the web screen the web bar / progress track / blocked banner go too.
     */
    private fun applyFullScreen() {
        val on = fullScreen
        val onWeb = current == Screen.WEB

        rail.visibility = if (on && !peek) View.GONE else View.VISIBLE
        topBar.visibility = if (on) View.GONE else View.VISIBLE

        webScreen.setFullScreen(on && onWeb)
        if (!on) webScreen.syncTo(TabManager.active)

        applySystemUi()
    }

    private fun applySystemUi() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // The user asked for the Android status bar (battery / clock) to be gone while
        // using the app, not only in full screen. So: never fit the system windows, and
        // keep the bars hidden at all times — a swipe from the edge still reveals them
        // transiently, which is what immersive sticky is for.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        if (fullScreen) {
            controller.hide(WindowInsetsCompat.Type.displayCutout())
        } else {
            controller.show(WindowInsetsCompat.Type.displayCutout())
        }
    }

    private fun toggleFullScreen() {
        if (!fullScreen) {
            fullScreen = true
            peek = false
            show(Screen.WEB)
            toast.say(getString(R.string.t_fs_enter))
        } else {
            fullScreen = false
            peek = false
            main.removeCallbacks(peekTimer)
            webScreen.cancelEditing()
            applyFullScreen()
            toast.say(getString(R.string.t_fs_exit))
        }
    }

    /** Reveal just the rail for 4 s (the double-back behaviour). */
    private fun setPeek(value: Boolean) {
        peek = value
        main.removeCallbacks(peekTimer)
        if (value) main.postDelayed(peekTimer, 4000)
        applyFullScreen()
    }

    private fun leaveFullScreenIfNeeded() {
        if (!fullScreen) return
        fullScreen = false
        peek = false
        main.removeCallbacks(peekTimer)
        applyFullScreen()
    }

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
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 500) {
                lastBackAt = 0
                if (peek) {
                    setPeek(false)
                    toast.say(getString(R.string.t_hidden_again))
                } else {
                    setPeek(true)
                    toast.say(getString(R.string.t_buttons_shown))
                }
            } else {
                lastBackAt = now
                main.postDelayed({
                    if (lastBackAt != 0L) toast.say(getString(R.string.t_double_tap_hint))
                }, 520)
            }
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
            toast.say("Press back again to close Minimal")
        }
    }

    /** The on-screen back pill behaves exactly like the hardware/gesture back. */
    private fun handleBackPill() {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 500) {
            lastBackAt = 0
            if (peek) {
                setPeek(false)
                toast.say(getString(R.string.t_hidden_again))
            } else {
                setPeek(true)
                toast.say(getString(R.string.t_buttons_shown))
            }
        } else {
            lastBackAt = now
            main.postDelayed({
                if (lastBackAt != 0L) toast.say(getString(R.string.t_double_tap_hint))
            }, 520)
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
        val resolved = if (url.startsWith("http")) url else UrlBar.resolve(url)
        if (resolved.isEmpty()) return
        if (TabManager.active == null) TabManager.newTab(resolved)
        else TabManager.loadInActive(resolved)
        show(Screen.WEB)
    }

    private fun newTab() {
        val target = when (Prefs.homepageMode) {
            HomePageModes.BLANK -> "about:blank"
            HomePageModes.CUSTOM -> Prefs.customHomeUrl.ifBlank { "about:blank" }
            else -> null
        }
        val tab = TabManager.newTab(target)
        TabManager.switchTo(tab)
        show(Screen.HOME)
    }

    private fun newPrivateTab() {
        val tab = TabManager.newTab(null, private = true)
        TabManager.switchTo(tab)
        toast.say("Private tab — nothing will be recorded")
        show(Screen.WEB)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        intent ?: run { bootstrap(); return }
        val uri = intent.dataString
        val query = intent.getStringExtra("query")
        when {
            !uri.isNullOrEmpty() && uri.startsWith("http") -> {
                TabManager.newTab(uri)
                show(Screen.WEB)
            }

            !query.isNullOrBlank() -> {
                TabManager.newTab(UrlBar.resolve(query))
                show(Screen.WEB)
            }

            else -> bootstrap()
        }
    }

    private fun bootstrap() {
        val restored = TabManager.restore(this)
        if (restored) {
            TabManager.tabs.firstOrNull()?.let { TabManager.switchTo(it) }
        } else {
            TabManager.newTab(null)
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
        if (fullScreen) {
            rail.visibility = View.GONE
            topBar.visibility = View.GONE
            webScreen.webBar.visibility = View.GONE
            webScreen.progressTrack.visibility = View.GONE
            webScreen.blockedBanner.visibility = View.GONE
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            applyFullScreen()
        }
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
        newTab()
        toast.say(getString(R.string.t_new_tab))
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
                        BrowserApp.requireRuntime().storageController.clearData(flags)
                    }
                    runOnUiThread { toast.say("Browsing data cleared") }
                }.start()
            }
            .show()
    }

    override fun onShowAbout() {
        AlertDialog.Builder(this, R.style.Theme_Minimal_Dialog)
            .setTitle("Minimal ${BuildConfig.VERSION_NAME}")
            .setMessage("Open-source browser (MPL 2.0)\nEngine: Firefox / GeckoView")
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
        TabManager.attach(webScreen.geckoView)
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
        main.removeCallbacksAndMessages(null)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            BrowserApp.requireRuntime().configurationChanged(newConfig)
        } catch (_: Exception) {
        }
    }
}
