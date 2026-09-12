package com.minimal.browser

import android.content.Context
import android.content.SharedPreferences

/**
 * Every preference exposed by the Settings screen lives here.
 * Ported 1:1 from the `prefs` object + settings rows in browser-app-screens.html.
 */
object Prefs {

    private const val FILE = "minimal_browser_prefs"

    // keys
    private const val K_SHIELDS = "shields_on"
    private const val K_BLOCK_ADS = "block_ads"
    private const val K_BLOCK_FP = "block_fingerprinting"
    private const val K_HTTPS_ONLY = "https_only"
    private const val K_BW_THEME = "bw_theme"
    private const val K_TEXT_SIZE = "text_size"
    private const val K_SEARCH_ENGINE = "search_engine"
    private const val K_HOMEPAGE = "homepage"
    private const val K_CUSTOM_HOME = "custom_home"
    private const val K_HOLD_SECONDS = "hold_seconds"
    private const val K_JS = "javascript"
    private const val K_DESKTOP_UA = "desktop_ua"
    private const val K_FIRST_RUN = "first_run"
    private const val K_HOLD_HINT_SHOWN = "hold_hint_shown"

    lateinit var sp: SharedPreferences
        private set

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /* ---- Privacy & Shields ---- */
    var shieldsOn: Boolean
        get() = sp.getBoolean(K_SHIELDS, true)
        set(v) = sp.edit().putBoolean(K_SHIELDS, v).apply()

    var blockAds: Boolean
        get() = sp.getBoolean(K_BLOCK_ADS, true)
        set(v) = sp.edit().putBoolean(K_BLOCK_ADS, v).apply()

    var blockFingerprinting: Boolean
        get() = sp.getBoolean(K_BLOCK_FP, true)
        set(v) = sp.edit().putBoolean(K_BLOCK_FP, v).apply()

    var httpsOnly: Boolean
        get() = sp.getBoolean(K_HTTPS_ONLY, true)
        set(v) = sp.edit().putBoolean(K_HTTPS_ONLY, v).apply()

    /* ---- Appearance ---- */
    var bwTheme: Boolean
        get() = sp.getBoolean(K_BW_THEME, true)
        set(v) = sp.edit().putBoolean(K_BW_THEME, v).apply()

    /** Page zoom default, 80..140 — matches the HTML range slider. */
    var textSizePercent: Int
        get() = sp.getInt(K_TEXT_SIZE, 100)
        set(v) = sp.edit().putInt(K_TEXT_SIZE, v.coerceIn(80, 140)).apply()

    /* ---- Search / Home ---- */
    var searchEngine: String
        get() = sp.getString(K_SEARCH_ENGINE, SearchEngines.DDG.id) ?: SearchEngines.DDG.id
        set(v) = sp.edit().putString(K_SEARCH_ENGINE, v).apply()

    var homepageMode: String
        get() = sp.getString(K_HOMEPAGE, HomePageModes.INTERNAL) ?: HomePageModes.INTERNAL
        set(v) = sp.edit().putString(K_HOMEPAGE, v).apply()

    var customHomeUrl: String
        get() = sp.getString(K_CUSTOM_HOME, "") ?: ""
        set(v) = sp.edit().putString(K_CUSTOM_HOME, v).apply()

    /* ---- Content ---- */
    var javaScriptEnabled: Boolean
        get() = sp.getBoolean(K_JS, true)
        set(v) = sp.edit().putBoolean(K_JS, v).apply()

    var desktopUserAgent: Boolean
        get() = sp.getBoolean(K_DESKTOP_UA, false)
        set(v) = sp.edit().putBoolean(K_DESKTOP_UA, v).apply()

    /* ---- Full-screen gesture ---- */
    /** How long the Web rail button must be held. Default 5s, as specified. */
    var holdSeconds: Int
        get() = sp.getInt(K_HOLD_SECONDS, 5)
        set(v) = sp.edit().putInt(K_HOLD_SECONDS, v.coerceIn(1, 15)).apply()

    val holdMillis: Long get() = holdSeconds * 1000L

    /* ---- misc ---- */
    var firstRun: Boolean
        get() = sp.getBoolean(K_FIRST_RUN, true)
        set(v) = sp.edit().putBoolean(K_FIRST_RUN, v).apply()

    var holdHintShown: Boolean
        get() = sp.getBoolean(K_HOLD_HINT_SHOWN, false)
        set(v) = sp.edit().putBoolean(K_HOLD_HINT_SHOWN, v).apply()
}

object HomePageModes {
    const val INTERNAL = "internal"
    const val BLANK = "blank"
    const val CUSTOM = "custom"
}
