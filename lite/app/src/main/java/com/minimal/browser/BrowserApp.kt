package com.minimal.browser

import android.app.Application
import android.util.Base64
import android.text.format.DateUtils

/**
 * The Lite build runs on the platform WebView, so there is no engine runtime to
 * own — this class just bootstraps preferences, the database and the block list.
 *
 * Keeping the class name means `MainActivity` and `SettingsScreen` work against
 * both builds without changes.
 */
class BrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        AdBlocker.init(this)
        // Warm the database on a worker thread so the first screen is instant.
        Thread {
            try {
                DataStore.get(this)
            } catch (_: Exception) {
            }
        }.start()
    }

    /* ---- live re-configuration from the Settings screen ---- */

    fun applyShields() {
        // AdBlocker.check() reads Prefs.shieldsOn / Prefs.blockAds per request.
        TabManager.setTrackingProtection(Prefs.shieldsOn)
    }

    fun applyTextSize() {
        TabManager.setTextZoom(Prefs.textSizePercent)
    }

    fun applyTheme() {
        // Nothing to reconfigure: the chrome is monochrome by design and the
        // page keeps the site's own colours.
    }

    fun applyJavaScript() {
        TabManager.setAllowJavascript(Prefs.javaScriptEnabled)
    }

    fun applyUserAgent() {
        TabManager.setUserAgentMode(if (Prefs.desktopUserAgent) 1 else 0)
    }

    fun applyHttpsOnly() {
        TabManager.applyMixedContent()
    }
}

/**
 * Monochrome, on-brand error pages — shown when a load fails instead of the
 * WebView's own unstyled page.
 */
object ErrorPages {

    fun dataUrlFor(uri: String?, errorCode: Int): String {
        val host = UrlBar.hostOf(uri)
        val (headline, detail) = describe(errorCode)
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body{height:100%}
              body{margin:0;background:#0a0a0a;color:#f2f2f2;
                   font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
                   display:flex;align-items:center;justify-content:center;padding:24px}
              .card{max-width:520px;border:1px solid #262626;background:#161616;border-radius:16px;padding:26px 28px}
              h1{font-size:22px;margin:0 0 8px;letter-spacing:-.02em}
              p{font-size:14px;line-height:1.6;color:#9c9c9c;margin:6px 0}
              code{font-size:12px;color:#f2f2f2;background:#1d1d1d;border:1px solid #333;
                   border-radius:8px;padding:2px 8px;word-break:break-all;display:inline-block;margin-top:6px}
              .dot{width:38px;height:38px;border-radius:12px;background:#fff;color:#000;font-weight:800;
                   display:flex;align-items:center;justify-content:center;margin-bottom:14px}
            </style></head>
            <body><div class="card">
              <div class="dot">M</div>
              <h1>$headline</h1>
              <p>$detail</p>
              <code>${escape(host.ifEmpty { uri ?: "unknown" })}</code>
            </div></body></html>
        """.trimIndent()

        return "data:text/html;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
    }

    private fun describe(errorCode: Int): Pair<String, String> {
        val text = when (errorCode) {
            android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> "Can’t find that site"
            android.webkit.WebViewClient.ERROR_CONNECT,
            android.webkit.WebViewClient.ERROR_TIMEOUT -> "Can’t connect"
            android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "Secure connection failed"
            android.webkit.WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "That address isn’t a web page"
            android.webkit.WebViewClient.ERROR_BAD_URL -> "That address doesn’t look right"
            else -> "Page not loaded"
        }
        val detail = when (errorCode) {
            android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ->
                "Minimal refused this connection because the certificate could not be verified."
            else ->
                "Check your connection, then reload. Shields stay on — nothing else from this page was fetched."
        }
        return text to detail
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** Human-readable sizes and dates for the Downloads / History lists. */
object Fmt {
    fun bytes(n: Long): String = when {
        n < 0 -> "—"
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        n < 1024L * 1024 * 1024 -> String.format("%.1f MB", n / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", n / (1024.0 * 1024.0 * 1024.0))
    }

    fun when_ago(ts: Long): String {
        val now = System.currentTimeMillis()
        if (now - ts < DateUtils.MINUTE_IN_MILLIS) return "just now"
        return DateUtils.getRelativeTimeSpanString(ts, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}
