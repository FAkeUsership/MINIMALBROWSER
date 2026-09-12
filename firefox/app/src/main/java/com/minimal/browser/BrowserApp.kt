package com.minimal.browser

import android.app.Application
import android.util.Log
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Owns the single GeckoRuntime (the Firefox engine) for the whole process and
 * keeps the privacy settings in sync with the Settings screen.
 */
class BrowserApp : Application() {

    companion object {
        private const val TAG = "MinimalBrowser"

        @Volatile
        var runtime: GeckoRuntime? = null
            private set

        fun requireRuntime(): GeckoRuntime =
            runtime ?: throw IllegalStateException("GeckoRuntime has not been created yet")
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createRuntime()
    }

    /* ------------------------------------------------------------------ */

    private fun createRuntime() {
        if (runtime != null) return

        val cbSettings = ContentBlocking.Settings.Builder()
            // Ads, analytics, social + content trackers, cryptomining.
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

        val builder = GeckoRuntimeSettings.Builder()
            // Firefox prefs: https-first everywhere, and no telemetry home phone.
            .arguments(
                arrayOf(
                    "-pref", "dom.security.https_only_mode=true",
                    "-pref", "dom.security.https_only_mode_pbm=true"
                )
            )
            .javaScriptEnabled(Prefs.javaScriptEnabled)
            .webFontsEnabled(true)
            .aboutConfigEnabled(false)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .consoleOutput(false)
            .automaticFontSizeAdjustment(false)
            .fontSizeFactor(Prefs.textSizePercent / 100f)
            .inputAutoZoomEnabled(true)
            .doubleTapZoomingEnabled(true)
            .contentBlocking(cbSettings)
            .preferredColorScheme(
                if (Prefs.bwTheme) GeckoRuntimeSettings.COLOR_SCHEME_DARK
                else GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
            )

        val settings = builder.build()
        runtime = GeckoRuntime.create(this, settings)

        // Settings that only exist as setters on the built settings object.
        applyHttpsOnly(settings)
        applyFingerprinting(settings)

        // Local, bundled EasyList/EasyPrivacy-style block list (request-level).
        AdBlocker.init(this)
        Log.i(TAG, "GeckoRuntime ready")
    }

    /** https-only mode; guarded so it degrades gracefully on any engine build. */
    private fun applyHttpsOnly(settings: GeckoRuntimeSettings) {
        val mode = if (Prefs.httpsOnly) {
            GeckoRuntimeSettings.HTTPS_ONLY
        } else {
            0 // HTTPS_ONLY_DISABLED
        }
        try {
            val m = GeckoRuntimeSettings::class.java.getMethod("setHttpsOnlyMode", Int::class.javaPrimitiveType)
            m.invoke(settings, mode)
        } catch (e: Throwable) {
            Log.w(TAG, "https-only pref unavailable: ${e.message}")
        }
    }

    /** Firefox's fingerprinting resistance (the "Block fingerprinting" row). */
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
        val cats = when {
            !Prefs.shieldsOn -> ContentBlocking.AntiTracking.NONE
            Prefs.blockAds -> aggressive
            else -> base
        }
        return cats or
            (if (Prefs.shieldsOn && Prefs.blockFingerprinting) ContentBlocking.AntiTracking.FINGERPRINTING else 0)
    }

    /* ------------------------------------------------------------------ */
    /*  Live re-configuration from the Settings screen                     */
    /* ------------------------------------------------------------------ */

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

        // Per-session tracking protection follows the global shield switch.
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
    }

    fun applyUserAgent() {
        TabManager.setUserAgentMode(
            if (Prefs.desktopUserAgent) 1 else 0 // USER_AGENT_MODE_DESKTOP : USER_AGENT_MODE_MOBILE
        )
    }
}
