package com.minimal.browser

import android.text.format.DateUtils
import android.util.Base64
import org.mozilla.geckoview.WebRequestError

/**
 * Monochrome, on-brand error pages — shown when a load fails instead of Gecko's
 * own (English-only, unstyled) page.
 */
object ErrorPages {

    fun dataUrlFor(uri: String?, error: WebRequestError): String {
        val host = UrlBar.hostOf(uri)
        val (headline, detail) = describe(error)
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

    private fun describe(error: WebRequestError): Pair<String, String> {
        val code = error.code
        val text = when {
            code in 1..20 -> "Can’t connect"
            code in 21..30 -> "That address doesn’t look right"
            code in 31..50 -> "The connection was refused"
            code in 51..70 -> "Security error"
            code in 71..90 -> "This site looks unsafe"
            else -> "Page not loaded"
        }
        val detail = when {
            code in 51..90 ->
                "Minimal blocked this page because the connection is not secure. Turn off “Upgrade to HTTPS” in Settings if you are sure you want to continue."
            else ->
                "Check your connection, then reload. Shields stay on — nothing from this page reached the network."
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
