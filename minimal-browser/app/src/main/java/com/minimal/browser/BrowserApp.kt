package com.minimal.browser

import android.app.Application
import android.webkit.WebView

/**
 * Process-wide setup for Minimal Browser.
 *
 * Browsing uses Android's maintained System WebView instead of embedding a
 * second native runtime. That removes the native library / child-process startup
 * path that was crashing when Web or Tabs was selected.
 */
class BrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        Prefs.init(this)
        AdBlocker.init(this)
        // Provider diagnostics must never make the Home shell fail to start.
        runCatching { WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) }
    }

    /** Reapply live Settings changes to all existing browser tabs. */
    fun applyShields() = TabManager.applySettings()
    fun applyTextSize() = TabManager.applySettings()
    fun applyTheme() = TabManager.applySettings()
    fun applyJavaScript() = TabManager.applySettings()
    fun applyUserAgent() = TabManager.applySettings()
}
