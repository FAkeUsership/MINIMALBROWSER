package com.minimal.browser

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
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
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal — a landscape, monochrome, privacy-first browser shell around
 * the bundled Mozilla GeckoView engine.
 *
 * Page-only mode:
 *   • a compact top-right toggle explicitly enters and leaves the page view
 *   • Android Back or a double tap on the page remain supplementary exits
 *   • Android system bars are immersive only while the page-only mode is active
 */
class MainActivity : AppCompatActivity(), TabManager.Host,
    MenuDrawer.Callback, SettingsScreen.Callback {

    /* ---------------- screens ---------------- */
    private lateinit var rail: RailView
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
    private var visibleListMode: ListMode? = null
    /** App page-only mode, entered from the explicit compact toggle or menu. */
    private var fullScreen = false
    /** A web page's own HTML/video full-screen request. */
    private var videoFullScreen = false
    /** Saved tabs are restored only after a user actually needs the engine. */
    private var restoreAttempted = false
    private var lastEngineFailure: String? = null

    private var lastExitAt = 0L

    // Chrome callbacks may arrive during navigation. Keep bookmark queries off
    // the render/UI thread and reuse one worker rather than spawning a thread
    // per menu action or per location update.
    private val chromeStore = Executors.newSingleThreadExecutor { work ->
        Thread(work, "minimal-browser-chrome-store").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private var drawerBookmarkUrl: String? = null
    private var drawerBookmarkValue = false
    private var drawerBookmarkRequest = 0L
    private var lastDownloadsRefreshAt = 0L
    private var lastImeHideAt = 0L
    private var lastMouseBackAt = 0L
    private var lastMouseForwardAt = 0L
    private var inputDeviceListenerRegistered = false
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = requestInputModeRefresh()
        override fun onInputDeviceRemoved(deviceId: Int) = requestInputModeRefresh()
        override fun onInputDeviceChanged(deviceId: Int) = requestInputModeRefresh()
    }

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

    /* ================================================================== */
    /*  external keyboard and mouse                                      */
    /* ================================================================== */

    /**
     * Keep normal page typing in GeckoView untouched, but make the familiar
     * desktop-browser navigation shortcuts work when a physical keyboard is
     * attached. The address controls themselves also handle plain Enter.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && isMouseKeyEvent(event)) {
            // Android may synthesize these KeyEvents in addition to a mouse
            // ACTION_BUTTON_PRESS. Consume the duplicate, or use it as a
            // fallback on devices that only send the key event.
            val now = System.currentTimeMillis()
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (now - lastMouseBackAt > 250L) {
                        lastMouseBackAt = now
                        handleMouseBack()
                    }
                    return true
                }
                KeyEvent.KEYCODE_FORWARD -> {
                    if (now - lastMouseForwardAt > 250L) {
                        lastMouseForwardAt = now
                        TabManager.goForward()
                    }
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            UiKeys.isEnterKey(event.keyCode) &&
            submitFocusedNativeText()
        ) {
            // Keep this activity-level fallback ahead of the normal shortcut
            // path. A few keyboard/IME combinations consume EditText Enter
            // before its listener runs; this still submits the omnibox once.
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && !UiKeys.shouldShowSoftwareKeyboard(this)) {
            hideSoftwareKeyboardIfSuppressed()
        }
        if (
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            UiKeys.hasHardwareKeyboard(this) &&
            handleKeyboardShortcut(event)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Android normally routes pointer-wheel events to the view under the cursor,
     * but some dock/mouse stacks route them to the focused shell view instead.
     * Send an in-bounds wheel event straight to GeckoView with coordinates made
     * local to its surface. This preserves a web app's nested overflow target
     * (for example, an open Arena-style history/sidebar), rather than only
     * scrolling the outer Android shell or dropping the event.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isPointerScrollEvent(event) && forwardPointerScrollToGecko(event)) {
            return true
        }
        if (isMouseEvent(event) && event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
            when (event.actionButton) {
                MotionEvent.BUTTON_BACK -> {
                    val now = System.currentTimeMillis()
                    if (now - lastMouseBackAt > 250L) {
                        lastMouseBackAt = now
                        handleMouseBack()
                    }
                    return true
                }
                MotionEvent.BUTTON_FORWARD -> {
                    val now = System.currentTimeMillis()
                    if (now - lastMouseForwardAt > 250L) {
                        lastMouseForwardAt = now
                        TabManager.goForward()
                    }
                    return true
                }
            }
            window.decorView.postDelayed({ hideSoftwareKeyboardIfSuppressed(force = true) }, 90L)
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * A mouse click, docked keyboard, or the explicit "mobile keyboard off"
     * preference can focus a page text field and still make Gecko request the
     * IME. Hide only that software IME on the next loop turn; do not clear
     * focus, so physical typing still reaches the field that was clicked.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !UiKeys.shouldShowSoftwareKeyboard(this)) {
            window.decorView.postDelayed({ hideSoftwareKeyboardIfSuppressed(force = true) }, 90L)
        }
        return handled
    }

    private fun handleKeyboardShortcut(event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            if (event.keyCode == KeyEvent.KEYCODE_N && event.isShiftPressed) {
                openTabFromKeyboard(privateTab = true)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_TAB && cycleTabsFromKeyboard(event.isShiftPressed)) {
                return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_L -> {
                    focusAddressFromKeyboard()
                    return true
                }
                KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_N -> {
                    openTabFromKeyboard(privateTab = false)
                    return true
                }
                KeyEvent.KEYCODE_W -> return closeActiveTabFromKeyboard()
                KeyEvent.KEYCODE_R -> {
                    if (TabManager.active == null) return false
                    TabManager.reload()
                    return true
                }
                KeyEvent.KEYCODE_F -> {
                    if (TabManager.active == null) return false
                    leaveFullScreenIfNeeded()
                    onFindInPage()
                    return true
                }
                KeyEvent.KEYCODE_H -> {
                    leaveFullScreenIfNeeded()
                    drawer.closeImmediately()
                    showList(ListMode.HISTORY)
                    return true
                }
                KeyEvent.KEYCODE_J -> {
                    leaveFullScreenIfNeeded()
                    drawer.closeImmediately()
                    showList(ListMode.DOWNLOADS)
                    return true
                }
                KeyEvent.KEYCODE_P -> {
                    if (TabManager.active == null) return false
                    onPrint()
                    return true
                }
            }
        }

        if (event.isAltPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (TabManager.goBack()) return true
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (TabManager.goForward()) return true
            }
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_F5 -> {
                if (TabManager.active == null) false else {
                    TabManager.reload()
                    true
                }
            }
            KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_SEARCH -> {
                focusAddressFromKeyboard()
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                leaveFullScreenIfNeeded()
                drawer.toggle()
                true
            }
            KeyEvent.KEYCODE_ESCAPE -> when {
                drawer.isOpen() -> {
                    drawer.close()
                    true
                }
                webScreen.cancelEditingIfActive() -> true
                fullScreen -> {
                    exitPageOnly()
                    true
                }
                else -> false
            }
            else -> false
        }
    }

    private fun focusAddressFromKeyboard() {
        leaveFullScreenIfNeeded()
        drawer.closeImmediately()
        show(Screen.WEB)
        webScreen.focusAddress()
    }

    private fun openTabFromKeyboard(privateTab: Boolean) {
        leaveFullScreenIfNeeded()
        drawer.closeImmediately()
        if (openNewTabSafely(null, privateTab) == null) return
        show(Screen.WEB)
        if (privateTab) toast.say("Private tab — nothing will be recorded")
        webScreen.focusAddress()
    }

    private fun cycleTabsFromKeyboard(backward: Boolean): Boolean {
        val tabs = TabManager.tabs
        if (tabs.size < 2) return false
        val currentIndex = tabs.indexOf(TabManager.active).coerceAtLeast(0)
        val nextIndex = if (backward) {
            (currentIndex - 1 + tabs.size) % tabs.size
        } else {
            (currentIndex + 1) % tabs.size
        }
        TabManager.switchTo(tabs[nextIndex])
        show(Screen.WEB)
        return true
    }

    private fun closeActiveTabFromKeyboard(): Boolean {
        val tab = TabManager.active ?: return false
        leaveFullScreenIfNeeded()
        TabManager.close(tab)
        if (TabManager.tabs.isEmpty()) show(Screen.HOME) else show(Screen.WEB)
        return true
    }

    private fun submitFocusedNativeText(): Boolean =
        (this::webScreen.isInitialized && webScreen.submitAddressIfEditing()) ||
            (this::homeScreen.isInitialized && homeScreen.submitSearchIfFocused())

    private fun isMouseEvent(event: MotionEvent): Boolean =
        (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE

    private fun isPointerScrollEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
        val source = event.source
        return (source and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
            (source and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD ||
            (source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
    }

    /**
     * Generic motion reaches Activity coordinates on a number of Android mouse
     * adapters. GeckoView's PanZoomController explicitly requires coordinates
     * relative to its display surface, so copy and translate rather than sending
     * the original decor-relative event.
     */
    private fun forwardPointerScrollToGecko(event: MotionEvent): Boolean {
        if (current != Screen.WEB) return false
        val view = geckoView ?: return false
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) {
            return false
        }

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        var localX = event.rawX - location[0]
        var localY = event.rawY - location[1]
        // A few older mouse bridges report raw coordinates in window space.
        // Prefer true screen coordinates, then fall back only when those place
        // the pointer outside Gecko's visible surface.
        if (localX < 0f || localY < 0f || localX >= view.width || localY >= view.height) {
            view.getLocationInWindow(location)
            localX = event.x - location[0]
            localY = event.y - location[1]
        }
        if (localX < 0f || localY < 0f || localX >= view.width || localY >= view.height) return false

        val localEvent = MotionEvent.obtain(event)
        return try {
            localEvent.offsetLocation(localX - localEvent.x, localY - localEvent.y)
            // Call GeckoView directly once; returning true prevents Android's
            // fallback routing from delivering a duplicate wheel event.
            view.onGenericMotionEvent(localEvent)
        } finally {
            localEvent.recycle()
        }
    }

    private fun isMouseKeyEvent(event: KeyEvent): Boolean =
        (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE

    private fun hideSoftwareKeyboardIfSuppressed(force: Boolean = false) {
        if (UiKeys.shouldShowSoftwareKeyboard(this)) return
        val now = System.currentTimeMillis()
        if (!force && now - lastImeHideAt < 300L) return
        lastImeHideAt = now
        UiKeys.hideKeyboard(window.decorView, clearFocus = false)
    }

    /** Input-device configuration changes are inconsistent across OEMs. */
    private fun requestInputModeRefresh() {
        runOnUiThread {
            if (
                isFinishing ||
                isDestroyed ||
                !this::homeScreen.isInitialized ||
                !this::webScreen.isInitialized ||
                !this::settingsScreen.isInitialized
            ) return@runOnUiThread
            homeScreen.refreshInputMode()
            webScreen.refreshInputMode()
            settingsScreen.refreshInputMode()
            hideSoftwareKeyboardIfSuppressed(force = true)
        }
    }

    private fun registerInputDeviceListener() {
        if (inputDeviceListenerRegistered) return
        val manager = getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        manager.registerInputDeviceListener(inputDeviceListener, null)
        inputDeviceListenerRegistered = true
    }

    private fun unregisterInputDeviceListener() {
        if (!inputDeviceListenerRegistered) return
        (getSystemService(Context.INPUT_SERVICE) as? InputManager)
            ?.unregisterInputDeviceListener(inputDeviceListener)
        inputDeviceListenerRegistered = false
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

        // Gecko may request the IME after a page field gains focus. Native
        // chrome inputs opt out themselves; this insets safety net covers the
        // engine-owned field without taking focus away from it.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            if (!UiKeys.shouldShowSoftwareKeyboard(this) && insets.isVisible(WindowInsetsCompat.Type.ime())) {
                view.post { hideSoftwareKeyboardIfSuppressed() }
            }
            insets
        }

        return root
    }

    private fun wireUp() {
        /* ---- rail ---- */
        rail.home.onTap = { leaveFullScreenIfNeeded(); show(Screen.HOME) }
        rail.web.onTap = { show(Screen.WEB) }
        rail.tabs.onTap = { leaveFullScreenIfNeeded(); show(Screen.TABS) }
        rail.settings.onTap = { leaveFullScreenIfNeeded(); show(Screen.SETTINGS) }

        /* ---- web screen ---- */
        webScreen.onBack = { TabManager.goBack() }
        webScreen.onForward = { TabManager.goForward() }
        webScreen.onReload = { TabManager.reload() }
        webScreen.onTabs = { leaveFullScreenIfNeeded(); show(Screen.TABS) }
        webScreen.onMenu = { drawer.toggle() }
        webScreen.onToggleFullScreen = { togglePageOnly() }
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
        listScreen.onOpenDownload = { uri, mime -> openDownloadedFile(uri, mime) }
        listScreen.onDeleteBookmark = {
            invalidateDrawerBookmark()
            syncDrawer()
        }
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
                rail.setActive(rail.home)
                homeScreen.refresh()
                UiKeys.hideKeyboard(homeScreen)
            }

            Screen.WEB -> {
                rail.setActive(rail.web)
                var tab: Tab? = null
                if (ensureBrowserReady()) {
                    tab = TabManager.active ?: openNewTabSafely()
                    attachActiveTabToVisibleView(tab)
                }
                webScreen.syncTo(tab)
            }

            Screen.TABS -> {
                rail.setActive(rail.tabs)
                // Tab cards intentionally use their lightweight preview artwork.
                // Do not make a full compositor readback just to open this screen.
                tabsScreen.refresh(TabManager.tabs, TabManager.active?.id)
            }

            Screen.SETTINGS -> {
                rail.setActive(rail.settings)
                settingsScreen.render()
            }

            Screen.LIST -> { /* rail keeps whatever was selected before */ }
        }

        applyFullScreen()
        syncDrawer()
    }

    private fun showList(mode: ListMode) {
        visibleListMode = mode
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
     * Applies page-only mode. Normal browser chrome disappears, but the Web
     * surface retains one compact top-right toggle as the deliberate, reliable
     * way to return — there is no hidden hold gesture.
     */
    private fun applyFullScreen() {
        val pageOnly = fullScreen || videoFullScreen
        val onWeb = current == Screen.WEB

        rail.visibility = if (pageOnly) View.GONE else View.VISIBLE
        webScreen.setFullScreen(pageOnly && onWeb)

        // A toast, even for a short time, would intrude on the page surface.
        toast.setSuppressed(pageOnly)
        if (!pageOnly) webScreen.syncTo(TabManager.active)
        applySystemUi(pageOnly)
    }

    /**
     * Page-only mode hides both Android bars. On ordinary Web screens the
     * system-bar surfaces are white with dark icons, rather than leaving opaque
     * black strips beside a landscape cutout or below a white web page. Other
     * native screens retain their dark shell bars for visual continuity.
     */
    private fun applySystemUi(pageOnly: Boolean = fullScreen || videoFullScreen) {
        applyCutoutLayoutMode(pageOnly)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !pageOnly)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        if (pageOnly) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.hide(WindowInsetsCompat.Type.displayCutout())
        } else {
            val webSurface = current == Screen.WEB
            val barColor = if (webSurface) Color.WHITE else Ink.SHELL2
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Prevent Android from layering an automatic dark contrast band
                // over the white navigation/status surfaces on gesture devices.
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            controller.isAppearanceLightStatusBars = webSurface
            controller.isAppearanceLightNavigationBars = webSurface
            controller.show(WindowInsetsCompat.Type.navigationBars())
            controller.show(WindowInsetsCompat.Type.displayCutout())
            if (Prefs.hideStatusBar) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    /**
     * Normal browser UI stays outside a cutout. In explicit page-only mode use
     * the widest cutout policy the platform offers so the Gecko surface fills
     * the landscape frame instead of being surrounded by a black letterbox.
     */
    private fun applyCutoutLayoutMode(pageOnly: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val targetMode = when {
            !pageOnly -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (window.attributes.layoutInDisplayCutoutMode != targetMode) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = targetMode }
        }
    }

    /** Enter page-only mode from the visible top-right toggle or the three-dot menu. */
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

    private fun togglePageOnly() {
        when {
            fullScreen -> exitPageOnly()
            videoFullScreen -> {
                videoFullScreen = false
                TabManager.exitPageFullScreen()
                applyFullScreen()
            }
            else -> enterPageOnly()
        }
    }

    /**
     * Android Back or a page double tap restores browser chrome and ordinary
     * system bars. Also tell Gecko to leave content full-screen even when its
     * asynchronous callback has not arrived yet; otherwise that late state can
     * consume the first page-only exit action.
     */
    private fun exitPageOnly() {
        if (!fullScreen) return
        fullScreen = false
        videoFullScreen = false
        // This is a safe no-op when page content is not full-screen. Issuing it
        // unconditionally closes the callback race between a video entering
        // full-screen and the user's single Android Back gesture.
        TabManager.exitPageFullScreen()
        webScreen.cancelEditing()
        applyFullScreen()
    }

    private fun leaveFullScreenIfNeeded() = exitPageOnly()

    /* ================================================================== */
    /*  back handling                                                      */
    /* ================================================================== */

    /** Mouse side-Back follows browser navigation and never closes the app. */
    private fun handleMouseBack() {
        if (fullScreen) {
            exitPageOnly()
            return
        }
        if (drawer.isOpen()) {
            drawer.close()
            return
        }
        if (videoFullScreen) {
            videoFullScreen = false
            TabManager.exitPageFullScreen()
            applyFullScreen()
            return
        }
        if (current == Screen.LIST) {
            show(previousScreen)
            return
        }
        TabManager.goBack()
    }

    private fun handleBack() {
        if (fullScreen) {
            // Page-only mode is deliberately first: an invisible/stale drawer
            // state must never consume the one Back action promised to restore
            // normal browser controls and system bars.
            exitPageOnly()
            return
        }
        if (drawer.isOpen()) {
            drawer.close()
            return
        }
        if (videoFullScreen) {
            // Optimistically restore app chrome rather than waiting for Gecko's
            // asynchronous full-screen-exit callback.
            videoFullScreen = false
            TabManager.exitPageFullScreen()
            applyFullScreen()
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
            webScreen.cancelPendingNavigation()
            toast.say(getString(R.string.t_engine_session_failed))
            false
        }
    } catch (error: Throwable) {
        Log.e("MinimalBrowser", "Unable to load URL in bundled engine", error)
        webScreen.cancelPendingNavigation()
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
        syncDrawer()
    }

    override fun onActiveTabChanged(tab: Tab?) {
        if (current == Screen.WEB) {
            attachActiveTabToVisibleView(tab)
            webScreen.syncTo(tab)
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

    override fun onDownloadRequested(tab: Tab, response: WebResponse) {
        val request = Downloads.describe(response)
        if (request == null) {
            Downloads.discard(response)
            toast.say("This download did not include a usable file")
            return
        }
        if (isFinishing || isDestroyed) {
            Downloads.discard(response)
            return
        }

        // GeckoView owns this authorized stream. Avoid its short default read
        // timeout while a person reads the confirmation, then close it on every
        // cancel/dismiss path. This prevents a failed or invisible download from
        // looking like a completed one in the browser UI.
        runCatching { response.setReadTimeoutMillis(0) }
        val consumed = AtomicBoolean(false)
        fun discardResponse() {
            if (consumed.compareAndSet(false, true)) Downloads.discard(response)
        }
        fun startTransfer() {
            if (!consumed.compareAndSet(false, true)) return
            Downloads.start(applicationContext, response, request, downloadListener(tab.private))
        }

        val source = UrlBar.hostOf(request.sourceUrl).ifBlank { request.sourceUrl }
        val size = if (request.expectedBytes >= 0L) Fmt.bytes(request.expectedBytes) else "size unknown"
        val message = buildString {
            append(request.fileName)
            append("\n")
            append(request.mimeType)
            append(" · ")
            append(size)
            append("\nFrom ")
            append(source)
            append("\n\nSaved to your device Downloads folder.")
            if (request.requestExternalApp) append(" This file can be opened in a matching app after it is saved.")
            if (tab.private) append(" Private-tab downloads are not kept in the browser download list.")
        }
        try {
            val dialog = AlertDialog.Builder(this, R.style.Theme_Minimal_Dialog)
                .setTitle("Download file?")
                .setMessage(message)
                .setNegativeButton("Cancel") { _, _ -> discardResponse() }
                .setPositiveButton("Download") { _, _ -> startTransfer() }
                .create()
            dialog.setOnCancelListener { discardResponse() }
            dialog.setOnDismissListener { discardResponse() }
            dialog.show()
        } catch (error: Throwable) {
            Log.w("MinimalBrowser", "could not show download confirmation", error)
            discardResponse()
            toast.say("Could not show the download confirmation")
        }
    }

    private fun downloadListener(privateDownload: Boolean): Downloads.Listener = object : Downloads.Listener {
        override fun onStarted(request: Downloads.Request) {
            toast.say("Downloading ${request.fileName}")
            refreshDownloadsIfVisible(force = true)
        }

        override fun onProgress(request: Downloads.Request, receivedBytes: Long, expectedBytes: Long) {
            // The transfer posts progress at most a few times per second. Avoid
            // starting a database query for each byte callback, but make an open
            // Downloads screen feel alive once per second.
            refreshDownloadsIfVisible(force = false)
        }

        override fun onCompleted(download: Downloads.CompletedDownload) {
            if (privateDownload) {
                toast.say("Downloaded ${download.request.fileName}")
                refreshDownloadsIfVisible(force = true)
                return
            }
            // A history row is written only after MediaStore has published the
            // fully copied file. The old code inserted it at transfer start,
            // which produced dead, dummy download rows after any failure.
            try {
                chromeStore.execute {
                    val saved = runCatching {
                        DataStore.get(applicationContext).addDownload(
                            fileName = download.request.fileName,
                            url = download.request.sourceUrl,
                            mime = download.request.mimeType,
                            bytes = download.bytes,
                            localUri = download.localUri.toString()
                        )
                    }.isSuccess
                    runOnUiThread {
                        if (!isDestroyed) {
                            toast.say(
                                if (saved) "Downloaded ${download.request.fileName}"
                                else "File saved, but it could not be added to Downloads"
                            )
                            refreshDownloadsIfVisible(force = true)
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.w("MinimalBrowser", "could not record completed download", error)
                if (!isDestroyed) toast.say("Downloaded ${download.request.fileName}")
            }
        }

        override fun onFailed(request: Downloads.Request, reason: String) {
            toast.say(reason)
            refreshDownloadsIfVisible(force = true)
        }
    }

    private fun refreshDownloadsIfVisible(force: Boolean) {
        if (current != Screen.LIST || visibleListMode != ListMode.DOWNLOADS) return
        val now = System.currentTimeMillis()
        if (!force && now - lastDownloadsRefreshAt < 1_000L) return
        lastDownloadsRefreshAt = now
        listScreen.showDownloads()
    }

    private fun openDownloadedFile(uriString: String, mime: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: run {
            toast.say("This downloaded file is no longer available")
            return
        }
        val type = mime.substringBefore(';').trim().ifBlank { "application/octet-stream" }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            if (view.resolveActivity(packageManager) == null) {
                toast.say("No app can open this type of file")
                return
            }
            startActivity(Intent.createChooser(view, "Open ${uri.lastPathSegment ?: "download"}"))
        } catch (error: Throwable) {
            Log.w("MinimalBrowser", "could not open downloaded file", error)
            toast.say("Could not open this downloaded file")
        }
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
        togglePageOnly()
    }

    override fun onToggleShields(on: Boolean) {
        (application as BrowserApp).applyShields()
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
        val storeContext = applicationContext
        chromeStore.execute {
            val message = runCatching {
                val db = DataStore.get(storeContext)
                if (db.isBookmarked(url)) {
                    db.removeBookmark(url)
                    "Bookmark removed"
                } else {
                    db.addBookmark(url, title)
                    "Bookmarked"
                }
            }.getOrElse { "Could not update bookmark" }
            runOnUiThread {
                if (!isDestroyed) {
                    invalidateDrawerBookmark(url)
                    toast.say(message)
                    syncDrawer()
                }
            }
        }
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
            UiKeys.configureTextInput(this)
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_Minimal_Dialog)
            .setTitle("Find in page")
            .setView(field)
            .setNegativeButton("Clear") { _, _ -> TabManager.clearFind() }
            .setNeutralButton("Previous") { _, _ ->
                TabManager.findInPage(field.text?.toString().orEmpty(), forward = false)
            }
            .setPositiveButton("Find") { _, _ ->
                TabManager.findInPage(field.text?.toString().orEmpty())
            }
            .create()
        dialog.setOnShowListener {
            field.requestFocus()
            UiKeys.showKeyboard(field)
        }
        dialog.show()
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
                val storeContext = applicationContext
                chromeStore.execute {
                    val databaseCleared = runCatching {
                        val db = DataStore.get(storeContext)
                        if (checked[0]) db.clearHistory()
                        if (checked[3]) db.clearBlocked()
                    }.isSuccess
                    runOnUiThread {
                        if (!isDestroyed) {
                            // Keep the Gecko storage operation on the same UI boundary
                            // as session lifecycle changes.
                            TabManager.clearEngineData(
                                clearCookies = checked[1],
                                clearCache = checked[2],
                                clearHistory = checked[0]
                            )
                            toast.say(if (databaseCleared) "Browsing data cleared" else "Some browsing data could not be cleared")
                        }
                    }
                }
            }
            .show()
    }

    override fun onStatusBarVisibilityChanged() {
        applySystemUi()
    }

    override fun onMobileKeyboardVisibilityChanged() {
        requestInputModeRefresh()
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
    /*  drawer sync                                                        */
    /* ================================================================== */

    /**
     * The drawer is chrome, not page content. Never make navigation wait for a
     * synchronous SQLite bookmark lookup just to update its bookmark row.
     */
    private fun syncDrawer() {
        val url = TabManager.active?.url?.takeIf { it.isNotBlank() }
        val tabCount = TabManager.tabs.size
        if (url == null) {
            invalidateDrawerBookmark()
            drawer.syncState(tabCount, false)
            return
        }
        if (drawerBookmarkUrl == url) {
            drawer.syncState(tabCount, drawerBookmarkValue)
            return
        }

        drawerBookmarkUrl = url
        drawerBookmarkValue = false
        val request = ++drawerBookmarkRequest
        val storeContext = applicationContext
        drawer.syncState(tabCount, false)
        try {
            chromeStore.execute {
                val bookmarked = runCatching {
                    DataStore.get(storeContext).isBookmarked(url)
                }.getOrDefault(false)
                runOnUiThread {
                    if (!isDestroyed && request == drawerBookmarkRequest && drawerBookmarkUrl == url) {
                        drawerBookmarkValue = bookmarked
                        drawer.syncState(TabManager.tabs.size, bookmarked)
                    }
                }
            }
        } catch (error: Throwable) {
            Log.w("MinimalBrowser", "could not query drawer bookmark state", error)
        }
    }

    private fun invalidateDrawerBookmark(url: String? = null) {
        if (url == null || drawerBookmarkUrl == url) {
            drawerBookmarkUrl = null
            drawerBookmarkValue = false
            drawerBookmarkRequest++
        }
    }

    /* ================================================================== */
    /*  lifecycle                                                          */
    /* ================================================================== */

    override fun onResume() {
        super.onResume()
        registerInputDeviceListener()
        if (current == Screen.WEB && ensureBrowserReady()) {
            attachActiveTabToVisibleView(TabManager.active)
            TabManager.resumeActive()
        }
        applySystemUi()
        requestInputModeRefresh()
        homeScreen.refresh()
    }

    override fun onPause() {
        unregisterInputDeviceListener()
        TabManager.persist()
        TabManager.pauseActive()
        TabManager.detach()
        webScreen.detachGeckoView()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterInputDeviceListener()
        TabManager.detach()
        webScreen.detachGeckoView()
        TabManager.host = null
        chromeStore.shutdownNow()
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
        requestInputModeRefresh()
    }
}
