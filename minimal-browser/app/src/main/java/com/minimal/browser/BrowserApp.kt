package com.minimal.browser

import android.app.Application
import android.content.Context
import android.util.Log
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * Owns Minimal Browser's single bundled GeckoView runtime.
 *
 * Runtime creation is intentionally lazy. Home, Tabs, Settings, and the app
 * shell do not start native browser code; it is created only when Web or a
 * real navigation first needs it. That isolates native startup from normal UI
 * navigation and keeps a startup failure contained at the browser boundary.
 */
class BrowserApp : Application() {

    companion object {
        private const val TAG = "MinimalBrowser"
        private val runtimeLock = Any()

        @Volatile
        var runtime: GeckoRuntime? = null
            private set

        /** Non-null only when lazy bundled-engine startup failed. */
        @Volatile
        var startupFailure: String? = null
            private set

        val engineReady: Boolean get() = runtime != null

        /**
         * Starts the bundled runtime once, on demand. This is called from the
         * activity's guarded Web/tab boundary rather than Application.onCreate.
         */
        fun ensureRuntime(context: Context): GeckoRuntime? = synchronized(runtimeLock) {
            runtime?.let { return@synchronized it }
            startupFailure = null
            try {
                createRuntime(context.applicationContext)
                runtime
            } catch (error: Throwable) {
                runtime = null
                startupFailure = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
                Log.e(TAG, "Bundled Gecko runtime could not start", error)
                null
            }
        }

        private fun createRuntime(context: Context) {
            if (runtime != null) return

            val contentBlocking = ContentBlocking.Settings.Builder()
                .antiTracking(
                    ContentBlocking.AntiTracking.AD or
                        ContentBlocking.AntiTracking.ANALYTIC or
                        ContentBlocking.AntiTracking.SOCIAL or
                        ContentBlocking.AntiTracking.CONTENT or
                        ContentBlocking.AntiTracking.CRYPTOMINING or
                        ContentBlocking.AntiTracking.STP or
                        ContentBlocking.AntiTracking.FINGERPRINTING
                )
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .cookiePurging(true)
                .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                .build()

            val settings = GeckoRuntimeSettings.Builder()
                .allowInsecureConnections(
                    if (Prefs.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY
                    else GeckoRuntimeSettings.ALLOW_ALL
                )
                .javaScriptEnabled(Prefs.javaScriptEnabled)
                .webFontsEnabled(true)
                .aboutConfigEnabled(false)
                // This is an end-user browser build. Do not keep a remote
                // DevTools endpoint and its associated bookkeeping alive while
                // pages are rendering just because the APK is debug-signed.
                .remoteDebuggingEnabled(false)
                .consoleOutput(false)
                .automaticFontSizeAdjustment(false)
                .fontSizeFactor(Prefs.textSizePercent / 100f)
                .inputAutoZoomEnabled(true)
                .doubleTapZoomingEnabled(true)
                .contentBlocking(contentBlocking)
                .preferredColorScheme(
                    if (Prefs.bwTheme) GeckoRuntimeSettings.COLOR_SCHEME_DARK
                    else GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
                )
                .build()

            runtime = GeckoRuntime.create(context, settings)
            applyHttpsOnly(settings)
            applyFingerprinting(settings)
            TabManager.init(runtime!!)
            Log.i(TAG, "Bundled Gecko runtime ready")
        }

        /** Applies GeckoView's supported HTTPS-only runtime preference. */
        private fun applyHttpsOnly(settings: GeckoRuntimeSettings) {
            settings.setAllowInsecureConnections(
                if (Prefs.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY
                else GeckoRuntimeSettings.ALLOW_ALL
            )
        }

        private fun applyFingerprinting(settings: GeckoRuntimeSettings) {
            settings.setFingerprintingProtection(Prefs.blockFingerprinting)
            settings.contentBlocking.setAntiTracking(trackingCategories())
        }

        private fun trackingCategories(): Int {
            val base = ContentBlocking.AntiTracking.SOCIAL or ContentBlocking.AntiTracking.ANALYTIC
            val aggressive = base or
                ContentBlocking.AntiTracking.AD or
                ContentBlocking.AntiTracking.CONTENT or
                ContentBlocking.AntiTracking.CRYPTOMINING or
                ContentBlocking.AntiTracking.STP
            val categories = when {
                !Prefs.shieldsOn -> ContentBlocking.AntiTracking.NONE
                Prefs.blockAds -> aggressive
                else -> base
            }
            return categories or if (Prefs.shieldsOn && Prefs.blockFingerprinting) {
                ContentBlocking.AntiTracking.FINGERPRINTING
            } else {
                0
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        Prefs.init(this)
        AdBlocker.init(this)
        // Do not create GeckoRuntime here. It contains the large native engine
        // and is deliberately initialized only after the user enters Web.
    }

    /** Reapply live Settings changes to an already-started bundled runtime. */
    fun applyShields() {
        val settings = runtime?.settings ?: return
        settings.contentBlocking.setAntiTracking(trackingCategories())
        settings.contentBlocking.setEnhancedTrackingProtectionLevel(
            if (Prefs.shieldsOn) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.NONE
        )
        settings.contentBlocking.setSafeBrowsing(
            if (Prefs.shieldsOn) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE
        )
        settings.contentBlocking.setCookieBehavior(
            if (Prefs.shieldsOn) ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
            else ContentBlocking.CookieBehavior.ACCEPT_ALL
        )
        applyFingerprinting(settings)
        TabManager.setTrackingProtection(Prefs.shieldsOn)
    }

    fun applyTextSize() {
        runtime?.settings?.apply {
            automaticFontSizeAdjustment = false
            fontSizeFactor = Prefs.textSizePercent / 100f
        }
    }

    fun applyTheme() {
        runtime?.settings?.preferredColorScheme =
            if (Prefs.bwTheme) GeckoRuntimeSettings.COLOR_SCHEME_DARK
            else GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
    }

    fun applyJavaScript() {
        runtime?.settings?.javaScriptEnabled = Prefs.javaScriptEnabled
        TabManager.setAllowJavascript(Prefs.javaScriptEnabled)
    }

    fun applyHttpsOnly() {
        runtime?.settings?.let { settings -> Companion.applyHttpsOnly(settings) }
    }

    fun applyUserAgent() {
        TabManager.setUserAgentMode(
            if (Prefs.desktopUserAgent) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        )
    }
}
