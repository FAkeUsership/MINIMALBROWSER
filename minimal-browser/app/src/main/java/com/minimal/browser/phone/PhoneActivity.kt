package com.minimal.browser.phone

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.minimal.browser.BrowserApp
import com.minimal.browser.DataStore
import com.minimal.browser.Downloads
import com.minimal.browser.Fmt
import com.minimal.browser.MainActivity
import com.minimal.browser.MediaScanner
import com.minimal.browser.Prefs
import com.minimal.browser.R
import com.minimal.browser.Tab
import com.minimal.browser.TabManager
import com.minimal.browser.UrlBar
import com.minimal.browser.ui.AppToast
import com.minimal.browser.ui.UiKeys
import com.minimal.browser.ui.dp
import com.minimal.browser.ui.icon
import com.minimal.browser.ui.roundRect
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Switches between the phone and tablet layouts. The chosen layout is saved first. */
object LayoutMode {
    fun switchTo(from: android.app.Activity, tablet: Boolean) {
        Prefs.tabletMode = tablet
        runCatching { TabManager.persist() }
        val target = if (tablet) MainActivity::class.java else PhoneActivity::class.java
        from.startActivity(Intent(from, target))
        from.finish()
    }
}

/**
 * Phone shell. Portrait only. Everything here is built from code so the look
 * stays exact and the animations stay in one place.
 *
 * Layout, top to bottom:
 *  - a floating address capsule (Web only), with a thin progress line
 *  - the page itself, full width, below the capsule
 *  - a floating ink dock: back, forward, the download button, tabs, menu
 *
 * Home, Library, and Settings replace the page in the same content area.
 */
class PhoneActivity : AppCompatActivity(), TabManager.Host {

    enum class Screen { HOME, WEB, LIBRARY, SETTINGS }

    /** One media file being downloaded from the media sheet. Keyed by its URL. */
    class Job(val item: MediaScanner.Item) {
        var state: State = State.RUNNING
        var downloadId: String? = null
        var received = 0L
        var expected = -1L
        var startedAt = System.currentTimeMillis()
        var reason = ""
        var localUri: String? = null
        var mime: String = item.mimeType

        val fraction: Float
            get() = if (expected > 0) (received.toFloat() / expected).coerceIn(0f, 1f) else 0f

        /** Average speed since the transfer started, in bytes per second. */
        val bytesPerSecond: Long
            get() {
                val seconds = (System.currentTimeMillis() - startedAt).coerceAtLeast(1) / 1000.0
                return (received / seconds).toLong()
            }

        enum class State { RUNNING, DONE, FAILED }
    }

    val main = Handler(Looper.getMainLooper())
    val chromeStore = Executors.newSingleThreadExecutor()
    val jobs = LinkedHashMap<String, Job>()

    /** Media found by the last automatic scan of the active page (videos only). */
    var detectedMedia: List<MediaScanner.Item> = emptyList()
        private set
    /** Called when a job changes so an open media sheet can redraw its row. */
    private val jobListeners = ArrayList<(String) -> Unit>()
    fun addJobListener(listener: (String) -> Unit) { jobListeners += listener }
    fun removeJobListener(listener: (String) -> Unit) { jobListeners.remove(listener) }
    fun activeJobCount(): Int = jobs.values.count { it.state == Job.State.RUNNING }

    /** Every download change goes through here, so the dock and open sheets stay in step. */
    fun notifyJob(url: String) {
        if (::mediaButton.isInitialized) mediaButton.setCount(activeJobCount())
        jobListeners.toList().forEach { it(url) }
    }

    lateinit var toast: AppToast
    lateinit var sheet: SheetHost
    var current: Screen = Screen.HOME
        private set

    private lateinit var root: FrameLayout
    private lateinit var screens: FrameLayout
    private lateinit var webHost: FrameLayout
    private lateinit var topBar: FrameLayout
    private lateinit var dockWrap: FrameLayout
    private lateinit var homeView: HomeView
    private lateinit var libraryView: LibraryView
    private lateinit var settingsView: SettingsView
    private var geckoView: GeckoView? = null

    private lateinit var capsule: LinearLayout
    private lateinit var addressLabel: TextView
    private lateinit var addressInput: EditText
    private lateinit var addressLock: ImageView
    private lateinit var progressLine: View
    private lateinit var backButton: DockButton
    private lateinit var forwardButton: DockButton
    private lateinit var mediaButton: DockButton
    private lateinit var foundPill: TextView
    private lateinit var tabsButton: DockButton
    private lateinit var menuButton: DockButton

