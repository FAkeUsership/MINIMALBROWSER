package com.minimal.browser

import android.content.Context
import android.net.Uri
import java.util.Locale
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
 * This path is exercised for many subresources on a modern page, so host rules
 * are indexed and matched label-by-label instead of linearly testing every
 * suffix rule for every request.
 */
object AdBlocker {

    private class Rules(val hosts: Set<String>, val patterns: Array<String>)

    @Volatile private var adRules = Rules(emptySet(), emptyArray())
    @Volatile private var trackerRules = Rules(emptySet(), emptyArray())
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
        val adHosts = LinkedHashSet<String>()
        val adPats = ArrayList<String>()
        val trHosts = LinkedHashSet<String>()
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
                        .lowercase(Locale.ROOT)
                    if (host.isNotEmpty()) (if (bucketTracker) trHosts else adHosts).add(host)
                }
                line.startsWith("/") -> {
                    val pattern = line.trim('/').lowercase(Locale.ROOT)
                    if (pattern.isNotEmpty()) (if (bucketTracker) trPats else adPats).add(pattern)
                }
                else -> {
                    // Bare domains retain the documented host-suffix behavior.
                    val host = line.lowercase(Locale.ROOT)
                    if (host.isNotEmpty()) (if (bucketTracker) trHosts else adHosts).add(host)
                }
            }
        }
        adRules = Rules(adHosts, adPats.toTypedArray())
        trackerRules = Rules(trHosts, trPats.toTypedArray())
    }

    val isReady: Boolean get() = loaded.get()

    val ruleCount: Int
        get() = adRules.hosts.size + adRules.patterns.size +
            trackerRules.hosts.size + trackerRules.patterns.size

    enum class Verdict { ALLOW, BLOCK_AD, BLOCK_TRACKER }

    fun check(url: String?): Verdict {
        if (url.isNullOrEmpty() || !Prefs.shieldsOn) return Verdict.ALLOW
        val requestUrl = url ?: return Verdict.ALLOW

        val blockAds = Prefs.blockAds
        val blockFingerprinting = Prefs.blockFingerprinting
        if (!blockAds && !blockFingerprinting) return Verdict.ALLOW

        val host = if (blockAds) {
            try {
                Uri.parse(requestUrl).host?.lowercase(Locale.ROOT)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        // A local variable ensures that substring and fingerprint checks share
        // one normalized URL instead of repeatedly doing case-insensitive scans.
        var normalizedUrl: String? = null
        fun normalized(): String = normalizedUrl ?: requestUrl.lowercase(Locale.ROOT).also {
            normalizedUrl = it
        }

        if (blockAds && host != null) {
            if (matchesHost(adRules.hosts, host)) return Verdict.BLOCK_AD
            if (adRules.patterns.isNotEmpty() && matchesPatterns(adRules.patterns, normalized())) {
                return Verdict.BLOCK_AD
            }
            if (matchesHost(trackerRules.hosts, host)) return Verdict.BLOCK_TRACKER
            if (trackerRules.patterns.isNotEmpty() && matchesPatterns(trackerRules.patterns, normalized())) {
                return Verdict.BLOCK_TRACKER
            }
        }

        if (blockFingerprinting && isFingerprintEndpoint(normalized())) {
            return Verdict.BLOCK_TRACKER
        }
        return Verdict.ALLOW
    }

    private fun isFingerprintEndpoint(normalizedUrl: String): Boolean =
        "/fingerprint" in normalizedUrl || "fingerprint.js" in normalizedUrl ||
            "device-fingerprint" in normalizedUrl || "canvas-fingerprint" in normalizedUrl

    /** Check `a.b.example` as a, b, and example suffixes against a hash set. */
    private fun matchesHost(hosts: Set<String>, host: String): Boolean {
        var candidate = host
        while (true) {
            if (candidate in hosts) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.lastIndex) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun matchesPatterns(patterns: Array<String>, normalizedUrl: String): Boolean {
        for (pattern in patterns) {
            if (normalizedUrl.contains(pattern)) return true
        }
        return false
    }
}
