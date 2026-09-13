package com.minimal.browser

import android.net.Uri

/**
 * The search engines offered by the "Search engine" settings row.
 * The HTML shipped DuckDuckGo / Google / Bing — all three are wired up for real.
 */
object SearchEngines {

    data class Engine(val id: String, val label: String, val template: String, val home: String) {
        /** `{q}` is replaced with the URL-encoded query. */
        fun searchUrl(query: String): String =
            template.replace("{q}", Uri.encode(query))
    }

    val DDG = Engine(
        id = "ddg",
        label = "DuckDuckGo (default)",
        template = "https://duckduckgo.com/?q={q}",
        home = "https://duckduckgo.com"
    )
    val GOOGLE = Engine(
        id = "google",
        label = "Google",
        template = "https://www.google.com/search?q={q}",
        home = "https://www.google.com"
    )
    val BING = Engine(
        id = "bing",
        label = "Bing",
        template = "https://www.bing.com/search?q={q}",
        home = "https://www.bing.com/search?q={q}"
    )
    val STARTPAGE = Engine(
        id = "startpage",
        label = "Startpage",
        template = "https://www.startpage.com/sp/search?query={q}",
        home = "https://www.startpage.com"
    )
    val BRAVESEARCH = Engine(
        id = "brave",
        label = "Brave Search",
        template = "https://search.brave.com/search?q={q}",
        home = "https://search.brave.com"
    )

    val ALL = listOf(DDG, GOOGLE, BING, STARTPAGE, BRAVESEARCH)

    fun byId(id: String): Engine = ALL.firstOrNull { it.id == id } ?: DDG

    fun current(): Engine = byId(Prefs.searchEngine)
}

/**
 * Turns whatever the user typed into something we can load.
 * Ported from the HTML `homeSearch` handler, but actually navigating this time.
 */
object UrlBar {

    /** Covers mailto:, tel:, geo:, intent:, about:, data:, and normal http(s). */
    private val EXPLICIT_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    private val URLISH = Regex(
        "^([a-zA-Z][a-zA-Z0-9+.-]*://)|" +                    // explicit scheme
        "^localhost(:\\d+)?(/.*)?$|" +
        "^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?(/.*)?$|" +          // IPv4
        "^[^\\s/]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$"              // host.tld
    )

    fun isUrl(input: String): Boolean {
        val s = input.trim()
        if (s.isEmpty()) return false
        if (EXPLICIT_SCHEME.containsMatchIn(s)) return true
        return URLISH.matches(s) && !s.contains(' ')
    }

    /** https-first: bare hosts get https://, while every explicit scheme stays intact. */
    fun normalize(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return s
        return if (EXPLICIT_SCHEME.containsMatchIn(s)) s else "https://$s"
    }

    /** Resolve the address-bar input into a URL to load. */
    fun resolve(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        return if (isUrl(s)) normalize(s) else SearchEngines.current().searchUrl(s)
    }

    fun hostOf(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return try {
            Uri.parse(url).host ?: url
        } catch (e: Exception) {
            url
        }
    }

    /** "verge.example" for the address pill (path trimmed like the HTML did). */
    fun shortLabel(url: String?): String {
        if (url.isNullOrEmpty()) return "Search or type a URL"
        return try {
            val u = Uri.parse(url)
            val host = u.host ?: url
            val path = u.path?.trimStart('/')?.substringBefore('/') ?: ""
            if (path.isEmpty()) host else "$host/${u.path!!.trimStart('/')}"
        } catch (e: Exception) {
            url
        }
    }
}
