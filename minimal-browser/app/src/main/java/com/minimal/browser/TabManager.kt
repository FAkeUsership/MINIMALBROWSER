package com.minimal.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Stable tab controller built on Android System WebView.
 *
 * The former bundled-engine controller was the crash source: opening a first
 * browser session created a native child process and failed on the affected
 * Android 14+ device. System WebView is maintained by Android/Chrome and its
 * renderer-death callback lets us recover without taking down the Activity.
 */
object TabManager {

    private const val TAG = "TabManager"
    private val main = Handler(Looper.getMainLooper())

    val tabs = mutableListOf<Tab>()
    var active: Tab? = null
        private set

    /** Set by MainActivity while the UI is alive. */
    var host: Host? = null

    interface Host {
        fun activeContext(): Context?
        fun onTabsChanged()
        fun onActiveTabChanged(tab: Tab?)
        fun onProgress(tab: Tab, progress: Int)
        fun onPageFinished(tab: Tab, successful: Boolean)
        fun onSecurityChanged(tab: Tab)
        fun onBlockedOnPage(tab: Tab)
        fun onBlockedTotal(kind: String, host: String)
        fun onDownloadStarted(fileName: String)
        fun onRendererRecovered(tab: Tab)
    }

    /* ------------------------------------------------------------------ */
    /*  tab lifecycle                                                     */
    /* ------------------------------------------------------------------ */

    /** Creates a WebView tab. Caller catches this boundary for provider failures. */
    fun newTab(
        context: Context,
        url: String? = null,
        privateMode: Boolean = false,
        activate: Boolean = true
    ): Tab {
        val tab = Tab(privateMode)
        tab.webView = createWebView(context, tab)
        tabs += tab
        if (activate) switchTo(tab) else host?.onTabsChanged()
        if (!url.isNullOrBlank()) load(tab, url)
        return tab
    }

    fun switchTo(tab: Tab) {
        if (!tabs.contains(tab)) return
        if (active === tab) {
            host?.onActiveTabChanged(tab)
            return
        }
        try {
            active?.webView?.onPause()
        } catch (e: Throwable) {
            Log.w(TAG, "could not pause previous tab", e)
        }
        active = tab
        try {
            tab.webView?.onResume()
        } catch (e: Throwable) {
            Log.w(TAG, "could not resume tab", e)
        }
        updateNavigationState(tab)
        host?.onTabsChanged()
        host?.onActiveTabChanged(tab)
    }

    fun close(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        val wasActive = active === tab
        tabs.removeAt(index)
        if (wasActive) {
            active = tabs.getOrNull(max(0, index - 1))
            active?.let { updateNavigationState(it) }
            // MainActivity detaches the old view before it is destroyed below.
            host?.onActiveTabChanged(active)
        }
        destroyTab(tab)
        host?.onTabsChanged()
    }

    fun closeActive() {
        active?.let(::close)
    }

    fun closeAll() {
        val old = tabs.toList()
        tabs.clear()
        active = null
        host?.onActiveTabChanged(null)
        old.forEach(::destroyTab)
        host?.onTabsChanged()
    }

    fun pauseActive() {
        try {
            active?.webView?.onPause()
        } catch (e: Throwable) {
            Log.w(TAG, "WebView pause failed", e)
        }
    }

    fun resumeActive() {
        try {
            active?.webView?.onResume()
        } catch (e: Throwable) {
            Log.w(TAG, "WebView resume failed", e)
        }
    }

    /* ------------------------------------------------------------------ */
    /*  navigation                                                        */
    /* ------------------------------------------------------------------ */

