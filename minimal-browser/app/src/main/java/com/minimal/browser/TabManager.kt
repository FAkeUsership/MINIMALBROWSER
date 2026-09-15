package com.minimal.browser

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import androidx.annotation.OptIn
import com.minimal.browser.ui.UiKeys
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Multi-tab controller for the bundled GeckoView engine.
 *
 * Each tab owns one GeckoSession. Exactly one lazily created GeckoView surface
 * is attached, and only while the Web screen is actually visible. Delegates are
 * installed before session.open(), as required by GeckoView's documented
 * startup ordering workaround.
 */
object TabManager {

    private const val TAG = "TabManager"
    private const val BLOCKED_BATCH_DELAY_MS = 120L
    private val main = Handler(Looper.getMainLooper())

    // One low-priority worker replaces the old one-new-thread-per-write model.
    // In particular, a tracker-heavy page can generate many block events at once.
    private val storageExecutor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "minimal-browser-store").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private data class BlockedSignal(
        val tab: Tab,
        val generation: Long,
        val kind: String
    )
    private data class BlockedDelta(var ads: Int = 0, var trackers: Int = 0)
    private val blockedSignalLock = Any()
    private val pendingBlockedSignals = ArrayList<BlockedSignal>()
    private var blockedFlushScheduled = false
    private val blockedFlushRunnable = Runnable { flushBlockedSignals() }

    val tabs = mutableListOf<Tab>()
    var active: Tab? = null
        private set

    private var runtime: GeckoRuntime? = null
    private var attachedView: GeckoView? = null

    /** Set only while MainActivity is alive. */
    var host: Host? = null

    interface Host {
        fun activeContext(): Context?
        fun onTabsChanged()
        fun onActiveTabChanged(tab: Tab?)
        fun onProgress(tab: Tab, progress: Int)
        fun onPageFinished(tab: Tab, successful: Boolean)
        fun onSecurityChanged(tab: Tab)
        fun onBlockedOnPage(tab: Tab)
        fun onEnterVideoFullScreen(fullScreen: Boolean)
        fun onDownloadRequested(tab: Tab, response: WebResponse)
        fun onTabWantsToClose(tab: Tab)
        fun onSessionRecovered(tab: Tab)
        fun onSessionFailure(tab: Tab, reason: String)
    }

    /** BrowserApp calls this after creating the one process-wide GeckoRuntime. */
    fun init(value: GeckoRuntime) {
        if (runtime === value) return
        if (runtime != null && runtime !== value) {
            Log.w(TAG, "ignoring attempt to replace a live GeckoRuntime")
            return
        }
        runtime = value
    }

    /* ------------------------------------------------------------------ */
    /*  visible-surface lifecycle                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Binds the current active session to a GeckoView only after that view is
     * attached to the Android window. Calling this from a hidden/GONE view was
     * one of the fragile compositor paths eliminated by this implementation.
     */
    fun attach(view: GeckoView) {
        attachedView = view
        val tab = active ?: return
        if (!view.isAttachedToWindow) {
            view.post {
                if (attachedView === view && active === tab && view.isAttachedToWindow) attach(view)
            }
            return
        }
        try {
            if (view.session !== tab.session) view.setSession(tab.session)
            tab.session.setActive(true)
            tab.session.setFocused(true)
        } catch (error: Throwable) {
            Log.e(TAG, "could not attach active GeckoSession", error)
            host?.onSessionFailure(tab, "The browser view could not attach")
        }
    }

    /** Releases only the display/compositor binding; the tab session remains open. */
    fun detach() {
        val view = attachedView
        attachedView = null
        try {
            view?.releaseSession()
        } catch (error: Throwable) {
            Log.w(TAG, "could not release GeckoView session", error)
        }
        setSessionActive(active, false)
    }

    fun pauseActive() {
        setSessionActive(active, false)
    }

    fun resumeActive() {
        setSessionActive(active, true)
    }

    private fun setSessionActive(tab: Tab?, value: Boolean) {
        tab ?: return
        runCatching {
            tab.session.setActive(value)
            if (value) tab.session.setFocused(true)
        }.onFailure { Log.w(TAG, "could not change GeckoSession active state", it) }
    }

    /* ------------------------------------------------------------------ */
    /*  tabs and navigation                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Creates an opened session. All delegates, especially ContentDelegate,
     * are installed first; no session is ever opened with an unset delegate.
     */
    private fun createUnopenedTab(privateMode: Boolean): Tab =
        Tab(createSession(privateMode), privateMode).also(::bind)

    private fun createOpenedTab(privateMode: Boolean): Tab {
        val tab = createUnopenedTab(privateMode)
        try {
            val currentRuntime = runtime ?: BrowserApp.runtime
                ?: throw IllegalStateException("Bundled browser runtime is not ready")
            tab.session.open(currentRuntime)
            return tab
        } catch (error: Throwable) {
            runCatching { tab.session.close() }
            Log.e(TAG, "could not create/open GeckoSession", error)
            throw error
        }
    }

    private fun createSession(privateMode: Boolean): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .useTrackingProtection(Prefs.shieldsOn)
            .suspendMediaWhenInactive(true)
            .allowJavascript(Prefs.javaScriptEnabled)
            .userAgentMode(
                if (Prefs.desktopUserAgent) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .apply {
                if (privateMode) {
                    usePrivateMode(true)
                    contextId("private-${UUID.randomUUID()}")
                }
            }
            .build()
        return GeckoSession(settings)
    }

    fun newTab(url: String? = null, privateMode: Boolean = false, activate: Boolean = true): Tab {
        val tab = createOpenedTab(privateMode)
        tabs += tab
        if (activate) switchTo(tab) else host?.onTabsChanged()
        if (!url.isNullOrBlank()) load(tab, url)
        return tab
    }

    fun switchTo(tab: Tab) {
        if (!tabs.contains(tab)) return
        if (active === tab) {
            attachedView?.let(::attach)
            host?.onActiveTabChanged(tab)
            return
        }
        val previous = active
        if (previous != null) {
            // Do not force a full-frame GPU readback while the user is switching
            // tabs. Inactive sessions are simply deactivated.
            setSessionActive(previous, false)
        }
        active = tab
        try {
            attachedView?.let { view ->
                if (view.isAttachedToWindow) view.setSession(tab.session)
            }
            setSessionActive(tab, true)
            // URL-only tab restore intentionally avoids loading a stack of
            // hidden pages at engine startup. Start this saved page only when
            // the user makes its tab visible.
            tab.pendingRestoreUrl?.let { pending ->
                tab.pendingRestoreUrl = null
                load(tab, pending)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "could not switch Gecko session", error)
            host?.onSessionFailure(tab, "The selected tab could not start")
        }
        host?.onTabsChanged()
        host?.onActiveTabChanged(tab)
    }

    fun close(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        val wasActive = active === tab
        // Prefer the tab to the right, otherwise the one to the left. Looking up
        // max(0, index - 1) before removal selected the closing first tab itself.
        val next = if (wasActive) tabs.getOrNull(index + 1) ?: tabs.getOrNull(index - 1) else null

        if (wasActive) {
            try {
                if (attachedView?.session === tab.session) attachedView?.releaseSession()
            } catch (error: Throwable) {
                Log.w(TAG, "could not release closing session", error)
            }
            active = null
        }
        tabs.removeAt(index)
        destroyTab(tab)

        if (wasActive && next != null && tabs.contains(next)) {
            switchTo(next)
        } else if (wasActive) {
            host?.onActiveTabChanged(null)
            host?.onTabsChanged()
        } else {
            host?.onTabsChanged()
        }
    }

    fun closeActive() {
        active?.let(::close)
    }

    fun closeAll() {
        detach()
        val old = tabs.toList()
        tabs.clear()
        active = null
        old.forEach(::destroyTab)
        host?.onActiveTabChanged(null)
        host?.onTabsChanged()
    }

    fun goBack(): Boolean {
        val tab = active ?: return false
        return runCatching {
            if (!tab.canGoBack) false else {
                tab.session.goBack()
                true
            }
        }.getOrElse {
            Log.w(TAG, "back navigation failed", it)
            false
        }
    }

    fun goForward(): Boolean {
        val tab = active ?: return false
        return runCatching {
            if (!tab.canGoForward) false else {
                tab.session.goForward()
                true
            }
        }.getOrElse {
            Log.w(TAG, "forward navigation failed", it)
            false
        }
    }

    fun reload() {
        val tab = active ?: return
        runCatching {
            // Gecko's current document is the internal data: error page after a
            // failed load; retry the original URL rather than reloading that page.
            if (tab.errorPageUrl != null && tab.url.isNotBlank()) load(tab, tab.url)
            else tab.session.reload()
        }.onFailure { Log.w(TAG, "reload failed", it) }
    }

    fun stop() {
        runCatching { active?.session?.stop() }
            .onFailure { Log.w(TAG, "stop failed", it) }
    }

    /** @return false when there is no live active session. */
    fun loadInActive(url: String): Boolean {
        val tab = active ?: return false
        return load(tab, url)
    }

    private fun load(tab: Tab, rawUrl: String): Boolean {
        val url = upgradeToHttps(rawUrl)
        if (url.isBlank()) return false
        return try {
            tab.url = url
            tab.pendingRestoreUrl = null
            tab.errorPageUrl = null
            tab.isSecure = url.startsWith("https://", ignoreCase = true)
            tab.session.load(GeckoSession.Loader().uri(url))
            true
        } catch (error: Throwable) {
            Log.w(TAG, "load failed for $url", error)
            if (active === tab) host?.onPageFinished(tab, false)
            false
        }
    }

    private fun upgradeToHttps(url: String): String =
        if (Prefs.httpsOnly && url.startsWith("http://", ignoreCase = true)) {
            "https://${url.substringAfter("://")}"
        } else {
            url
        }

    fun findInPage(query: String, forward: Boolean = true) {
        val tab = active ?: return
        runCatching {
            tab.session.finder.find(
                query,
                if (forward) 0 else GeckoSession.FINDER_FIND_BACKWARDS
            )
        }.onFailure { Log.w(TAG, "find-in-page failed", it) }
    }

    fun clearFind() {
        runCatching { active?.session?.finder?.clear() }
            .onFailure { Log.w(TAG, "could not clear find results", it) }
    }

    fun exitPageFullScreen() {
        runCatching { active?.session?.exitFullScreen() }
            .onFailure { Log.w(TAG, "could not exit content fullscreen", it) }
    }

    fun setTrackingProtection(on: Boolean) {
        tabs.forEach { tab ->
            runCatching { tab.session.settings.setUseTrackingProtection(on) }
                .onFailure { Log.w(TAG, "could not change tracking protection", it) }
        }
    }

    fun setUserAgentMode(mode: Int) {
        tabs.forEach { tab ->
            runCatching { tab.session.settings.setUserAgentMode(mode) }
                .onFailure { Log.w(TAG, "could not change user agent mode", it) }
        }
    }

    fun setAllowJavascript(on: Boolean) {
        tabs.forEach { tab ->
            runCatching { tab.session.settings.setAllowJavascript(on) }
                .onFailure { Log.w(TAG, "could not change JavaScript setting", it) }
        }
    }

    /** Clear bundled-engine browsing storage after the Settings action. */
    fun clearEngineData(clearCookies: Boolean, clearCache: Boolean, clearHistory: Boolean) {
        // Browsing history is owned by DataStore and is cleared by the Activity.
        // Do not use ClearFlags.ALL for a history-only request: that would erase
        // every cookie/site setting unexpectedly.
        var flags = 0L
        if (clearCookies) {
            flags = flags or StorageController.ClearFlags.COOKIES or
                StorageController.ClearFlags.DOM_STORAGES or
                StorageController.ClearFlags.AUTH_SESSIONS or
                StorageController.ClearFlags.SITE_SETTINGS
        }
        if (clearCache) flags = flags or StorageController.ClearFlags.ALL_CACHES
        if (flags == 0L) return
        runCatching { (runtime ?: BrowserApp.runtime)?.storageController?.clearData(flags) }
            .onFailure { Log.w(TAG, "could not clear bundled-engine data", it) }
    }

    /* ------------------------------------------------------------------ */
    /*  URL-only restore                                                   */
    /* ------------------------------------------------------------------ */

    // Tab cards use lightweight artwork instead of automatic full-frame
    // compositor screenshots. Screenshot readback forces a GPU/CPU sync and
    // used to run during switching and page loading, directly competing with
    // scroll and first-paint work.

    /** Persist only URLs/titles. Restoring opaque native window state was removed deliberately. */
    fun persist() {
        val context = host?.activeContext()?.applicationContext ?: return
        val snapshot = tabs.map { tab ->
            TabRow(tab.id, tab.url, tab.title, tab.private, null)
        }
        background { DataStore.get(context).saveTabs(snapshot) }
    }

    /** Recreates at most eight ordinary tabs from their URLs; private tabs are never restored. */
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
                // Do not start several hidden network pages as soon as the
                // engine starts. An opened but blank session is lightweight;
                // switchTo() loads pendingRestoreUrl when this tab is selected.
                val tab = createOpenedTab(privateMode = false)
                tab.id = row.id
                tab.url = row.url
                tab.title = row.title
                tab.isSecure = row.url.startsWith("https://", ignoreCase = true)
                tab.pendingRestoreUrl = row.url
                tabs += tab
                setSessionActive(tab, false)
                restored = true
            } catch (error: Throwable) {
                Log.w(TAG, "skipping saved tab ${row.url}", error)
            }
        }
        return restored
    }

    private fun destroyTab(tab: Tab) {
        runCatching { tab.session.close() }
            .onFailure { Log.w(TAG, "could not close GeckoSession", it) }
    }

    /* ------------------------------------------------------------------ */
    /*  session setup and delegates                                       */
    /* ------------------------------------------------------------------ */

    /**
     * A target=_blank/window.open session is opened by Gecko only after
     * NavigationDelegate.onNewSession returns. Posting the activation keeps the
     * browser from attaching an unopened session, while bringing a user-facing
     * login/consent window to the foreground as soon as Gecko has opened it.
     */
    private fun foregroundOpenedChild(tab: Tab, attempts: Int = 0) {
        main.post {
            if (!tabs.contains(tab)) return@post
            if (tab.session.isOpen()) {
                switchTo(tab)
            } else if (attempts < 3) {
                // Gecko opens the returned session on its current UI callback;
                // retry a few main-loop turns only for that handoff.
                foregroundOpenedChild(tab, attempts + 1)
            } else {
                Log.w(TAG, "new child session did not open in time")
            }
        }
    }

    private fun bind(tab: Tab) {
        val session = tab.session
        session.navigationDelegate = NavigationDelegateImpl(tab)
        session.progressDelegate = ProgressDelegateImpl(tab)
        // This must be assigned before session.open().
        session.contentDelegate = ContentDelegateImpl(tab)
        session.historyDelegate = HistoryDelegateImpl(tab)
        session.permissionDelegate = PermissionDelegateImpl()
        session.setPromptDelegate(BrowserPromptDelegate())
        session.setContentBlockingDelegate(ContentBlockingDelegateImpl(tab))
        installHardwareAwareTextInputDelegate(session)
    }

    /**
     * GeckoView's default delegate directly calls InputMethodManager.showSoftInput
     * for a focused HTML field. Wrap that existing delegate rather than replacing
     * it with partial no-op callbacks: this keeps Gecko's normal restart,
     * selection, extracted-text, and cursor-anchor plumbing intact.
     */
    private fun installHardwareAwareTextInputDelegate(session: GeckoSession) {
        val textInput = session.textInput
        val defaultDelegate = textInput.delegate
        textInput.setDelegate(object : GeckoSession.TextInputDelegate {
            override fun restartInput(session: GeckoSession, reason: Int) {
                defaultDelegate.restartInput(session, reason)
            }

            override fun showSoftInput(session: GeckoSession) {
                val view = session.textInput.view
                if (view != null && !UiKeys.shouldShowSoftwareKeyboard(view.context)) {
                    // Leave GeckoView and the HTML field focused. Only decline the
                    // software keyboard request so raw physical key events retain
                    // their normal Gecko delivery path. This also respects the
                    // explicit "Show mobile keyboard" setting.
                    UiKeys.hideKeyboard(view, clearFocus = false)
                } else {
                    defaultDelegate.showSoftInput(session)
                }
            }

            override fun hideSoftInput(session: GeckoSession) {
                defaultDelegate.hideSoftInput(session)
            }

            override fun updateSelection(
                session: GeckoSession,
                selStart: Int,
                selEnd: Int,
                compositionStart: Int,
                compositionEnd: Int
            ) {
                defaultDelegate.updateSelection(session, selStart, selEnd, compositionStart, compositionEnd)
            }

            override fun updateExtractedText(
                session: GeckoSession,
                request: android.view.inputmethod.ExtractedTextRequest,
                text: android.view.inputmethod.ExtractedText
            ) {
                defaultDelegate.updateExtractedText(session, request, text)
            }

            override fun updateCursorAnchorInfo(
                session: GeckoSession,
                info: android.view.inputmethod.CursorAnchorInfo
            ) {
                defaultDelegate.updateCursorAnchorInfo(session, info)
            }
        })
    }

    private fun isCurrent(tab: Tab, session: GeckoSession): Boolean =
        tabs.contains(tab) && tab.session === session

    private class NavigationDelegateImpl(private val tab: Tab) : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) {
            if (!isCurrent(tab, session) || url.isNullOrBlank()) return
            if (url == tab.errorPageUrl) return
            tab.url = url
            tab.errorPageUrl = null
            tab.isSecure = url.startsWith("https://", ignoreCase = true)
            if (active === tab) host?.onActiveTabChanged(tab)
            host?.onTabsChanged()
        }

        override fun onCanGoBack(session: GeckoSession, goBack: Boolean) {
            if (!isCurrent(tab, session)) return
            tab.canGoBack = goBack
            if (active === tab) host?.onActiveTabChanged(tab)
        }

        override fun onCanGoForward(session: GeckoSession, goForward: Boolean) {
            if (!isCurrent(tab, session)) return
            tab.canGoForward = goForward
            if (active === tab) host?.onActiveTabChanged(tab)
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            if (!isCurrent(tab, session)) return GeckoResult.deny()
            val uri = request.uri
            val scheme = runCatching { android.net.Uri.parse(uri).scheme?.lowercase() }.getOrNull()
            if (scheme == "http" || scheme == "https") {
                if (Prefs.httpsOnly && scheme == "http") {
                    val secure = upgradeToHttps(uri)
                    if (secure != uri) load(tab, secure)
                    return GeckoResult.deny()
                }
                // Never apply the local block list to a main-frame URL deliberately opened by the user.
                return GeckoResult.allow()
            }
            if (scheme in setOf("about", "data", "blob", "javascript")) return GeckoResult.allow()
            host?.activeContext()?.let { External.openUri(it, uri) }
            return GeckoResult.deny()
        }

        override fun onSubframeLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            if (!isCurrent(tab, session)) return GeckoResult.deny()
            return when (AdBlocker.check(request.uri)) {
                AdBlocker.Verdict.BLOCK_AD -> {
                    reportBlocked(tab, "ad")
                    GeckoResult.deny()
                }
                AdBlocker.Verdict.BLOCK_TRACKER -> {
                    reportBlocked(tab, "tracker")
                    GeckoResult.deny()
                }
                AdBlocker.Verdict.ALLOW -> GeckoResult.allow()
            }
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
            return try {
                // GeckoView itself opens this session with the window ID supplied by
                // the engine. Returning an already-open session here is explicitly
                // invalid and causes a native assertion on target=_blank/window.open.
                val child = createUnopenedTab(tab.private)
                child.url = uri
                child.isSecure = uri.startsWith("https://", ignoreCase = true)
                tabs += child
                host?.onTabsChanged()
                // OAuth and consent pages frequently use target=_blank or
                // window.open. The old implementation retained this new
                // session in the background, leaving the tapped flow looking
                // like it had done nothing. Gecko opens it after this callback;
                // activate it on the following UI turn, never before.
                foregroundOpenedChild(child)
                GeckoResult.fromValue(child.session)
            } catch (error: Throwable) {
                Log.e(TAG, "could not create requested child tab", error)
                GeckoResult.fromException<GeckoSession>(error)
            }
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError
        ): GeckoResult<String> {
            val page = ErrorPages.dataUrlFor(uri, error)
            if (isCurrent(tab, session)) tab.errorPageUrl = page
            return GeckoResult.fromValue(page)
        }
    }

    private class ProgressDelegateImpl(private val tab: Tab) : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (!isCurrent(tab, session)) return
            if (url != tab.errorPageUrl) {
                tab.url = url
                tab.errorPageUrl = null
                tab.isSecure = url.startsWith("https://", ignoreCase = true)
            }
            tab.blockedAds = 0
            tab.blockedTrackers = 0
            tab.blockedGeneration++
            if (active === tab) {
                host?.onProgress(tab, 10)
                host?.onActiveTabChanged(tab)
            }
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            if (isCurrent(tab, session) && active === tab) {
                host?.onProgress(tab, progress.coerceIn(0, 100))
            }
        }

        override fun onPageStop(session: GeckoSession, successful: Boolean) {
            if (!isCurrent(tab, session)) return
            if (active === tab) {
                host?.onProgress(tab, 100)
                host?.onPageFinished(tab, successful)
                host?.onActiveTabChanged(tab)
            }
        }

        override fun onSessionStateChange(session: GeckoSession, state: GeckoSession.SessionState) {
            if (isCurrent(tab, session)) tab.sessionState = state
        }

        override fun onSecurityChange(
            session: GeckoSession,
            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
        ) {
            if (!isCurrent(tab, session)) return
            tab.isSecure = securityInfo.isSecure
            if (active === tab) {
                host?.onSecurityChanged(tab)
                host?.onActiveTabChanged(tab)
            }
        }
    }

    private class ContentDelegateImpl(private val tab: Tab) : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            if (!isCurrent(tab, session)) return
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { tab.title = it }
            // Snapshot mutable tab values before the serialized worker handles
            // this write; a later navigation must not retitle the wrong URL.
            val pageUrl = tab.url
            val pageTitle = tab.title
            if (!tab.private && pageUrl.isNotBlank() && pageTitle.isNotBlank()) {
                host?.activeContext()?.applicationContext?.let { context ->
                    background { DataStore.get(context).retitle(pageUrl, pageTitle) }
                }
            }
            if (active === tab) host?.onActiveTabChanged(tab)
            host?.onTabsChanged()
        }

        override fun onCloseRequest(session: GeckoSession) {
            if (isCurrent(tab, session)) host?.onTabWantsToClose(tab)
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            if (isCurrent(tab, session) && active === tab) host?.onEnterVideoFullScreen(fullScreen)
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            if (!isCurrent(tab, session)) {
                Downloads.discard(response)
                return
            }
            // GeckoView gives us the already-authorized byte stream. The host
            // owns the visible confirmation and starts copying only if the
            // person accepts; it also closes a declined response safely.
            val browserHost = host
            if (browserHost == null) {
                Downloads.discard(response)
            } else {
                browserHost.onDownloadRequested(tab, response)
            }
        }

        override fun onCrash(session: GeckoSession) {
            if (isCurrent(tab, session)) recoverSession(tab, session, "Page renderer crashed")
        }

        override fun onKill(session: GeckoSession) {
            if (isCurrent(tab, session)) recoverSession(tab, session, "Page renderer was restarted")
        }
    }

    private class ContentBlockingDelegateImpl(private val tab: Tab) : ContentBlocking.Delegate {
        override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
            if (!isCurrent(tab, session)) return
            val category = event.antiTrackingCategory
            val isAd = (category and (
                ContentBlocking.AntiTracking.AD or
                    ContentBlocking.AntiTracking.CONTENT or
                    ContentBlocking.AntiTracking.CRYPTOMINING
                )) != 0
            reportBlocked(tab, if (isAd) "ad" else "tracker")
        }
    }

    private class HistoryDelegateImpl(private val tab: Tab) : GeckoSession.HistoryDelegate {
        override fun onVisited(
            session: GeckoSession,
            url: String,
            lastVisitedURL: String?,
            flags: Int
        ): GeckoResult<Boolean> {
            if (!isCurrent(tab, session) || tab.private || url.startsWith("about:")) {
                return GeckoResult.fromValue(false)
            }
            val title = tab.title
            host?.activeContext()?.applicationContext?.let { context ->
                background { DataStore.get(context).recordVisit(url, title) }
            }
            return GeckoResult.fromValue(true)
        }
    }

    /**
     * GeckoView's default when no PromptDelegate is installed is to dismiss every
     * content prompt. That makes JavaScript confirmations and modern FedCM
     * sign-in/consent handoffs look like a tapped button did nothing. Keep the
     * implementation deliberately small, but surface the safe browser prompts
     * that are needed for ordinary navigation and sign-in.
     */
    private class BrowserPromptDelegate : GeckoSession.PromptDelegate {
        private fun activeActivity(): android.app.Activity? =
            host?.activeContext() as? android.app.Activity

        private fun title(value: String?, fallback: String): String =
            value?.takeIf { it.isNotBlank() } ?: fallback

        private fun targetLabel(uri: String?): String =
            UrlBar.hostOf(uri).ifBlank { uri?.takeIf { it.isNotBlank() } ?: "another page" }

        private fun dismissed(
            prompt: GeckoSession.PromptDelegate.BasePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = try {
            GeckoResult.fromValue(prompt.dismiss())
        } catch (error: Throwable) {
            Log.w(TAG, "could not dismiss content prompt", error)
            GeckoResult.fromValue<GeckoSession.PromptDelegate.PromptResponse>(null)
        }

        private fun complete(
            result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
            prompt: GeckoSession.PromptDelegate.BasePrompt,
            response: () -> GeckoSession.PromptDelegate.PromptResponse
        ) {
            if (prompt.isComplete()) return
            try {
                result.complete(response())
            } catch (error: Throwable) {
                Log.w(TAG, "could not complete content prompt", error)
                runCatching { result.complete(null) }
            }
        }

        private fun show(
            prompt: GeckoSession.PromptDelegate.BasePrompt,
            configure: (
                AlertDialog.Builder,
                GeckoResult<GeckoSession.PromptDelegate.PromptResponse>
            ) -> Unit
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val activity = activeActivity()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                return dismissed(prompt)
            }
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            try {
                val builder = AlertDialog.Builder(activity, R.style.Theme_Minimal_Dialog)
                configure(builder, result)
                builder.setOnCancelListener {
                    complete(result, prompt) { prompt.dismiss() }
                }
                builder.show()
            } catch (error: Throwable) {
                Log.w(TAG, "could not show content prompt", error)
                complete(result, prompt) { prompt.dismiss() }
            }
            return result
        }

        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = show(prompt) { builder, result ->
            builder.setTitle(title(prompt.title, "Message"))
                .setMessage(prompt.message.orEmpty())
                .setPositiveButton("OK") { _, _ ->
                    complete(result, prompt) { prompt.dismiss() }
                }
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = show(prompt) { builder, result ->
            builder.setTitle(title(prompt.title, "Confirm"))
                .setMessage(prompt.message.orEmpty())
                .setNegativeButton("Cancel") { _, _ ->
                    complete(result, prompt) {
                        prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)
                    }
                }
                .setPositiveButton("OK") { _, _ ->
                    complete(result, prompt) {
                        prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)
                    }
                }
        }

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val activity = activeActivity() ?: return dismissed(prompt)
            val input = EditText(activity).apply {
                setText(prompt.defaultValue.orEmpty())
                setSelectAllOnFocus(false)
                UiKeys.configureTextInput(this)
            }
            return show(prompt) { builder, result ->
                builder.setTitle(title(prompt.title, "Input"))
                    .setMessage(prompt.message.orEmpty())
                    .setView(input)
                    .setNegativeButton("Cancel") { _, _ ->
                        complete(result, prompt) { prompt.dismiss() }
                    }
                    .setPositiveButton("OK") { _, _ ->
                        complete(result, prompt) { prompt.confirm(input.text.toString()) }
                    }
            }
        }

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = show(prompt) { builder, result ->
            builder.setTitle("Leave this page?")
                .setMessage("Changes on this page may not be saved.")
                .setNegativeButton("Stay") { _, _ ->
                    complete(result, prompt) { prompt.confirm(AllowOrDeny.DENY) }
                }
                .setPositiveButton("Leave") { _, _ ->
                    complete(result, prompt) { prompt.confirm(AllowOrDeny.ALLOW) }
                }
        }

        override fun onRepostConfirmPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = show(prompt) { builder, result ->
            builder.setTitle("Resend form data?")
                .setMessage("Refreshing this page may submit the form again.")
                .setNegativeButton("Cancel") { _, _ ->
                    complete(result, prompt) { prompt.confirm(AllowOrDeny.DENY) }
                }
                .setPositiveButton("Resend") { _, _ ->
                    complete(result, prompt) { prompt.confirm(AllowOrDeny.ALLOW) }
                }
        }

        override fun onWebAuthnRelatedOriginPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.WebAuthnRelatedOriginPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = show(prompt) { builder, result ->
            builder.setTitle("Allow passkey sign-in?")
                .setMessage("${targetLabel(prompt.origin)} wants to use passkeys for ${prompt.rpId.orEmpty()}.")
                .setNegativeButton("Cancel") { _, _ ->
                    complete(result, prompt) { prompt.confirm(AllowOrDeny.DENY) }
                }
                .setPositiveButton("Allow") { _, _ ->
                    complete(result, prompt) { prompt.confirm(AllowOrDeny.ALLOW) }
                }
        }

        override fun onPopupPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.PopupPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = show(prompt) { builder, result ->
            builder.setTitle("Allow pop-up?")
                .setMessage("This page wants to open ${targetLabel(prompt.targetUri)}.")
                .setNegativeButton("Block") { _, _ ->
                    complete(result, prompt) { prompt.confirm(AllowOrDeny.DENY) }
                }
                .setPositiveButton("Allow") { _, _ ->
                    complete(result, prompt) { prompt.confirm(AllowOrDeny.ALLOW) }
                }
        }

        override fun onRedirectPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.RedirectPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = show(prompt) { builder, result ->
            builder.setTitle("Allow redirect?")
                .setMessage("This page wants to continue to ${targetLabel(prompt.targetUri)}.")
                .setNegativeButton("Block") { _, _ ->
                    complete(result, prompt) { prompt.confirm(AllowOrDeny.DENY) }
                }
                .setPositiveButton("Continue") { _, _ ->
                    complete(result, prompt) { prompt.confirm(AllowOrDeny.ALLOW) }
                }
        }

        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ChoicePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val allChoices = ArrayList<GeckoSession.PromptDelegate.ChoicePrompt.Choice>()
            for (choice in prompt.choices) {
                val children = choice.items
                if (children == null) {
                    allChoices += choice
                } else {
                    children.forEach { allChoices += it }
                }
            }
            val choices = allChoices.filter { !it.disabled && !it.separator }
            if (choices.isEmpty()) return dismissed(prompt)
            val labels: Array<CharSequence> = choices.map { it.label as CharSequence }.toTypedArray()
            return if (prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                val selected = BooleanArray(choices.size) { choices[it].selected }
                show(prompt) { builder, result ->
                    builder.setTitle(title(prompt.title, "Choose options"))
                        .setMessage(prompt.message)
                        .setMultiChoiceItems(labels, selected) { _, which, checked ->
                            selected[which] = checked
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            complete(result, prompt) { prompt.dismiss() }
                        }
                        .setPositiveButton("Done") { _, _ ->
                            val selectedChoices = choices.filterIndexed { index, _ -> selected[index] }.toTypedArray()
                            complete(result, prompt) { prompt.confirm(selectedChoices) }
                        }
                }
            } else {
                val initial = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
                show(prompt) { builder, result ->
                    builder.setTitle(title(prompt.title, "Choose an option"))
                        .setMessage(prompt.message)
                        .setSingleChoiceItems(labels, initial) { dialog, which ->
                            complete(result, prompt) { prompt.confirm(choices[which]) }
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            complete(result, prompt) { prompt.dismiss() }
                        }
                }
            }
        }

        override fun onSelectIdentityCredentialProvider(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.IdentityCredential.ProviderSelectorPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            if (prompt.providers.isEmpty()) return dismissed(prompt)
            val labels: Array<CharSequence> = prompt.providers.map {
                "${it.name} (${it.domain})" as CharSequence
            }.toTypedArray()
            return show(prompt) { builder, result ->
                builder.setTitle("Choose sign-in provider")
                    .setItems(labels) { dialog, which ->
                        complete(result, prompt) { prompt.confirm(which) }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        complete(result, prompt) { prompt.dismiss() }
                    }
            }
        }

        override fun onSelectIdentityCredentialAccount(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.IdentityCredential.AccountSelectorPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            if (prompt.accounts.isEmpty()) return dismissed(prompt)
            val labels: Array<CharSequence> = prompt.accounts.map {
                if (it.name.isBlank()) it.email else "${it.name} (${it.email})"
            }.map { it as CharSequence }.toTypedArray()
            return show(prompt) { builder, result ->
                builder.setTitle("Choose ${prompt.provider.name} account")
                    .setItems(labels) { dialog, which ->
                        complete(result, prompt) { prompt.confirm(which) }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        complete(result, prompt) { prompt.dismiss() }
                    }
            }
        }

        override fun onShowPrivacyPolicyIdentityCredential(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.IdentityCredential.PrivacyPolicyPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = show(prompt) { builder, result ->
            builder.setTitle("Continue with ${prompt.providerDomain}?")
                .setMessage("${prompt.host} wants to use this provider to sign you in.")
                .setNegativeButton("Cancel") { _, _ ->
                    complete(result, prompt) { prompt.confirm(false) }
                }
                .setPositiveButton("Agree") { _, _ ->
                    complete(result, prompt) { prompt.confirm(true) }
                }
        }
    }

    /**
     * Android hardware permissions remain denied by default. A third-party
     * storage request is different: it is commonly the explicit handoff that
     * lets a user-initiated identity provider finish a sign-in or consent flow.
     * Ask instead of silently denying it, while retaining the default deny for
     * location, notifications, camera, microphone, and persistent storage.
     */
    @OptIn(ExperimentalGeckoViewApi::class)
    private class PermissionDelegateImpl : GeckoSession.PermissionDelegate {
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback
        ) {
            callback.reject()
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            permission: GeckoSession.PermissionDelegate.ContentPermission
        ): GeckoResult<Int> {
            if (permission.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE) {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
            if (permission.permission != GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS) {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
            if (permission.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) {
                return GeckoResult.fromValue(permission.value)
            }
            // Earlier builds silently returned DENY here. Treat an inherited
            // denial as ask-again so an existing user can recover a login flow
            // after updating instead of being permanently stuck with it.

            val activity = host?.activeContext() as? android.app.Activity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
            val result = GeckoResult<Int>()
            val answered = AtomicBoolean(false)
            val respond: (Int) -> Unit = { value ->
                if (answered.compareAndSet(false, true)) result.complete(value)
            }
            val site = UrlBar.hostOf(permission.uri).ifBlank { "this site" }
            val provider = UrlBar.hostOf(permission.thirdPartyOrigin).ifBlank { "a sign-in provider" }
            try {
                AlertDialog.Builder(activity, R.style.Theme_Minimal_Dialog)
                    .setTitle("Allow sign-in storage?")
                    .setMessage("$provider wants to use its sign-in storage on $site. Allow only if you started this sign-in or consent flow.")
                    .setNegativeButton("Block") { _, _ ->
                        respond(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                    }
                    .setPositiveButton("Allow") { _, _ ->
                        respond(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                    }
                    .setOnCancelListener {
                        respond(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                    }
                    .show()
                permission.notifyShown()
            } catch (error: Throwable) {
                Log.w(TAG, "could not show storage-access prompt", error)
                respond(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
            return result
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            deviceOrigin: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback
        ) {
            callback.reject()
        }
    }

    /** Reopens only a renderer-crashed tab and reloads its last URL. */
    private fun recoverSession(tab: Tab, dead: GeckoSession, reason: String) {
        main.post {
            if (!isCurrent(tab, dead)) {
                return@post
            }
            val lastUrl = tab.url
            val wasActive = active === tab
            try {
                if (attachedView?.session === dead) attachedView?.releaseSession()
            } catch (error: Throwable) {
                Log.w(TAG, "could not release crashed Gecko session", error)
            }
            runCatching { dead.close() }

            try {
                val replacement = createSession(tab.private)
                tab.session = replacement
                bind(tab)
                val currentRuntime = runtime ?: BrowserApp.runtime
                    ?: throw IllegalStateException("Bundled browser runtime is unavailable")
                replacement.open(currentRuntime)
                if (wasActive) attachedView?.let(::attach)
                if (lastUrl.isNotBlank()) load(tab, lastUrl)
                host?.onSessionRecovered(tab)
            } catch (error: Throwable) {
                Log.e(TAG, "could not recover GeckoSession", error)
                host?.onSessionFailure(tab, reason)
            }
        }
    }

    /**
     * A content-blocking delegate can report many resources in one page burst,
     * and may not be on the Android main thread. Coalesce those signals first;
     * do not post a UI task and start a new database thread for every URL.
     */
    private fun reportBlocked(tab: Tab, kind: String) {
        // The Home screen exposes only the total, so do not parse every blocked
        // resource URL solely to persist a host that the UI never reads. The
        // short batch below records the current page host once per tab instead.
        val signal = BlockedSignal(tab, tab.blockedGeneration, kind)
        synchronized(blockedSignalLock) {
            pendingBlockedSignals += signal
            if (!blockedFlushScheduled) {
                blockedFlushScheduled = true
                main.postDelayed(blockedFlushRunnable, BLOCKED_BATCH_DELAY_MS)
            }
        }
    }

    /** Runs on the main loop once per short burst, not once per blocked request. */
    private fun flushBlockedSignals() {
        val signals = synchronized(blockedSignalLock) {
            blockedFlushScheduled = false
            ArrayList(pendingBlockedSignals).also { pendingBlockedSignals.clear() }
        }
        if (signals.isEmpty()) return

        val tabDeltas = LinkedHashMap<Tab, BlockedDelta>()
        val pageHosts = HashMap<Tab, String>()
        val databaseDeltas = LinkedHashMap<Pair<String, String>, Int>()
        for (signal in signals) {
            // A closed tab no longer needs UI or persistent accounting. The
            // check is deliberately on the main loop because `tabs` is UI state.
            if (!tabs.contains(signal.tab) || signal.generation != signal.tab.blockedGeneration) continue
            val delta = tabDeltas.getOrPut(signal.tab) { BlockedDelta() }
            if (signal.kind == "ad") delta.ads++ else delta.trackers++
            // Private browsing must not leave a blocked-request history behind.
            if (!signal.tab.private) {
                val pageHost = pageHosts.getOrPut(signal.tab) { signal.tab.host }
                val key = pageHost to signal.kind
                databaseDeltas[key] = (databaseDeltas[key] ?: 0) + 1
            }
        }
        if (tabDeltas.isEmpty()) return

        for ((tab, delta) in tabDeltas) {
            tab.blockedAds += delta.ads
            tab.blockedTrackers += delta.trackers
        }

        val currentHost = host
        currentHost?.activeContext()?.applicationContext?.let { context ->
            if (databaseDeltas.isNotEmpty()) {
                background { DataStore.get(context).recordBlockedBatch(databaseDeltas) }
            }
        }
        tabDeltas.keys.firstOrNull { it === active }?.let { currentHost?.onBlockedOnPage(it) }
    }

    private fun background(block: () -> Unit) {
        storageExecutor.execute {
            try {
                block()
            } catch (error: Throwable) {
                Log.w(TAG, "background work failed", error)
            }
        }
    }
}
