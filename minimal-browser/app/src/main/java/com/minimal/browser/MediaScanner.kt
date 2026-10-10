package com.minimal.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Finds the images and videos on the current web page for the toolbar's media
 * downloader.
 *
 * The page HTML is parsed with Jsoup. Each candidate is then probed over HTTP:
 * images are decoded to read their real pixel size, and videos are checked for
 * their file size. Only candidates that pass the user's minimum-size settings
 * are returned, and the largest variants come first.
 *
 * Known limits, stated plainly:
 *  - Media that a page creates with JavaScript after it loads is not seen.
 *  - Adaptive streams (HLS .m3u8 and DASH .mpd) are counted, not downloaded,
 *    because they are many small segments rather than one file.
 */
object MediaScanner {

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private const val TAG = "MediaScanner"
    private const val MAX_CANDIDATES = 400
    private const val MAX_RESULTS = 300
    private const val PROBE_THREADS = 6
    private const val MAX_IMAGE_BYTES = 30L * 1024 * 1024
    private const val MAX_HTML_BYTES = 10 * 1024 * 1024
    private const val THUMB_PX = 160

    private val IMAGE_EXT = Regex("\\.(jpe?g|png|gif|webp|bmp|avif|heic)(\\?.*)?$", RegexOption.IGNORE_CASE)
    private val VIDEO_EXT = Regex("\\.(mp4|webm|mov|m4v|mkv|ogv|3gp)(\\?.*)?$", RegexOption.IGNORE_CASE)
    private val STREAM_EXT = Regex("\\.(m3u8|mpd)(\\?.*)?$", RegexOption.IGNORE_CASE)
    private val HEIGHT_LABEL = Regex("(?<!\\d)(144|240|360|480|576|720|1080|1440|2160|4320)[pP](?![A-Za-z])")
    private val CSS_URL = Regex("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)", RegexOption.IGNORE_CASE)
    private val SRCSET_SPLIT = Regex(",\\s+")
    private val WHITESPACE = Regex("\\s+")

    private val MIME_BY_EXT = mapOf(
        "jpg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "avif" to "image/avif",
        "heic" to "image/heic",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "m4v" to "video/x-m4v",
        "mkv" to "video/x-matroska",
        "ogv" to "video/ogg",
        "3gp" to "video/3gpp"
    )

    enum class Kind { IMAGE, VIDEO }

    /** One downloadable file found on the page. */
    data class Item(
        val url: String,
        val kind: Kind,
        val fileName: String,
        val mimeType: String,
        val width: Int,
        val height: Int,
        /** File size in bytes, or -1 when the server did not say. */
        val bytes: Long,
        /** Resolution label such as "1080p" when the page provides one, else "". */
        val quality: String,
        /** Small JPEG preview for images, null for videos. */
        val thumbnail: ByteArray?
    )

    data class Result(
        val items: List<Item>,
        val candidates: Int,
        val streamsSkipped: Int,
        val hiddenBySize: Int,
        val error: String?
    )