    private var insetTop = 0
    private var insetBottom = 0
    private var editingAddress = false
    private var fullscreenVideo = false
    private var restoreAttempted = false
    private var detectToken = 0
    private var lastWebScreen = false
    private var redirecting = false

    /* ================================================================== */
    /*  lifecycle                                                          */
    /* ================================================================== */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (Prefs.tabletMode) {
            // The saved layout is tablet: hand over to the rail UI.
            redirecting = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        TabManager.host = this
        buildUi()
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        handleIntent(intent)
        if (current == Screen.HOME && TabManager.active?.url?.isNotBlank() == true) {
            show(Screen.WEB)
        }
    }

    override fun onStart() {
        super.onStart()
        if (redirecting) return
        if (current == Screen.WEB) attachVisibleTab()
    }

    override fun onResume() {
        super.onResume()
        if (redirecting) return
        TabManager.resumeActive()
        refreshChrome()
    }

    override fun onStop() {
        if (!redirecting) {
            runCatching { TabManager.persist() }
            TabManager.pauseActive()
            if (TabManager.host === this) TabManager.detach()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (!redirecting) {
            TabManager.detach()
            // Only release the host if it is still us. A Phone/Tablet switch may
            // already have handed it to the next activity.
            if (TabManager.host === this) TabManager.host = null
            chromeStore.shutdownNow()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.dataString
        when {
            !uri.isNullOrEmpty() -> openUrl(uri)
            intent?.getStringExtra("query")?.isNotBlank() == true ->
                openInput(intent.getStringExtra("query")!!)
        }
    }

    /* ================================================================== */
    /*  construction                                                       */
    /* ================================================================== */

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Palette.PAPER) }

        screens = FrameLayout(this)
        homeView = HomeView(this)
        libraryView = LibraryView(this)
        settingsView = SettingsView(this)
        for (v in listOf(homeView, libraryView, settingsView)) {
            screens.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
            v.visibility = View.GONE
        }
        root.addView(screens, FrameLayout.LayoutParams(MATCH, MATCH))

        webHost = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(webHost, FrameLayout.LayoutParams(MATCH, MATCH))

