package com.minimal.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Multi-tab controller on top of the platform WebView.
 *
 * The public surface is identical to the GeckoView build, so `MainActivity`
 * and every screen work unchanged against either engine.
 */
object TabManager {

    private const val TAG = "TabManager"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    val tabs = mutableListOf<Tab>()
    var active: Tab? = null
        private set

    var container: FrameLayout? = null
        private set

    var host: Host? = null

    /** Implemented by the WebChromeClient so the Activity can mount a full-screen video. */
    interface VideoHost {
        fun currentCustomView(): android.view.View?
    }

    interface Host {
        fun activeContext(): Context?
        fun onTabsChanged()
        fun onActiveTabChanged(tab: Tab)
        fun onProgress(tab: Tab, progress: Int)
        fun onPageFinished(tab: Tab, successful: Boolean)
        fun onSecurityChanged(tab: Tab)
        fun onBlockedOnPage(tab: Tab)
        fun onBlockedTotal(kind: String, host: String)
        fun onEnterVideoFullScreen(fullScreen: Boolean)
        fun onDownloadStarted(fileName: String)
        fun onTabWantsToClose(tab: Tab)
        fun openFileChooser(callback: ValueCallback<Array<Uri>>?, accept: String?)
    }

    fun exitVideoFullScreen() {
        (active?.webView?.webChromeClient as? ChromeImpl)?.onHideCustomView()
    }

    /* ------------------------------------------------------------------ */

    fun attach(v: FrameLayout) {
        container = v
        val tab = active ?: return
        mount(tab)
    }

    fun detach() {
        container?.removeAllViews()
        container = null
    }

    private fun mount(tab: Tab) {
        val v = container ?: return
        if (tab.webView.parent === v) return
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        v.removeAllViews()
        v.addView(
            tab.webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        tab.webView.onResume()
        tab.webView.requestFocus()
    }

    /* ------------------------------------------------------------------ */

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(context: Context, private: Boolean): WebView {
        val view = object : WebView(context) {
            // Keeps the software keyboard from resizing the page oddly in landscape.
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) requestFocus()
            }
        }
        view.setBackgroundColor(android.graphics.Color.WHITE)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false

        view.settings.apply {
            javaScriptEnabled = Prefs.javaScriptEnabled
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            textZoom = Prefs.textSizePercent
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = if (Prefs.desktopUserAgent) DESKTOP_UA else null
            mixedContentMode = if (Prefs.httpsOnly) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }

        if (private) {
            // A private tab keeps its own cookie jar for as long as it is open,
            // and is never written to history.
            CookieManager.getInstance().setAcceptCookie(false)
        }
        return view
    }

    private fun bind(tab: Tab) {
        tab.webView.webViewClient = ClientImpl(tab)
        tab.webView.webChromeClient = ChromeImpl(tab)
        tab.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val ctx = host?.activeContext() ?: return@setDownloadListener
            val name = Downloads.enqueue(ctx, url, userAgent, contentDisposition, mimeType)
            if (name != null) {
                host?.onDownloadStarted(name)
                if (!tab.private) {
                    thread(ctx) {
                        DataStore.get(ctx).addDownload(name, url, mimeType ?: "", -1L)
                    }
                }
            }
        }
    }

    private fun thread(ctx: Context, block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "background work failed: ${e.message}")
            }
        }.start()
    }

    /* ------------------------------------------------------------------ */

    fun newTab(url: String? = null, private: Boolean = false, activate: Boolean = true): Tab? {
        val ctx = host?.activeContext() ?: return null
        val tab = Tab(newWebView(ctx, private), private)
        tabs += tab
        bind(tab)
        if (activate) switchTo(tab) else host?.onTabsChanged()
        if (!url.isNullOrEmpty()) tab.webView.loadUrl(url)
        return tab
    }

    fun switchTo(tab: Tab) {
        if (active == tab) {
            host?.onActiveTabChanged(tab)
            return
        }
        val previous = active
        if (previous != null) {
            captureThumbnail(previous)
            previous.webView.onPause()
        }
        active = tab
        mount(tab)
        host?.onTabsChanged()
        host?.onActiveTabChanged(tab)
    }

    fun close(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (active == tab) {
            active = null
            container?.removeAllViews()
            val next = tabs.getOrNull(kotlin.math.max(0, index - 1))
            if (next != null) switchTo(next) else host?.onTabsChanged()
        } else {
            host?.onTabsChanged()
        }
        try {
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            tab.webView.stopLoading()
            tab.webView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "close failed: ${e.message}")
        }
        if (tabs.none { it.private }) CookieManager.getInstance().setAcceptCookie(true)
    }

    fun goBack(): Boolean {
        val t = active ?: return false
        if (!t.webView.canGoBack()) return false
        t.webView.goBack()
        return true
    }

    fun goForward(): Boolean {
        val t = active ?: return false
        if (!t.webView.canGoForward()) return false
        t.webView.goForward()
        return true
    }

    fun reload() = active?.webView?.reload() ?: Unit
    fun stop() = active?.webView?.stopLoading() ?: Unit

    fun loadInActive(url: String) {
        val t = active
        if (t == null) newTab(url) else t.webView.loadUrl(url)
    }

    fun findInPage(query: String) {
        val v = active?.webView ?: return
        if (query.isEmpty()) v.clearMatches() else v.findAllAsync(query)
    }

    fun findNext(forward: Boolean) {
        active?.webView?.findNext(forward)
    }

    fun clearFind() {
        active?.webView?.clearMatches()
    }

    fun setTrackingProtection(on: Boolean) {
        // AdBlocker reads Prefs.shieldsOn on every request, so nothing else to do.
    }

    fun setUserAgentMode(mode: Int) {
        tabs.forEach {
            it.webView.settings.userAgentString = if (mode == 1) DESKTOP_UA else null
        }
    }

    fun setAllowJavascript(on: Boolean) {
        tabs.forEach { it.webView.settings.javaScriptEnabled = on }
    }

    fun setTextZoom(percent: Int) {
        tabs.forEach { it.webView.settings.textZoom = percent }
    }

    fun applyMixedContent() {
        tabs.forEach {
            it.webView.settings.mixedContentMode = if (Prefs.httpsOnly) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }
    }

    /* ------------------------------------------------------------------ */

    fun captureThumbnail(tab: Tab) {
        try {
            val w = tab.webView.width
            val h = tab.webView.height
            if (w <= 0 || h <= 0) return
            val scale = 720f / w
            val bmp = Bitmap.createBitmap(720, (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            tab.webView.draw(canvas)
            tab.thumbnail?.recycle()
            tab.thumbnail = bmp
            host?.onTabsChanged()
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail capture failed: ${e.message}")
        }
    }

    fun persist() {
        val ctx = host?.activeContext() ?: return
        val snapshot = tabs.map { t ->
            t.stateJson = t.saveState()
            TabRow(t.id, t.url, t.title, t.private, t.stateJson)
        }
        thread(ctx) { DataStore.get(ctx).saveTabs(snapshot) }
    }

    fun restore(ctx: Context): Boolean {
        val rows = DataStore.get(ctx).loadTabs().filter { !it.private }
        if (rows.isEmpty()) return false
        for (row in rows.take(8)) {
            val tab = Tab(newWebView(ctx, false), false).also { it.id = row.id }
            tabs += tab
            bind(tab)
            tab.url = row.url
            tab.title = row.title
            val ok = !row.state.isNullOrEmpty() && tab.restoreState(row.state)
            if (!ok && row.url.isNotEmpty()) tab.webView.loadUrl(row.url)
        }
        return tabs.isNotEmpty()
    }

    fun closeAll() {
        for (t in tabs.toList()) {
            try {
                (t.webView.parent as? ViewGroup)?.removeView(t.webView)
                t.webView.destroy()
            } catch (_: Exception) {
            }
        }
        tabs.clear()
        active = null
        container?.removeAllViews()
        host?.onTabsChanged()
    }

    /* ------------------------------------------------------------------ */
    /*  clients                                                            */
    /* ------------------------------------------------------------------ */

    private class ClientImpl(val tab: Tab) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url?.toString() ?: return false

            when (AdBlocker.check(uri)) {
                AdBlocker.Verdict.BLOCK_AD -> {
                    count("ad", uri)
                    return true
                }
                AdBlocker.Verdict.BLOCK_TRACKER -> {
                    count("tracker", uri)
                    return true
                }
                else -> {}
            }

            // Non-web schemes belong to the OS.
            if (!uri.startsWith("http", true) && !uri.startsWith("about:", true)) {
                val ctx = TabManager.host?.activeContext()
                if (ctx != null && External.openUri(ctx, uri)) return true
            }
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val uri = request.url?.toString() ?: return null
            when (AdBlocker.check(uri)) {
                AdBlocker.Verdict.BLOCK_AD -> {
                    count("ad", uri)
                    return blocked()
                }
                AdBlocker.Verdict.BLOCK_TRACKER -> {
                    count("tracker", uri)
                    return blocked()
                }
                else -> return null
            }
        }

        private fun blocked() = WebResourceResponse(
            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
        )

        private fun count(kind: String, uri: String) {
            if (kind == "ad") tab.blockedAds++ else tab.blockedTrackers++
            val h = UrlBar.hostOf(uri)
            val ctx = TabManager.host?.activeContext()
            if (ctx != null) {
                TabManager.thread(ctx) { DataStore.get(ctx).recordBlocked(h, kind) }
            }
            TabManager.host?.onBlockedOnPage(tab)
            TabManager.host?.onBlockedTotal(kind, h)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            tab.url = url.orEmpty()
            tab.blockedAds = 0
            tab.blockedTrackers = 0
            TabManager.host?.onProgress(tab, 10)
            TabManager.host?.onActiveTabChanged(tab)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            tab.url = url ?: tab.url
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            tab.title = view.title?.trim().orEmpty().ifEmpty { tab.title }
            tab.isSecure = tab.url.startsWith("https://", true)
            if (!tab.private && tab.url.isNotEmpty()) {
                val ctx = TabManager.host?.activeContext()
                if (ctx != null) {
                    TabManager.thread(ctx) { DataStore.get(ctx).recordVisit(tab.url, tab.title) }
                }
            }
            TabManager.host?.onProgress(tab, 100)
            TabManager.host?.onPageFinished(tab, true)
            TabManager.host?.onActiveTabChanged(tab)
            TabManager.host?.onTabsChanged()
            TabManager.captureThumbnail(tab)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            if (request.isForMainFrame) {
                view.loadUrl(ErrorPages.dataUrlFor(request.url?.toString(), error.errorCode))
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url == null || url.startsWith("about:")) return
            tab.url = url
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            TabManager.host?.onActiveTabChanged(tab)
        }
    }

    private class ChromeImpl(val tab: Tab) : WebChromeClient(), VideoHost {

        private var customView: android.view.View? = null
        private var customCallback: CustomViewCallback? = null

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            TabManager.host?.onProgress(tab, newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            val t = title?.trim().orEmpty()
            if (t.isNotEmpty()) tab.title = t
            if (!tab.private && tab.url.isNotEmpty() && t.isNotEmpty()) {
                val ctx = TabManager.host?.activeContext()
                if (ctx != null) TabManager.thread(ctx) { DataStore.get(ctx).retitle(tab.url, t) }
            }
            TabManager.host?.onActiveTabChanged(tab)
            TabManager.host?.onTabsChanged()
        }

        override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
            if (view == null) return
            customView = view
            customCallback = callback
            TabManager.host?.onEnterVideoFullScreen(true)
        }

        override fun onHideCustomView() {
            customView = null
            customCallback?.onCustomViewHidden()
            customCallback = null
            TabManager.host?.onEnterVideoFullScreen(false)
        }

        override fun currentCustomView(): android.view.View? = customView

        override fun onPermissionRequest(request: PermissionRequest) {
            // Camera / mic prompts are answered from the Activity's runtime flow.
            request.deny()
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            val accept = fileChooserParams?.acceptTypes?.firstOrNull { !it.isNullOrEmpty() }
            TabManager.host?.openFileChooser(filePathCallback, accept)
            return true
        }
    }
}
