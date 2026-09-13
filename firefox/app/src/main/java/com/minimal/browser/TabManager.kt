package com.minimal.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import java.util.UUID
import kotlin.math.max

/**
 * Multi-tab controller. One [GeckoView] is shared; tabs swap their
 * [GeckoSession] in and out of it — the same model Firefox for Android uses.
 */
object TabManager {

    private const val TAG = "TabManager"

    val tabs = mutableListOf<Tab>()
    var active: Tab? = null
        private set

    var view: GeckoView? = null
        private set
    private var runtime: GeckoRuntime? = null
    private val main = Handler(Looper.getMainLooper())

    /** Set by MainActivity. */
    var host: Host? = null

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
        fun onRequestAndroidPermissions(permissions: Array<String>, cb: GeckoSession.PermissionDelegate.Callback)
        fun onTabWantsToClose(tab: Tab)
    }

    /* ------------------------------------------------------------------ */
    /*  lifecycle                                                          */
    /* ------------------------------------------------------------------ */

    fun init(runtime: GeckoRuntime) {
        if (this.runtime != null) return
        this.runtime = runtime
    }

    fun attach(v: GeckoView) {
        view = v
        val tab = active
        // setSession(null) would throw — only attach when there is a live tab
        if (tab == null || v.session === tab.session) return
        try {
            v.setSession(tab.session)
            tab.session.setActive(true)
        } catch (e: Throwable) {
            // Never let a compositor/session race take the process down; the screen
            // just retries on the next resume.
            Log.e(TAG, "setSession failed", e)
        }
    }

    fun detach() {
        view?.releaseSession()
        view = null
    }

    /* ------------------------------------------------------------------ */
    /*  tab plumbing                                                       */
    /* ------------------------------------------------------------------ */

    private fun newSession(private: Boolean): GeckoSession {
        // 153 exposes no public setContextId/setUsePrivateMode — the only correct way
        // to get a real private session is to build the settings up front. Mutating
        // them after GeckoSession(settings) is ignored by the engine, which is why the
        // old reflection hack silently produced a *normal* tab.
        val builder = GeckoSessionSettings.Builder()
            .useTrackingProtection(Prefs.shieldsOn)
            .suspendMediaWhenInactive(true)
            .allowJavascript(Prefs.javaScriptEnabled)
            .userAgentMode(
                if (Prefs.desktopUserAgent) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        if (private) {
            builder.usePrivateMode(true)
                .contextId("private-" + UUID.randomUUID())
        }
        val session = GeckoSession(builder.build())
        val rt = runtime ?: BrowserApp.requireRuntime()
        session.open(rt)
        return session
    }

    private fun bind(tab: Tab) {
        val s = tab.session
        s.navigationDelegate = NavigationDelegateImpl(tab)
        s.progressDelegate = ProgressDelegateImpl(tab)
        s.contentDelegate = ContentDelegateImpl(tab)
        s.permissionDelegate = PermissionDelegateImpl(tab)
        s.historyDelegate = HistoryDelegateImpl(tab)
        s.setContentBlockingDelegate(object : ContentBlocking.Delegate {
            override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
                val cat = event.antiTrackingCategory
                val isAd = (cat and (ContentBlocking.AntiTracking.AD or
                    ContentBlocking.AntiTracking.CONTENT or
                    ContentBlocking.AntiTracking.CRYPTOMINING)) != 0
                if (isAd) tab.blockedAds++ else tab.blockedTrackers++
                val h = UrlBar.hostOf(event.uri)
                host?.activeContext()?.let { ctx ->
                    thread { DataStore.get(ctx).recordBlocked(h, if (isAd) "ad" else "tracker") }
                }
                host?.onBlockedOnPage(tab)
                host?.onBlockedTotal(if (isAd) "ad" else "tracker", h)
            }
        })
    }

    /** Fire-and-forget DB work off the UI thread. */
    private fun thread(block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "background work failed: ${e.message}")
            }
        }.start()
    }

    /* ------------------------------------------------------------------ */
    /*  public API                                                         */
    /* ------------------------------------------------------------------ */

    fun newTab(url: String? = null, private: Boolean = false, activate: Boolean = true): Tab {
        val tab = Tab(newSession(private), private)
        tabs += tab
        bind(tab)
        if (activate) switchTo(tab) else host?.onTabsChanged()
        if (!url.isNullOrEmpty()) tab.session.load(GeckoSession.Loader().uri(url))
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
            previous.session.setActive(false)
        }
        active = tab
        val v = view
        if (v != null) {
            try {
                if (previous != null) v.releaseSession()
                v.setSession(tab.session)
            } catch (e: Throwable) {
                Log.e(TAG, "switchTo setSession failed", e)
            }
        }
        tab.session.setActive(true)
        tab.session.setFocused(true)
        host?.onTabsChanged()
        host?.onActiveTabChanged(tab)
    }

    fun close(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (active == tab) {
            active = null
            view?.releaseSession()
            val next = tabs.getOrNull(max(0, index - 1))
            if (next != null) {
                switchTo(next)
            } else {
                host?.onTabsChanged()
            }
        } else {
            host?.onTabsChanged()
        }
        try {
            tab.session.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed: ${e.message}")
        }
    }

    fun closeActive() {
        active?.let { close(it) }
    }

    fun goBack(): Boolean {
        val t = active ?: return false
        if (!t.canGoBack) return false
        t.session.goBack()
        return true
    }

    fun goForward(): Boolean {
        val t = active ?: return false
        if (!t.canGoForward) return false
        t.session.goForward()
        return true
    }

    fun reload() {
        active?.session?.reload()
    }

    fun stop() {
        active?.session?.stop()
    }

    fun loadInActive(url: String) {
        val t = active ?: newTab(url).also { return }
        t.session.load(GeckoSession.Loader().uri(url))
    }

    fun findInPage(query: String, forward: Boolean = true) {
        active?.session?.finder?.find(query, if (forward) 0 else GeckoSession.FINDER_FIND_BACKWARDS)
    }

    fun clearFind() {
        active?.session?.finder?.clear()
    }

    fun exitPageFullScreen() {
        active?.session?.exitFullScreen()
    }

    fun setTrackingProtection(on: Boolean) {
        tabs.forEach { it.session.settings.setUseTrackingProtection(on) }
    }

    fun setUserAgentMode(mode: Int) {
        tabs.forEach { it.session.settings.setUserAgentMode(mode) }
    }

    fun setAllowJavascript(on: Boolean) {
        tabs.forEach { it.session.settings.setAllowJavascript(on) }
    }

    /* ------------------------------------------------------------------ */
    /*  thumbnails + session restore                                       */
    /* ------------------------------------------------------------------ */

    fun captureThumbnail(tab: Tab) {
        try {
            val display = tab.session.acquireDisplay() ?: return
            display.screenshot()
                .aspectPreservingSize(720)
                .capture()
                .accept(
                    { bmp ->
                        if (bmp != null) {
                            tab.thumbnail?.recycle()
                            tab.thumbnail = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
                            host?.onTabsChanged()
                        }
                    },
                    { /* capture can fail for background tabs — that is fine */ }
                )
        } catch (e: Throwable) {
            Log.w(TAG, "thumbnail capture failed: ${e.message}")
        }
    }

    fun persist() {
        val ctx = host?.activeContext() ?: return
        val snapshot = tabs.map { t ->
            var state: String? = null
            try {
                t.session.flushSessionState()
                state = t.sessionState?.toString()
            } catch (e: Throwable) {
                state = null
            }
            t.stateJson = state
            TabRow(t.id, t.url, t.title, t.private, state)
        }
        thread { DataStore.get(ctx).saveTabs(snapshot) }
    }

    /** Rebuild the previous window; returns false when there is nothing to restore. */
    fun restore(ctx: Context): Boolean {
        val rows = DataStore.get(ctx).loadTabs()
        val restorable = rows.filter { !it.private }
        if (restorable.isEmpty()) return false
        var restoredAny = false
        for (row in restorable.take(8)) {
            val tab = Tab(newSession(false), false).also { it.id = row.id }
            tabs += tab
            bind(tab)
            tab.url = row.url
            tab.title = row.title
            var ok = false
            if (!row.state.isNullOrEmpty()) {
                try {
                    tab.session.restoreState(GeckoSession.SessionState.fromString(row.state!!)!!)
                    ok = true
                } catch (e: Throwable) {
                    Log.w(TAG, "restore failed for ${row.url}: ${e.message}")
                }
            }
            if (!ok && row.url.isNotEmpty()) {
                tab.session.load(GeckoSession.Loader().uri(row.url))
            }
            restoredAny = true
        }
        return restoredAny
    }

    fun closeAll() {
        for (t in tabs.toList()) {
            try {
                t.session.close()
            } catch (_: Exception) {
            }
        }
        tabs.clear()
        active = null
        view?.releaseSession()
        host?.onTabsChanged()
    }

    /* ------------------------------------------------------------------ */
    /*  delegates                                                          */
    /* ------------------------------------------------------------------ */

    private class NavigationDelegateImpl(val tab: Tab) : GeckoSession.NavigationDelegate {

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) {
            url ?: return
            if (url.startsWith("about:")) return
            tab.url = url
            if (!tab.private) {
                val ctx = host?.activeContext()
                if (ctx != null) thread { DataStore.get(ctx).recordVisit(url, tab.title) }
            }
            host?.onActiveTabChanged(tab)
            host?.onTabsChanged()
        }

        override fun onCanGoBack(session: GeckoSession, goBack: Boolean) {
            tab.canGoBack = goBack
            host?.onActiveTabChanged(tab)
        }

        override fun onCanGoForward(session: GeckoSession, goForward: Boolean) {
            tab.canGoForward = goForward
            host?.onActiveTabChanged(tab)
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            val uri = request.uri
            // Our own request-level blocker gets the first word.
            when (AdBlocker.check(uri)) {
                AdBlocker.Verdict.BLOCK_AD -> {
                    tab.blockedAds++
                    reportBlocked("ad", uri)
                    return GeckoResult.deny()
                }
                AdBlocker.Verdict.BLOCK_TRACKER -> {
                    tab.blockedTrackers++
                    reportBlocked("tracker", uri)
                    return GeckoResult.deny()
                }
                else -> {}
            }
            // Hand non-web schemes to the OS.
            if (uri != null && !uri.startsWith("http", true) &&
                !uri.startsWith("about:", true) && !uri.startsWith("data:", true) &&
                !uri.startsWith("blob:", true)
            ) {
                val ctx = host?.activeContext()
                if (ctx != null && External.openUri(ctx, uri)) return GeckoResult.deny()
            }
            return GeckoResult.allow()
        }

        override fun onSubframeLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            when (AdBlocker.check(request.uri)) {
                AdBlocker.Verdict.BLOCK_AD -> {
                    tab.blockedAds++
                    reportBlocked("ad", request.uri)
                    return GeckoResult.deny()
                }
                AdBlocker.Verdict.BLOCK_TRACKER -> {
                    tab.blockedTrackers++
                    reportBlocked("tracker", request.uri)
                    return GeckoResult.deny()
                }
                else -> return GeckoResult.allow()
            }
        }

        override fun onNewSession(
            session: GeckoSession,
            url: String
        ): GeckoResult<GeckoSession> {
            // target="_blank" and window.open() become a real new tab.
            val tab = newTab(url, private = active?.private == true)
            return GeckoResult.fromValue(tab.session)
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: org.mozilla.geckoview.WebRequestError
        ): GeckoResult<String> {
            return GeckoResult.fromValue(ErrorPages.dataUrlFor(uri, error))
        }

        private fun reportBlocked(kind: String, uri: String?) {
            val h = UrlBar.hostOf(uri)
            val ctx = host?.activeContext()
            if (ctx != null) thread { DataStore.get(ctx).recordBlocked(h, kind) }
            host?.onBlockedOnPage(tab)
            host?.onBlockedTotal(kind, h)
        }
    }

    private class ProgressDelegateImpl(val tab: Tab) : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            tab.url = url
            tab.blockedAds = 0
            tab.blockedTrackers = 0
            host?.onProgress(tab, 10)
            host?.onActiveTabChanged(tab)
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            host?.onProgress(tab, progress)
        }

        override fun onPageStop(session: GeckoSession, successful: Boolean) {
            host?.onProgress(tab, 100)
            host?.onPageFinished(tab, successful)
            if (successful) captureThumbnail(tab)
        }

        override fun onSessionStateChange(session: GeckoSession, state: GeckoSession.SessionState) {
            tab.sessionState = state
        }

        override fun onSecurityChange(
            session: GeckoSession,
            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
        ) {
            tab.isSecure = securityInfo.isSecure
            host?.onSecurityChanged(tab)
            host?.onActiveTabChanged(tab)
        }
    }

    private class ContentDelegateImpl(val tab: Tab) : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            val t = title?.trim().orEmpty()
            if (t.isNotEmpty()) tab.title = t
            if (!tab.private && tab.url.isNotEmpty() && t.isNotEmpty()) {
                val ctx = host?.activeContext()
                if (ctx != null) thread { DataStore.get(ctx).retitle(tab.url, t) }
            }
            host?.onActiveTabChanged(tab)
            host?.onTabsChanged()
        }

        override fun onCloseRequest(session: GeckoSession) {
            host?.onTabWantsToClose(tab)
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            host?.onEnterVideoFullScreen(fullScreen)
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            val ctx = host?.activeContext() ?: return
            val name = Downloads.start(ctx, response)
            if (name != null) {
                host?.onDownloadStarted(name)
                if (!tab.private) {
                    thread {
                        DataStore.get(ctx).addDownload(
                            name, response.uri,
                            response.headers["Content-Type"] ?: "",
                            response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                        )
                    }
                }
            }
        }

        override fun onFirstContentfulPaint(session: GeckoSession) {
            main.postDelayed({ captureThumbnail(tab) }, 400)
        }

        override fun onContextMenu(
            session: GeckoSession,
            screenX: Int,
            screenY: Int,
            element: GeckoSession.ContentDelegate.ContextElement
        ) {
            // Keep the surface simple: no custom context menu.
        }
    }

    /** History visits straight from Gecko — feeds "Continue reading" on the home screen. */
    private class HistoryDelegateImpl(val tab: Tab) : GeckoSession.HistoryDelegate {
        override fun onVisited(
            session: GeckoSession,
            url: String,
            lastVisitedURL: String?,
            flags: Int
        ): GeckoResult<Boolean> {
            if (tab.private || url.startsWith("about:")) {
                return GeckoResult.fromValue(false)
            }
            val ctx = host?.activeContext()
            if (ctx != null) thread { DataStore.get(ctx).recordVisit(url, tab.title) }
            return GeckoResult.fromValue(true)
        }
    }

    private class PermissionDelegateImpl(val tab: Tab) : GeckoSession.PermissionDelegate {
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback
        ) {
            if (permissions.isNullOrEmpty()) {
                callback.grant()
                return
            }
            host?.onRequestAndroidPermissions(permissions, callback) ?: callback.reject()
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            permission: GeckoSession.PermissionDelegate.ContentPermission
        ): GeckoResult<Int> {
            val allowed = when (permission.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> Prefs.shieldsOn
                GeckoSession.PermissionDelegate.PERMISSION_TRACKING -> !Prefs.shieldsOn
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> false
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> true
                else -> false
            }
            return GeckoResult.fromValue(
                if (allowed) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            )
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            deviceOrigin: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback
        ) {
            val ctx = host?.activeContext()
            if (ctx == null) {
                callback.reject()
                return
            }
            val need = ArrayList<String>()
            if (!video.isNullOrEmpty() &&
                ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) need += android.Manifest.permission.CAMERA
            if (!audio.isNullOrEmpty() &&
                ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) need += android.Manifest.permission.RECORD_AUDIO

            if (need.isEmpty()) {
                callback.grant(video?.firstOrNull(), audio?.firstOrNull())
            } else {
                host?.onRequestAndroidPermissions(need.toTypedArray(),
                    object : GeckoSession.PermissionDelegate.Callback {
                        override fun grant() {
                            callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                        }

                        override fun reject() {
                            callback.reject()
                        }
                    }) ?: callback.reject()
            }
        }
    }
}