    fun goBack(): Boolean {
        val tab = active ?: return false
        val view = tab.webView ?: return false
        return try {
            if (!view.canGoBack()) return false
            view.goBack()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "back navigation failed", e)
            false
        }
    }

    fun goForward(): Boolean {
        val tab = active ?: return false
        val view = tab.webView ?: return false
        return try {
            if (!view.canGoForward()) return false
            view.goForward()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "forward navigation failed", e)
            false
        }
    }

    fun reload() {
        try {
            active?.webView?.reload()
        } catch (e: Throwable) {
            Log.w(TAG, "reload failed", e)
        }
    }

    fun stop() {
        try {
            active?.webView?.stopLoading()
        } catch (e: Throwable) {
            Log.w(TAG, "stop failed", e)
        }
    }

    /** @return false when there is no live active tab. */
    fun loadInActive(url: String): Boolean {
        val tab = active ?: return false
        return load(tab, url)
    }

    fun findInPage(query: String, forward: Boolean = true) {
        val view = active?.webView ?: return
        try {
            if (query.isBlank()) {
                view.clearMatches()
            } else {
                view.findAllAsync(query)
                view.findNext(forward)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "find failed", e)
        }
    }

    fun clearFind() {
        try {
            active?.webView?.clearMatches()
        } catch (e: Throwable) {
            Log.w(TAG, "clear find failed", e)
        }
    }

    /* ------------------------------------------------------------------ */
    /*  settings, thumbnails, persistence                                 */
    /* ------------------------------------------------------------------ */

    fun applySettings() {
        tabs.forEach { tab ->
            try {
                configureSettings(tab)
            } catch (e: Throwable) {
                Log.w(TAG, "could not apply WebView settings", e)
            }
        }
    }

    fun captureThumbnail(tab: Tab) {
        val view = tab.webView ?: return
        if (view.width <= 0 || view.height <= 0) return
        try {
            val width = min(720, view.width)
            val height = max(1, (view.height.toFloat() * width / view.width).toInt())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val scale = width.toFloat() / view.width.toFloat()
            canvas.scale(scale, scale)
            view.draw(canvas)
            tab.thumbnail?.recycle()
            tab.thumbnail = bitmap
            host?.onTabsChanged()
        } catch (e: Throwable) {
            Log.w(TAG, "thumbnail capture failed", e)
        }
    }

    fun persist() {
        val context = host?.activeContext() ?: return
        val snapshot = tabs.map { tab ->
            TabRow(tab.id, tab.url, tab.title, tab.private, null)
        }
        background { DataStore.get(context).saveTabs(snapshot) }
    }

    /** Restores URLs only; opaque saved session state from old builds is ignored safely. */
    fun restore(context: Context): Boolean {
        val rows = runCatching { DataStore.get(context).loadTabs() }.getOrElse {
            Log.w(TAG, "tab restore database read failed", it)
            emptyList()
        }
        val restorable = rows.filter { !it.private && it.url.isNotBlank() }.take(8)
        if (restorable.isEmpty()) return false

        var restored = false
        for (row in restorable) {
            try {
                val tab = newTab(context, privateMode = false, activate = false)
                tab.id = row.id
                tab.title = row.title
                load(tab, row.url)
                restored = true
            } catch (e: Throwable) {
                Log.w(TAG, "skipping unrecoverable saved tab ${row.url}", e)
            }
        }
        return restored
    }

    /** Clear cookies/site data/cache on the UI thread after the Settings action. */
    fun clearWebData(clearCookies: Boolean, clearCache: Boolean, clearHistory: Boolean) {
        try {
            if (clearHistory) tabs.forEach { it.webView?.clearHistory() }
            if (clearCache) tabs.forEach { it.webView?.clearCache(true) }
            if (clearCookies) {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "web data clear failed", e)
        }
    }

    /* ------------------------------------------------------------------ */
    /*  WebView setup + callbacks                                         */
    /* ------------------------------------------------------------------ */

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, tab: Tab): WebView {
        // Set this before configureSettings(tab): that helper intentionally works
        // from the tab model so live Settings changes and newly created tabs share
        // exactly the same configuration path.
        val created = WebView(context)
        tab.webView = created
        return created.apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            settings.apply {
                javaScriptEnabled = Prefs.javaScriptEnabled
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(!Prefs.blockFingerprinting)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                textZoom = Prefs.textSizePercent
            }
            tab.defaultUserAgent = settings.userAgentString.orEmpty()
            configureSettings(tab)
            if (tab.private) {
                // System WebView storage is process-wide, so do not wipe normal
                // tabs' cache here. Private tabs are excluded from our history/
                // restore database and avoid writing new HTTP cache entries.
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                clearHistory()
            }
            webViewClient = BrowserClient(tab)
            webChromeClient = BrowserChrome(tab)
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                queueDownload(tab, url, userAgent, contentDisposition, mimeType, contentLength)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSettings(tab: Tab) {
        val view = tab.webView ?: return
        val settings = view.settings
        settings.javaScriptEnabled = Prefs.javaScriptEnabled
        settings.textZoom = Prefs.textSizePercent
        settings.setGeolocationEnabled(!Prefs.blockFingerprinting)
        settings.useWideViewPort = Prefs.desktopUserAgent
        settings.loadWithOverviewMode = Prefs.desktopUserAgent
        val defaultUa = tab.defaultUserAgent.ifBlank { settings.userAgentString.orEmpty() }
        if (tab.defaultUserAgent.isBlank()) tab.defaultUserAgent = defaultUa
        settings.userAgentString = if (Prefs.desktopUserAgent) desktopUserAgent(defaultUa) else defaultUa
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(
                if (Prefs.bwTheme) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            )
        }
    }

    private fun desktopUserAgent(defaultUa: String): String {
        if (defaultUa.isBlank()) {
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120 Safari/537.36"
        }
        return defaultUa
            .replace(Regex("\\sMobile(?:/[A-Za-z0-9.]+)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Android [^;)]+", RegexOption.IGNORE_CASE), "X11; Linux x86_64")
    }

    private fun load(tab: Tab, rawUrl: String): Boolean {
        val view = tab.webView ?: return false
        val url = upgradeToHttps(rawUrl)
        if (url.isBlank()) return false
        return try {
            tab.url = url
            tab.isSecure = url.startsWith("https://", ignoreCase = true)
            view.loadUrl(url)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "load failed for $url", e)
            host?.onPageFinished(tab, false)
            false
        }
    }

    private fun upgradeToHttps(url: String): String =
        if (Prefs.httpsOnly && url.startsWith("http://", ignoreCase = true)) {
            "https://${url.substringAfter("://")}"
        } else {
            url
        }

    private fun updateNavigationState(tab: Tab) {
        val view = tab.webView ?: return
        tab.canGoBack = runCatching { view.canGoBack() }.getOrDefault(false)
        tab.canGoForward = runCatching { view.canGoForward() }.getOrDefault(false)
        tab.isSecure = tab.url.startsWith("https://", ignoreCase = true)
    }

    private fun destroyTab(tab: Tab) {
        tab.thumbnail?.recycle()
        tab.thumbnail = null
        val view = tab.webView
        tab.webView = null
        try {
            view?.apply {
                (parent as? ViewGroup)?.removeView(this)
                stopLoading()
                clearHistory()
                removeAllViews()
                destroy()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "WebView destroy failed", e)
        }
    }

    /**
     * A WebView renderer may be killed independently of the Activity. Replace
     * only that tab's view on the main thread and reload its last page; never let
     * an old/background tab replace the visible active surface.
     */
    private fun recoverRenderer(tab: Tab, deadView: WebView) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { recoverRenderer(tab, deadView) }
            return
        }
        if (tab.webView !== deadView) return

        val lastUrl = tab.url
        tab.webView = null
        try {
            (deadView.parent as? ViewGroup)?.removeView(deadView)
            deadView.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "dead WebView cleanup failed", e)
        }

        try {
            val context = host?.activeContext() ?: return
            createWebView(context, tab)
            if (lastUrl.isNotBlank()) load(tab, lastUrl)
            host?.onRendererRecovered(tab)
            host?.onTabsChanged()
        } catch (e: Throwable) {
            Log.e(TAG, "WebView renderer replacement failed", e)
            tab.webView = null
            host?.onRendererRecovered(tab)
            host?.onTabsChanged()
        }
    }

    private fun queueDownload(
        tab: Tab,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val context = host?.activeContext() ?: return
        val name = Downloads.start(context, url, userAgent, contentDisposition, mimeType, contentLength)
            ?: return
        if (!tab.private) {
            background {
                DataStore.get(context).addDownload(name, url, mimeType.orEmpty(), contentLength)
            }
        }
        host?.onDownloadStarted(name)
    }

    private fun reportBlocked(tab: Tab, kind: String, url: String) {
        // shouldInterceptRequest may run off the UI thread. Keep tab state and
        // UI callbacks serialized on the main thread.
        val pageHost = UrlBar.hostOf(url)
        main.post {
            if (tabs.contains(tab)) {
                if (kind == "ad") tab.blockedAds++ else tab.blockedTrackers++
                host?.activeContext()?.let { context ->
                    background { DataStore.get(context).recordBlocked(pageHost, kind) }
                }
                host?.onBlockedOnPage(tab)
                host?.onBlockedTotal(kind, pageHost)
            }
        }
    }

    private fun background(block: () -> Unit) {
        Thread({
            try {
                block()
            } catch (e: Throwable) {
                Log.w(TAG, "background work failed", e)
            }
        }, "minimal-browser-store").start()
    }

    private class BrowserClient(private val tab: Tab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleNavigation(view, request.url.toString())

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleNavigation(view, url)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (tab.webView !== view) return
            tab.url = url
            tab.title = view.title.orEmpty().ifBlank { tab.title }
            tab.isSecure = url.startsWith("https://", ignoreCase = true)
            tab.blockedAds = 0
            tab.blockedTrackers = 0
            updateNavigationState(tab)
            host?.onProgress(tab, 10)
            host?.onSecurityChanged(tab)
            if (active === tab) host?.onActiveTabChanged(tab)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (tab.webView !== view) return
            tab.url = url
            view.title?.trim()?.takeIf { it.isNotEmpty() }?.let { tab.title = it }
            updateNavigationState(tab)
            if (!tab.private && url.startsWith("http", ignoreCase = true)) {
                host?.activeContext()?.let { context ->
                    background { DataStore.get(context).recordVisit(url, tab.title) }
                }
            }
            host?.onProgress(tab, 100)
            host?.onPageFinished(tab, true)
            if (active === tab) host?.onActiveTabChanged(tab)
            captureThumbnail(tab)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            if (tab.webView === view && request.isForMainFrame) {
                host?.onPageFinished(tab, false)
                updateNavigationState(tab)
                if (active === tab) host?.onActiveTabChanged(tab)
            }
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            // Never block the page the person deliberately opened. Only subresources
            // pass through the small local block list.
            if (request.isForMainFrame) return null
            return when (AdBlocker.check(request.url.toString())) {
                AdBlocker.Verdict.BLOCK_AD -> {
                    reportBlocked(tab, "ad", request.url.toString())
                    emptyResponse()
                }
                AdBlocker.Verdict.BLOCK_TRACKER -> {
                    reportBlocked(tab, "tracker", request.url.toString())
                    emptyResponse()
                }
                AdBlocker.Verdict.ALLOW -> null
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Returning true is vital: it tells Android the app handled a WebView
            // renderer death, preventing the Activity itself from being killed.
            recoverRenderer(tab, view)
            return true
        }

        private fun handleNavigation(view: WebView, rawUrl: String): Boolean {
            val scheme = runCatching { Uri.parse(rawUrl).scheme?.lowercase() }.getOrNull()
            if (scheme == "http" || scheme == "https") {
                if (Prefs.httpsOnly && scheme == "http") {
                    val secure = upgradeToHttps(rawUrl)
                    if (secure != rawUrl) view.loadUrl(secure)
                    return true
                }
                return false
            }
            // about:, data:, blob:, and javascript: are browser-internal URLs.
            if (scheme in setOf("about", "data", "blob", "javascript")) return false
            // mailto:, tel:, geo:, intent:, and market: go to an installed Android app.
            host?.activeContext()?.let { External.openUri(it, rawUrl) }
            return true
        }
    }

    private class BrowserChrome(private val tab: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, progress: Int) {
            if (tab.webView === view) host?.onProgress(tab, progress.coerceIn(0, 100))
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (tab.webView !== view) return
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { tab.title = it }
            if (active === tab) host?.onActiveTabChanged(tab)
            host?.onTabsChanged()
        }
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
}