        topBar = buildAddressBar()
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.TOP))

        dockWrap = buildDock()
        root.addView(dockWrap, FrameLayout.LayoutParams(MATCH, dp(70), Gravity.BOTTOM))

        foundPill = buildFoundPill()
        root.addView(foundPill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(50), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))

        toast = AppToast(this)
        root.addView(toast, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))
        sheet = SheetHost(this, root)

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            insetTop = bars.top
            insetBottom = bars.bottom
            applyInsets()
            insets
        }
        applyChromeVisibility()
    }

    private fun buildAddressBar(): FrameLayout {
        val bar = FrameLayout(this)
        capsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(28, Palette.CARD, Palette.LINE)
            setPadding(dp(16), 0, dp(6), 0)
            elevation = dp(2).toFloat()
            isClickable = true
            setOnClickListener { if (!editingAddress) beginEditing() }
        }
        addressLock = ImageView(this).apply {
            icon(R.drawable.ic_lock, Palette.INK_SOFT)
            scaleType = ImageView.ScaleType.CENTER
        }
        capsule.addView(addressLock, LinearLayout.LayoutParams(dp(18), dp(18)))
        addressLabel = label("", 15f, Palette.INK, Type.title).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        capsule.addView(addressLabel, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dp(10) })

        addressInput = EditText(this).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Palette.INK)
            textSize = 15f
            typeface = Type.title
            background = null
            setPadding(0, 0, 0, 0)
            visibility = View.GONE
            setOnEditorActionListener { v, _, _ ->
                val text = v.text.toString()
                endEditing()
                openInput(text)
                true
            }
            setOnFocusChangeListener { _, focused -> if (!focused && editingAddress) endEditing() }
        }
        capsule.addView(addressInput, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dp(10) })

        val reload = ImageView(this).apply {
            icon(R.drawable.ic_refresh, Palette.INK)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Reload"
            springPress(0.82f)
            setOnClickListener { TabManager.reload() }
        }
        capsule.addView(reload, LinearLayout.LayoutParams(dp(42), dp(42)))

        bar.addView(capsule, FrameLayout.LayoutParams(MATCH, dp(50), Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(14); marginEnd = dp(14)
        })
        progressLine = View(this).apply {
            setBackgroundColor(Palette.ACCENT)
            pivotX = 0f
            scaleX = 0f
            alpha = 0f
        }
        bar.addView(progressLine, FrameLayout.LayoutParams(MATCH, dp(2.5f).toInt(), Gravity.BOTTOM).apply {
            marginStart = dp(34); marginEnd = dp(34)
        })
        return bar
    }

    private fun buildDock(): FrameLayout {
        val wrap = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundRect(34, Palette.INK)
            elevation = dp(10).toFloat()
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), 0, dp(6), 0)
        }
        backButton = DockButton(this, R.drawable.ic_back, "Back")
        forwardButton = DockButton(this, R.drawable.ic_fwd, "Forward")
        mediaButton = DockButton(this, R.drawable.ic_download, "Downloads")
        tabsButton = DockButton(this, R.drawable.ic_tabs, "Tabs")
        menuButton = DockButton(this, R.drawable.ic_menu, "Menu")

        backButton.setOnClickListener { if (current == Screen.WEB) TabManager.goBack() }
        forwardButton.setOnClickListener { if (current == Screen.WEB) TabManager.goForward() }
        mediaButton.setOnClickListener { showLibrary(2) }
        tabsButton.setOnClickListener { showTabs() }
        menuButton.setOnClickListener { showMenu() }

        for (b in listOf(backButton, forwardButton, mediaButton, tabsButton, menuButton)) {
            pill.addView(b, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        wrap.addView(pill, FrameLayout.LayoutParams(MATCH, dp(64), Gravity.BOTTOM).apply {
            marginStart = dp(16); marginEnd = dp(16)
        })
        return wrap
    }

    private fun applyInsets() {
        (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop + dp(6)
        (webHost.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = if (fullscreenVideo) 0 else insetTop + dp(64)
            bottomMargin = 0
        }
        (dockWrap.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom + dp(10)
        (foundPill.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom + dp(92)
        screens.setPadding(0, insetTop, 0, insetBottom + dp(92))
        (toast.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom + dp(96)
        topBar.requestLayout()
        webHost.requestLayout()
        dockWrap.requestLayout()
    }

    /* ================================================================== */
    /*  navigation                                                         */
    /* ================================================================== */

    /** Shows one screen. Web attaches the engine view; every other screen releases it. */
    fun show(screen: Screen) {
        if (screen != Screen.WEB && editingAddress) endEditing()
        current = screen
        if (screen != Screen.WEB) lastWebScreen = false
        val target: View? = when (screen) {
            Screen.HOME -> homeView
            Screen.LIBRARY -> libraryView
            Screen.SETTINGS -> settingsView
            Screen.WEB -> null
        }
        for (v in listOf(homeView, libraryView, settingsView)) {
            if (v !== target && v.visibility == View.VISIBLE) {
                v.animate().cancel()
                v.animate().alpha(0f).setDuration(160).withEndAction { v.visibility = View.GONE }.start()
            }
        }
        if (target != null) {
            target.alpha = 1f
            target.visibility = View.VISIBLE
            target.enter()
        }
        when (target) {
            homeView -> homeView.refresh()
            settingsView -> settingsView.refresh()
        }
        webHost.visibility = if (screen == Screen.WEB) View.VISIBLE else View.GONE
        if (screen == Screen.WEB) {
            lastWebScreen = true
            webHost.enter()
            attachVisibleTab()
        } else {
            TabManager.detach()
        }
        applyChromeVisibility()
        refreshChrome()
        updateFoundPill()
    }

    /** The floating "Download N files found" pill, shown over a web page that has media. */
    private fun buildFoundPill(): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Type.display
        letterSpacing = 0.01f
        setTextColor(Palette.WHITE)
        background = roundRect(99, Palette.ACCENT)
        setPadding(dp(22), 0, dp(22), 0)
        elevation = dp(8).toFloat()
        visibility = View.GONE
        springPress(0.95f)
        setOnClickListener { showMedia() }
    }

    private fun updateFoundPill() {
        if (!::foundPill.isInitialized) return
        val count = detectedMedia.size
        val wanted = count > 0 && current == Screen.WEB && !fullscreenVideo && !sheet.isShowing
        foundPill.text = "Download  ·  $count ${if (count == 1) "file" else "files"} found"
        foundPill.animate().cancel()
        if (wanted) {
            if (foundPill.visibility != View.VISIBLE) {
                foundPill.visibility = View.VISIBLE
                foundPill.alpha = 0f
                foundPill.translationY = dp(28).toFloat()
            }
            foundPill.animate().alpha(1f).translationY(0f).setDuration(380)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
        } else if (foundPill.visibility == View.VISIBLE) {
            foundPill.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(200)
                .withEndAction { foundPill.visibility = View.GONE }.start()
        }
    }

    private fun applyChromeVisibility() {
        val web = current == Screen.WEB
        topBar.visibility = if (web && !fullscreenVideo) View.VISIBLE else View.GONE
        dockWrap.visibility = if (fullscreenVideo) View.GONE else View.VISIBLE
        applyInsets()
    }

    private fun ensureGecko(): GeckoView {
        geckoView?.let { return it }
        val view = GeckoView(this).apply { setBackgroundColor(Palette.PAPER) }
        geckoView = view
        webHost.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        return view
    }

    private fun attachVisibleTab() {
        if (current != Screen.WEB || !ensureBrowserReady()) return
        TabManager.attach(ensureGecko())
    }

    /** Starts the engine and restores saved tabs once. False when the engine cannot run. */
    fun ensureBrowserReady(): Boolean {
        val runtime = BrowserApp.ensureRuntime(this) ?: run {
            toast.say("The browser engine could not start on this device")
            return false
        }
        TabManager.init(runtime)
        if (!restoreAttempted) {
            restoreAttempted = true
            if (TabManager.tabs.isEmpty()) {
                val restored = runCatching { TabManager.restore(this) }.getOrElse { error ->
                    Log.e("MinimalBrowser", "Could not restore tabs", error)
                    false
                }
                if (restored && TabManager.active == null) {
                    TabManager.tabs.firstOrNull()?.let { TabManager.switchTo(it) }
                }
            }
        }
        return true
    }

    fun openInput(raw: String, newTab: Boolean = false) {
        val text = raw.trim()
        val url = UrlBar.resolve(text)
        if (url.isEmpty()) return
        toast.say(if (UrlBar.isUrl(text)) "Opening ${UrlBar.hostOf(url)}" else "Searching for $text")
        openUrl(url, newTab)
    }

    fun openUrl(url: String, newTab: Boolean = false) {
        if (!ensureBrowserReady()) return
        if (newTab || TabManager.active == null) TabManager.newTab(url) else TabManager.loadInActive(url)
        show(Screen.WEB)
    }

    /** New blank tab on the Home screen. */
    fun newBlankTab(privateMode: Boolean = false) {
        if (!ensureBrowserReady()) return
        TabManager.newTab(null, privateMode, activate = true)
        show(Screen.HOME)
    }

    /** Opens the saved tabs from the last session, then shows the page. */
    fun resumeLastSession() {
        if (!ensureBrowserReady()) return
        if (TabManager.active != null) show(Screen.WEB)
        else toast.say("No saved tabs to resume")
    }

    fun openTab(tab: Tab) {
        if (!ensureBrowserReady()) return
        TabManager.switchTo(tab)
        show(if (tab.url.isBlank()) Screen.HOME else Screen.WEB)
    }

    private fun handleBack() {
        when {
            sheet.isShowing -> sheet.dismiss()
            editingAddress -> endEditing()
            fullscreenVideo -> TabManager.exitPageFullScreen()
            current == Screen.WEB && TabManager.goBack() -> Unit
            current == Screen.WEB -> show(Screen.HOME)
            current == Screen.LIBRARY || current == Screen.SETTINGS ->
                show(if (lastWebScreen && TabManager.active != null) Screen.WEB else Screen.HOME)
            else -> finish()
        }
    }

    /* ================================================================== */
    /*  address bar                                                        */
    /* ================================================================== */

    private fun beginEditing() {
        if (current != Screen.WEB) return
        editingAddress = true
        addressLabel.visibility = View.GONE
        addressInput.visibility = View.VISIBLE
        addressInput.setText(TabManager.active?.url.orEmpty())
        UiKeys.configureTextInput(addressInput)
        addressInput.requestFocus()
        addressInput.selectAll()
        UiKeys.showKeyboard(addressInput)
    }

    private fun endEditing() {
        if (!editingAddress) return
        editingAddress = false
        UiKeys.hideKeyboard(addressInput, clearFocus = true)
        addressInput.visibility = View.GONE
        addressLabel.visibility = View.VISIBLE
    }

    private fun refreshChrome() {
        val tab = TabManager.active
        val url = tab?.url.orEmpty()
        addressLabel.text = if (url.isBlank()) "Search or type a link" else UrlBar.shortLabel(url)
        addressLabel.setTextColor(if (url.isBlank()) Palette.INK_FAINT else Palette.INK)
        addressLock.visibility = if (tab?.isSecure == true) View.VISIBLE else View.INVISIBLE
        backButton.setEnabledState(tab?.canGoBack == true)
        forwardButton.setEnabledState(tab?.canGoForward == true)
        tabsButton.setCount(TabManager.tabs.size)
        mediaButton.setCount(activeJobCount())
    }

    private fun updateProgress(percent: Int) {
        val p = (percent.coerceIn(0, 100)) / 100f
        if (percent in 1..99) {
            progressLine.animate().cancel()
            progressLine.alpha = 1f
            progressLine.animate().scaleX(p).setDuration(180).start()
        } else {
            progressLine.animate().cancel()
            progressLine.animate().alpha(0f).setDuration(260).withEndAction { progressLine.scaleX = 0f }.start()
        }
    }

    /* ================================================================== */
    /*  sheets                                                             */
    /* ================================================================== */

    fun showTabs() = sheet.show(TabsSheet(this), 0.84f)

    fun showMenu() = sheet.show(MenuSheet(this), 0.80f)

    fun showLibrary(section: Int) {
        libraryView.select(section)
        show(Screen.LIBRARY)
    }

    fun showSettings() = show(Screen.SETTINGS)

    fun showMedia() {
        val url = TabManager.active?.url.orEmpty()
        if (!url.startsWith("http")) {
            toast.say("Open a web page first, then look for media")
            return
        }
        val media = MediaSheet(this, url)
        sheet.show(media, 0.92f) {
            removeJobListener(media.jobListener)
            updateFoundPill()
        }
        updateFoundPill()
    }

    /* ================================================================== */
    /*  media detection and downloads                                      */
    /* ================================================================== */

    /** Quietly looks for videos on the page so the dock can show a badge. */
    private fun detectMedia(pageUrl: String) {
        val token = ++detectToken
        if (!pageUrl.startsWith("http")) {
            setDetected(emptyList())
            return
        }
        Thread({
            val found = runCatching {
                MediaScanner.scan(pageUrl, Prefs.mediaMinImagePx, Prefs.mediaMinVideoMb, includeImages = false)
            }.getOrNull()?.items.orEmpty()
            main.post { if (token == detectToken && !isDestroyed) setDetected(found) }
        }, "phone-media-detect").start()
    }

    private fun setDetected(items: List<MediaScanner.Item>) {
        detectedMedia = items
        updateFoundPill()
    }

    fun jobFor(url: String): Job? = jobs[url]

    /** Starts one media file. Progress and completion flow back through [onJobChanged]. */
    fun downloadMedia(item: MediaScanner.Item) {
        jobs[item.url]?.let { if (it.state == Job.State.RUNNING) return }
        val job = Job(item)
        jobs[item.url] = job
        val referer = TabManager.active?.url.orEmpty()
        val started = Downloads.startUrl(
            applicationContext, item.url, item.fileName, item.mimeType,
            referer, MediaScanner.USER_AGENT, downloadListener(job, privateDownload = false)
        )
        if (!started) {
            job.state = Job.State.FAILED
            job.reason = "Could not create a file in Downloads"
        }
        notifyJob(item.url)
    }

    fun cancelMedia(url: String) {
        val job = jobs[url] ?: return
        job.downloadId?.let { Downloads.cancel(it) }
    }

    private fun downloadListener(job: Job?, privateDownload: Boolean): Downloads.Listener =
        object : Downloads.Listener {
            override fun onStarted(request: Downloads.Request) {
                job?.let {
                    it.downloadId = request.id
                    it.expected = request.expectedBytes
                    it.startedAt = System.currentTimeMillis()
                }
                if (job == null) toast.say("Downloading ${request.fileName}")
                job?.let { notifyJob(it.item.url) }
            }

            override fun onProgress(request: Downloads.Request, receivedBytes: Long, expectedBytes: Long) {
                val j = job ?: return
                j.received = receivedBytes
                j.expected = expectedBytes
                notifyJob(j.item.url)
            }

            override fun onCompleted(download: Downloads.CompletedDownload) {
                val j = job
                if (j != null) {
                    j.state = Job.State.DONE
                    j.received = download.bytes
                    j.expected = download.bytes
                    j.localUri = download.localUri.toString()
                    j.mime = download.request.mimeType
                    notifyJob(j.item.url)
                }
                toast.say("Saved ${download.request.fileName}")
                if (!privateDownload) {
                    val name = download.request.fileName
                    val src = download.request.sourceUrl
                    val mime = download.request.mimeType
                    val bytes = download.bytes
                    val uri = download.localUri.toString()
                    val storeContext = applicationContext
                    chromeStore.execute {
                        runCatching {
                            DataStore.get(storeContext).addDownload(name, src, mime, bytes, uri)
                        }.onFailure { Log.w("MinimalBrowser", "could not record download", it) }
                    }
                }
            }

            override fun onFailed(request: Downloads.Request, reason: String) {
                val j = job
                if (j != null) {
                    j.state = Job.State.FAILED
                    j.reason = reason
                    notifyJob(j.item.url)
                } else if (!reason.equals(Downloads.CANCELLED_REASON, ignoreCase = true)) {
                    toast.say(reason)
                }
            }
        }

    /** Opens a finished download with whichever app the phone offers for its type. */
    fun openDownloadedFile(uriString: String, mime: String) {
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

    /* ================================================================== */
    /*  layout mode                                                        */
    /* ================================================================== */

    fun switchToTablet() = LayoutMode.switchTo(this, tablet = true)

    fun clearBrowsingData() {
        val labels = arrayOf("History", "Cookies & site data", "Cache", "Blocked-request log")
        val checked = booleanArrayOf(true, true, true, false)
        AlertDialog.Builder(this, R.style.Theme_Minimal_Dialog)
            .setTitle("Clear browsing data")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                val storeContext = applicationContext
                chromeStore.execute {
                    val ok = runCatching {
                        val db = DataStore.get(storeContext)
                        if (checked[0]) db.clearHistory()
                        if (checked[3]) db.clearBlocked()
                    }.isSuccess
                    main.post {
                        if (isDestroyed) return@post
                        TabManager.clearEngineData(
                            clearCookies = checked[1], clearCache = checked[2], clearHistory = checked[0]
                        )
                        toast.say(if (ok) "Browsing data cleared" else "Some browsing data could not be cleared")
                        homeView.refresh()
                    }
                }
            }
            .show()
    }

    /* ================================================================== */
    /*  TabManager.Host                                                    */
    /* ================================================================== */

    override fun activeContext(): Context? = this

    override fun onTabsChanged() {
        refreshChrome()
    }

    override fun onActiveTabChanged(tab: Tab?) {
        refreshChrome()
        if (current == Screen.WEB) attachVisibleTab()
        if (tab != null) detectMedia(tab.url)
        if (tab == null || tab.url.isBlank()) setDetected(emptyList())
    }

    override fun onProgress(tab: Tab, progress: Int) {
        if (tab === TabManager.active) updateProgress(progress)
    }

    override fun onPageFinished(tab: Tab, successful: Boolean) {
        if (tab !== TabManager.active) return
        updateProgress(100)
        refreshChrome()
        if (successful) detectMedia(tab.url)
    }

    override fun onSecurityChanged(tab: Tab) {
        if (tab === TabManager.active) refreshChrome()
    }

    override fun onBlockedOnPage(tab: Tab) {}

    override fun onEnterVideoFullScreen(fullScreen: Boolean) {
        // Let the sensor pick the orientation for video. Leaving restores portrait.
        requestedOrientation = if (fullScreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        fullscreenVideo = fullScreen
        applyChromeVisibility()
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
        runCatching { response.setReadTimeoutMillis(0) }
        val consumed = AtomicBoolean(false)
        fun discard() {
            if (consumed.compareAndSet(false, true)) Downloads.discard(response)
        }
        val size = if (request.expectedBytes >= 0L) Fmt.bytes(request.expectedBytes) else "size unknown"
        val source = UrlBar.hostOf(request.sourceUrl).ifBlank { request.sourceUrl }
        AlertDialog.Builder(this, R.style.Theme_Minimal_Dialog)
            .setTitle("Download file?")
            .setMessage("${request.fileName}\n${request.mimeType} · $size\nFrom $source")
            .setNegativeButton("Cancel") { _, _ -> discard() }
            .setPositiveButton("Download") { _, _ ->
                if (consumed.compareAndSet(false, true)) {
                    Downloads.start(applicationContext, response, request, downloadListener(null, tab.private))
                }
            }
            .setOnCancelListener { discard() }
            .setOnDismissListener { discard() }
            .show()
    }

    override fun onTabWantsToClose(tab: Tab) {
        TabManager.close(tab)
    }

    override fun onSessionRecovered(tab: Tab) {
        if (tab === TabManager.active && current == Screen.WEB) {
            attachVisibleTab()
            toast.say("Page renderer restarted")
        }
        refreshChrome()
    }

    override fun onSessionFailure(tab: Tab, reason: String) {
        toast.say(reason)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

/**
 * Dock item. The middle accent button is a round, larger control with a soft
 * ripple halo while media is available. The other items get a sliding pill
 * when they represent the current state.
 */
class DockButton(
    context: Context,
    iconRes: Int,
    label: String,
    private val accent: Boolean = false
) : FrameLayout(context) {

    private val halo = View(context).apply {
        background = context.roundRect(99, Palette.ACCENT_SOFT)
        alpha = 0f
        visibility = View.GONE
    }
    private val core = View(context).apply {
        background = if (accent) context.roundRect(99, Palette.ACCENT)
        else context.roundRect(99, 0x24FFFFFF)
        alpha = if (accent) 1f else 0f
        scaleX = if (accent) 1f else 0.5f
        scaleY = if (accent) 1f else 0.5f
    }
    private val glyph = context.icon(iconRes, Palette.PAPER, label)
    private val badge = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 10f
        typeface = Type.display
        includeFontPadding = false
        setPadding(context.dp(5), 0, context.dp(5), 0)
        background = context.roundRect(9, if (accent) Palette.WHITE else Palette.ACCENT)
        setTextColor(if (accent) Palette.ACCENT else Palette.WHITE)
        visibility = View.GONE
    }
    private val pulses = ArrayList<ObjectAnimator>()
    private var count = 0

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true
        contentDescription = label
        springPress(0.86f)
        val coreSize = dp(if (accent) 56 else 44)
        addView(halo, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        addView(core, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
        addView(glyph, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        addView(badge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(18), Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            leftMargin = dp(if (accent) 14 else 12)
            topMargin = dp(if (accent) 2 else 4)
        })
    }

    private fun dp(v: Int) = context.dp(v)

    fun setSelectedState(on: Boolean) {
        if (accent) return
        core.animate().cancel()
        core.animate().alpha(if (on) 1f else 0f)
            .scaleX(if (on) 1f else 0.5f).scaleY(if (on) 1f else 0.5f)
            .setDuration(320).setInterpolator(OvershootInterpolator(1.8f)).start()
    }

    fun setEnabledState(on: Boolean) {
        isEnabled = on
        glyph.alpha = if (on) 1f else 0.3f
    }

    fun setCount(n: Int) {
        val previous = count
        count = n
        badge.text = if (n > 99) "99+" else n.toString()
        badge.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (n > previous && n > 0) {
            badge.scaleX = 0.3f
            badge.scaleY = 0.3f
            badge.animate().scaleX(1f).scaleY(1f).setDuration(460)
                .setInterpolator(OvershootInterpolator(2.6f)).start()
        }
        if (accent) setPulsing(n > 0)
    }

    /** Ripple halo that repeats while there is something to download. */
    private fun setPulsing(on: Boolean) {
        if (on && pulses.isEmpty()) {
            halo.visibility = View.VISIBLE
            val sx = ObjectAnimator.ofFloat(halo, SCALE_X, 0.9f, 1.55f)
            val sy = ObjectAnimator.ofFloat(halo, SCALE_Y, 0.9f, 1.55f)
            val a = ObjectAnimator.ofFloat(halo, ALPHA, 0.6f, 0f)
            for (anim in listOf(sx, sy, a)) {
                anim.duration = 1500
                anim.repeatCount = ObjectAnimator.INFINITE
                anim.repeatMode = ObjectAnimator.RESTART
                anim.interpolator = DecelerateInterpolator()
                anim.start()
                pulses += anim
            }
        } else if (!on && pulses.isNotEmpty()) {
            pulses.forEach { it.cancel() }
            pulses.clear()
            halo.visibility = View.GONE
            halo.scaleX = 1f
            halo.scaleY = 1f
        }
    }
}
