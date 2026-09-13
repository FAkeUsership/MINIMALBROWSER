package com.minimal.browser

import android.graphics.Bitmap
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

/**
 * One tab = one [GeckoSession].
 *
 * Mirrors the tab cards in `#s-tabs`: favicon letter, title, host, a thumbnail,
 * and the shield counter shown in the address pill.
 */
class Tab(
    val session: GeckoSession,
    val private: Boolean
) {
    var id: String = UUID.randomUUID().toString()

    var url: String = ""
    var title: String = ""

    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
    var isSecure: Boolean = false

    /** Per-page shield counters, shown as "3 blocked" in the address pill. */
    var blockedAds: Int = 0
    var blockedTrackers: Int = 0

    var thumbnail: Bitmap? = null
    var sessionState: GeckoSession.SessionState? = null

    /** Only used while persisting. */
    var stateJson: String? = null

    val host: String get() = UrlBar.hostOf(url)

    /** Single uppercase letter for the favicon chip, like the HTML cards. */
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
