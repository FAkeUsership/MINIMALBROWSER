package com.minimal.browser

import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.webkit.WebView

/**
 * One tab = one [WebView].
 *
 * Same shape as the Firefox build so the whole UI (rail, home, tabs, settings,
 * drawer, full screen) is shared between the two variants.
 */
class Tab(val webView: WebView, val private: Boolean) {

    var id: String = java.util.UUID.randomUUID().toString()

    var url: String = ""
    var title: String = ""

    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
    var isSecure: Boolean = false

    /** Per-page shield counters, shown as "3 blocked" in the address pill. */
    var blockedAds: Int = 0
    var blockedTrackers: Int = 0

    var thumbnail: android.graphics.Bitmap? = null

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

    /* ---------------- session restore ---------------- */

    fun saveState(): String? = try {
        val bundle = Bundle()
        webView.saveState(bundle)
        val bytes = bundleToBytes(bundle) ?: return null
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    fun restoreState(encoded: String): Boolean = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val bundle = bytesToBundle(bytes) ?: return false
        webView.restoreState(bundle) != null
    } catch (e: Exception) {
        false
    }

    private fun bundleToBytes(bundle: Bundle): ByteArray? {
        val parcel = android.os.Parcel.obtain()
        return try {
            bundle.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    private fun bytesToBundle(bytes: ByteArray): Bundle? {
        val parcel = android.os.Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel)
        } catch (e: Exception) {
            null
        } finally {
            parcel.recycle()
        }
    }

    companion object {
        /** Shared cookie jar switch — private tabs simply never write history. */
        fun setCookiesEnabled(context: Context, enabled: Boolean) {
            android.webkit.CookieManager.getInstance().setAcceptCookie(enabled)
        }
    }
}
