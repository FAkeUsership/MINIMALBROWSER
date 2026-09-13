package com.minimal.browser

import android.content.Context
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Request-level blocker used by Gecko navigation callbacks before eligible subframes load.
 *
 * Rules are loaded from `assets/blocklist.txt`:
 *   lines starting with `!` are comments
 *   `||host.tld^`  → host-suffix rule (matches host and every sub-domain)
 *   `/pattern`     → substring rule (matches anywhere in the URL)
 *   `##` header line selects the bucket: `## ad` or `## tracker`
 *
 * Every rule is evaluated *before* the request leaves the device, which is the
 * whole point of the shield pill in the top bar.
 */
object AdBlocker {

    private class Rules(val hosts: Array<String>, val patterns: Array<String>)

    @Volatile private var adRules = Rules(emptyArray(), emptyArray())
    @Volatile private var trackerRules = Rules(emptyArray(), emptyArray())
    private val loaded = AtomicBoolean(false)

    fun init(context: Context) {
        if (!loaded.compareAndSet(false, true)) return
        val text = try {
            context.assets.open("blocklist.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        parse(text)
    }

    private fun parse(text: String) {
        val adHosts = ArrayList<String>()
        val adPats = ArrayList<String>()
        val trHosts = ArrayList<String>()
        val trPats = ArrayList<String>()
        var bucketTracker = false

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('!')) continue
            if (line.startsWith("##")) {
                bucketTracker = line.contains("tracker", ignoreCase = true)
                continue
            }
            when {
                line.startsWith("||") && line.endsWith("^") -> {
                    val host = line.removePrefix("||").removeSuffix("^")
                    if (host.isNotEmpty()) (if (bucketTracker) trHosts else adHosts).add(host)
                }
                line.startsWith("/") -> {
                    val p = line.trim('/')
                    if (p.isNotEmpty()) (if (bucketTracker) trPats else adPats).add(p)
                }
                else -> {
                    // bare domain, treat as a host-suffix rule
                    (if (bucketTracker) trHosts else adHosts).add(line)
                }
            }
        }
        adRules = Rules(adHosts.toTypedArray(), adPats.toTypedArray())
        trackerRules = Rules(trHosts.toTypedArray(), trPats.toTypedArray())
    }

    val isReady: Boolean get() = loaded.get()

    val ruleCount: Int
        get() = adRules.hosts.size + adRules.patterns.size +
            trackerRules.hosts.size + trackerRules.patterns.size

    enum class Verdict { ALLOW, BLOCK_AD, BLOCK_TRACKER }

    fun check(url: String?): Verdict {
        if (url.isNullOrEmpty() || !Prefs.shieldsOn) return Verdict.ALLOW
        val host = try {
            Uri.parse(url).host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return Verdict.ALLOW
        // Ad/tracker blocking is one switch. Fingerprinting has its own explicit
        // setting so turning that setting off does not silently keep blocking it.
        if (Prefs.blockAds && matches(adRules, url, host)) return Verdict.BLOCK_AD
        if (Prefs.blockAds && matches(trackerRules, url, host)) return Verdict.BLOCK_TRACKER
        if (Prefs.blockFingerprinting && isFingerprintEndpoint(url)) return Verdict.BLOCK_TRACKER
        return Verdict.ALLOW
    }

    private fun isFingerprintEndpoint(url: String): Boolean {
        val u = url.lowercase()
        return "/fingerprint" in u || "fingerprint.js" in u ||
            "device-fingerprint" in u || "canvas-fingerprint" in u
    }

    private fun matches(rules: Rules, url: String, host: String): Boolean {
        for (h in rules.hosts) {
            if (host == h || host.endsWith(".$h")) return true
        }
        for (p in rules.patterns) {
            if (url.contains(p, ignoreCase = true)) return true
        }
        return false
    }
}