    /**
     * Blocking call: run it on a background thread. Never call it on the UI thread.
     * Minimum sizes come from Settings. 0 means no limit.
     */
    fun scan(pageUrl: String, minImagePx: Int, minVideoMb: Int): Result {
        val document = try {
            Jsoup.connect(pageUrl)
                .userAgent(USER_AGENT)
                .timeout(20_000)
                .followRedirects(true)
                .maxBodySize(MAX_HTML_BYTES)
                .get()
        } catch (error: Exception) {
            Log.w(TAG, "page could not be read", error)
            val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            return Result(emptyList(), 0, 0, 0, "The page could not be read: $reason")
        }

        // Relative links resolve against the final URL after any redirects.
        val base = document.location().ifBlank { pageUrl }
        val imageUrls = LinkedHashSet<String>()
        val videoUrls = LinkedHashMap<String, String>()
        var streams = 0

        fun offer(raw: String?, quality: String = "", hint: Kind? = null) {
            val url = resolve(base, raw) ?: return
            when {
                STREAM_EXT.containsMatchIn(url) -> streams++
                hint == Kind.VIDEO || VIDEO_EXT.containsMatchIn(url) -> videoUrls.putIfAbsent(url, quality)
                hint == Kind.IMAGE || IMAGE_EXT.containsMatchIn(url) -> imageUrls.add(url)
            }
        }

        val lazySrcAttrs = listOf(
            "src", "data-src", "data-original", "data-lazy-src", "data-lazy",
            "data-full-src", "data-hi-res-src", "data-zoom-src"
        )
        val srcsetAttrs = listOf("srcset", "data-srcset", "data-lazy-srcset")

        // <img> tags, including lazy-loading attributes used by most news and shop sites.
        for (img in document.select("img")) {
            lazySrcAttrs.forEach { offer(img.attr(it), hint = Kind.IMAGE) }
            srcsetAttrs.forEach { attr -> bestFromSrcset(img.attr(attr))?.let { offer(it, hint = Kind.IMAGE) } }
        }
        // <picture><source srcset=...> offers the high-resolution variants.
        for (source in document.select("picture source")) {
            srcsetAttrs.forEach { attr -> bestFromSrcset(source.attr(attr))?.let { offer(it, hint = Kind.IMAGE) } }
        }
        // <video> sources, with the resolution label when the site provides one.
        for (video in document.select("video[src]")) {
            offer(video.attr("src"), qualityOf("", video.attr("src")), Kind.VIDEO)
        }
        for (source in document.select("video source[src]")) {
            val raw = source.attr("src")
            val label = source.attr("label").ifBlank { source.attr("res") }
            offer(raw, qualityOf(label, raw), Kind.VIDEO)
        }
        // Open Graph and Twitter card metadata.
        for (meta in document.select("meta[property], meta[name]")) {
            val key = meta.attr("property").ifBlank { meta.attr("name") }.lowercase(Locale.US)
            val content = meta.attr("content")
            when (key) {
                "og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src" ->
                    offer(content, hint = Kind.IMAGE)
                "og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream" ->
                    offer(content, qualityOf("", content), Kind.VIDEO)
            }
        }
        // Links that point straight at a file, such as a "Download original" button.
        for (anchor in document.select("a[href]")) {
            offer(anchor.attr("href"))
        }
        // Inline CSS backgrounds such as style="background-image:url(...)".
        for (element in document.select("[style]")) {
            CSS_URL.findAll(element.attr("style")).forEach { match ->
                offer(match.groupValues[1], hint = Kind.IMAGE)
            }
        }

        val candidates = imageUrls.size + videoUrls.size
        val tasks = ArrayList<Callable<Item?>>()
        imageUrls.take(MAX_CANDIDATES).forEachIndexed { index, url ->
            tasks += Callable { probeImage(url, pageUrl, index + 1) }
        }
        videoUrls.entries.take(MAX_CANDIDATES).forEachIndexed { index, (url, quality) ->
            tasks += Callable { probeVideo(url, pageUrl, quality, index + 1) }
        }

        val pool = Executors.newFixedThreadPool(PROBE_THREADS)
        val probed: List<Item> = try {
            pool.invokeAll(tasks).mapNotNull { future -> runCatching { future.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }

        var hidden = 0
        val minVideoBytes = minVideoMb * 1024L * 1024L
        val kept = probed.filter { item ->
            val pass = when (item.kind) {
                Kind.IMAGE -> minImagePx <= 0 || minOf(item.width, item.height) >= minImagePx
                Kind.VIDEO -> minVideoMb <= 0 || item.bytes < 0 || item.bytes >= minVideoBytes
            }
            if (!pass) hidden++
            pass
        }

        // Highest quality first: largest image area, then largest video file.
        val sorted = kept
            .sortedWith(
                compareBy<Item> { it.kind }
                    .thenByDescending { it.width.toLong() * it.height }
                    .thenByDescending { it.bytes }
            )
            .take(MAX_RESULTS)

        return Result(sorted, candidates, streams, hidden, null)
    }

    /* ------------------------------------------------------------------ */
    /*  probing                                                            */
    /* ------------------------------------------------------------------ */

    /** Downloads the image once to read its real size and make a small preview. */
    private fun probeImage(url: String, referer: String, number: Int): Item? {
        var connection: HttpURLConnection? = null
        try {
            connection = open(url, referer, null)
            if (connection.responseCode !in 200..299) return null
            val mime = mimeOf(connection)
            if (mime.isNotEmpty() && !mime.startsWith("image/")) return null
            val bytes = readAll(connection.inputStream, MAX_IMAGE_BYTES) ?: return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null // SVG or a format this device cannot decode

            val ext = extFor(Kind.IMAGE, mime, url)
            return Item(
                url = url,
                kind = Kind.IMAGE,
                fileName = fileNameFor(url, "image", number, ext),
                mimeType = MIME_BY_EXT[ext] ?: "image/jpeg",
                width = width,
                height = height,
                bytes = bytes.size.toLong(),
                quality = "",
                thumbnail = thumbnail(bytes, width, height)
            )
        } catch (error: Exception) {
            Log.d(TAG, "image skipped: $url", error)
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /** Asks for only the first byte so the file size is known without downloading it. */
    private fun probeVideo(url: String, referer: String, quality: String, number: Int): Item? {
        var connection: HttpURLConnection? = null
        try {
            connection = open(url, referer, "bytes=0-0")
            val code = connection.responseCode
            if (code != 200 && code != 206) return null
            val mime = mimeOf(connection)
            // An HTML page or JSON error body is not a video, even if the link looked like one.
            if (mime.startsWith("text/") || mime == "application/json") return null

            val total = if (code == 206) {
                connection.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')
                    ?.trim()
                    ?.toLongOrNull() ?: -1L
            } else {
                connection.contentLengthLong
            }
            val ext = extFor(Kind.VIDEO, mime, url)
            return Item(
                url = url,
                kind = Kind.VIDEO,
                fileName = fileNameFor(url, "video", number, ext),
                mimeType = MIME_BY_EXT[ext] ?: "video/mp4",
                width = 0,
                height = 0,
                bytes = total,
                quality = quality,
                thumbnail = null
            )
        } catch (error: Exception) {
            Log.d(TAG, "video skipped: $url", error)
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /* ------------------------------------------------------------------ */
    /*  helpers                                                            */
    /* ------------------------------------------------------------------ */

    private fun open(url: String, referer: String, range: String?): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")
        if (referer.isNotBlank()) connection.setRequestProperty("Referer", referer)
        if (range != null) connection.setRequestProperty("Range", range)
        return connection
    }

    private fun mimeOf(connection: HttpURLConnection): String =
        connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()

    /** Reads the whole stream, or returns null when it is larger than [limit]. */
    private fun readAll(input: InputStream, limit: Long): ByteArray? {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) return null
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }

    /** Small JPEG preview, so the list shows what each image is without storing full files. */
    private fun thumbnail(bytes: ByteArray, width: Int, height: Int): ByteArray? {
        return try {
            var sample = 1
            val longest = maxOf(width, height)
            while (longest / (sample * 2) >= THUMB_PX) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            try {
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        } catch (error: Exception) {
            null
        }
    }

    /**
     * Resolves a raw attribute value against the page URL. Only http(s) survives.
     * data: and blob: URLs live only in the page and cannot be downloaded.
     */
    private fun resolve(base: String, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("data:", true) || value.startsWith("blob:", true) ||
            value.startsWith("javascript:", true)
        ) return null
        return try {
            val url = URL(URL(base), value)
            if (url.protocol != "http" && url.protocol != "https") null
            else url.toString().substringBefore('#').replace(" ", "%20")
        } catch (error: Exception) {
            null
        }
    }

    /** Picks the largest entry from a srcset such as "a.jpg 1x, b.jpg 2x" or "a.jpg 480w, b.jpg 1200w". */
    private fun bestFromSrcset(srcset: String): String? {
        if (srcset.isBlank()) return null
        return srcset.split(SRCSET_SPLIT)
            .mapNotNull { part ->
                val tokens = part.trim().split(WHITESPACE)
                val url = tokens.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val descriptor = tokens.getOrNull(1)?.lowercase(Locale.US).orEmpty()
                val score = if (descriptor.endsWith("w") || descriptor.endsWith("x")) {
                    descriptor.dropLast(1).toDoubleOrNull() ?: 1.0
                } else {
                    1.0
                }
                url to score
            }
            .maxByOrNull { it.second }
            ?.first
    }

    /** "1080p" from an explicit label such as "1080" or "720p", else from the URL text. */
    private fun qualityOf(label: String, url: String): String {
        val explicit = label.trim()
        if (explicit.isNotEmpty()) {
            return if (explicit.all { it.isDigit() }) "${explicit}p" else explicit
        }
        return HEIGHT_LABEL.find(url)?.value?.lowercase(Locale.US) ?: ""
    }

    /** Extension from the file's MIME type first, then the URL, then a safe default. */
    private fun extFor(kind: Kind, mime: String, url: String): String {
        val fromMime = MIME_BY_EXT.entries.firstOrNull { it.value == mime }?.key
        if (fromMime != null) return fromMime
        val regex = if (kind == Kind.IMAGE) IMAGE_EXT else VIDEO_EXT
        val fromUrl = regex.find(url)?.groupValues?.get(1)?.lowercase(Locale.US)
        return when {
            fromUrl == null -> if (kind == Kind.IMAGE) "jpg" else "mp4"
            fromUrl == "jpeg" -> "jpg"
            else -> fromUrl
        }
    }

    private fun fileNameFor(url: String, prefix: String, number: Int, ext: String): String {
        // URI.getPath() returns the decoded path, so "my%20photo.jpg" becomes "my photo.jpg".
        val last = runCatching { URI(url).path }.getOrNull()?.substringAfterLast('/').orEmpty()
        val stem = last.substringBeforeLast('.', last).ifBlank { "$prefix-$number" }.take(120)
        return "$stem.$ext"
    }
}
