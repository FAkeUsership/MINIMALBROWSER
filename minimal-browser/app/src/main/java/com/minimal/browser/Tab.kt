package com.minimal.browser

import android.graphics.Bitmap
import android.webkit.WebView
import java.util.UUID

/**
 * One browser tab backed by Android's system WebView.
 *
 * The old build stored a bundled-engine session here. Keeping browser state in
 * this small model lets the UI, history, tab cards, and page-only mode remain stable
 * while avoiding a bundled native browser process on Android 14+.
 */
class Tab(val private: Boolean) {
    var id: String = UUID.randomUUID().toString()

    /** The view is replaced if Android reports that its renderer died. */
    var webView: WebView? = null
    var defaultUserAgent: String = ""

    var url: String = ""
    var title: String = ""

    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
    var isSecure: Boolean = false

    /** Per-page request-block counters shown by the browser chrome. */
    var blockedAds: Int = 0
    var blockedTrackers: Int = 0

    var thumbnail: Bitmap? = null

    /** Reserved for the database schema; WebView restores the tab URL safely. */
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
