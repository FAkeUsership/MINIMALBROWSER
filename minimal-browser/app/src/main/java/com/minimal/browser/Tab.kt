package com.minimal.browser

import org.mozilla.geckoview.GeckoSession
import java.util.UUID

/**
 * One tab backed by one open GeckoSession.
 *
 * The rendering surface is deliberately not stored here: TabManager attaches
 * only the active session to one visible GeckoView, which avoids a background
 * compositor owning a hidden Android surface.
 */
class Tab(
    var session: GeckoSession,
    val private: Boolean
) {
    var id: String = UUID.randomUUID().toString()

    var url: String = ""
    var title: String = ""
    /** Transient custom error-page URI; never replaces the failed page URL. */
    var errorPageUrl: String? = null
    /** A restored background tab loads its saved URL only when selected. */
    var pendingRestoreUrl: String? = null

    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
    var isSecure: Boolean = false

    /** Per-page shield counters shown by the browser chrome. */
    var blockedAds: Int = 0
    var blockedTrackers: Int = 0
    /** Keeps a delayed shield batch from being shown against a newer page. */
    var blockedGeneration: Long = 0

    var sessionState: GeckoSession.SessionState? = null

    /** Last serializable session state, retained only for tab restore. */
    var stateJson: String? = null

    val host: String get() = UrlBar.hostOf(url)

    /** Single uppercase letter for the tab-card favicon chip. */
    val faviconLetter: String
        get() {
            val h = host
            if (h.isEmpty()) return if (private) "P" else "•"
            val c = h.trimStart('w', 'W').trimStart('.').firstOrNull() ?: h.first()
            return c.uppercaseChar().toString()
        }

    val displayTitle: String
        get() = title.ifEmpty { url.ifEmpty { if (private) "Private tab" else "New tab" } }

    val totalBlocked: Int get() = blockedAds + blockedTrackers
}
