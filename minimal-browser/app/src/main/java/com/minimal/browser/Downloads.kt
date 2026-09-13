package com.minimal.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import org.mozilla.geckoview.WebResponse
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * User-visible downloads for responses GeckoView cannot render.
 *
 * Gecko hands the browser the already-authorized response stream. Copying that
 * stream preserves signed URLs, cookies, and POST-backed downloads that would
 * fail if the app issued a second, unauthenticated DownloadManager request.
 * Android 10+ output is owned MediaStore.Downloads content; older devices use
 * app-external storage and share through FileProvider.
 */
object Downloads {

    private const val TAG = "Downloads"
    private const val PROGRESS_INTERVAL_MS = 400L
    private const val BUFFER_SIZE = 64 * 1024

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2) { work ->
        Thread(work, "minimal-browser-download").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val transfers = ConcurrentHashMap<String, Transfer>()

    /** Immutable details presented before the user starts a download. */
    data class Request(
        val id: String = UUID.randomUUID().toString(),
        val fileName: String,
        val sourceUrl: String,
        val mimeType: String,
        val expectedBytes: Long,
        val requestExternalApp: Boolean
    )

    /** Lightweight live state used by the Downloads screen while copying. */
    data class ActiveDownload(
        val id: String,
        val fileName: String,
        val sourceUrl: String,
        val mimeType: String,
        val receivedBytes: Long,
        val expectedBytes: Long,
        val startedAt: Long
    )

    /** Result emitted only after the byte stream is fully written and published. */
    data class CompletedDownload(
        val request: Request,
        val localUri: Uri,
        val bytes: Long
    )

    interface Listener {
        fun onStarted(request: Request)
        fun onProgress(request: Request, receivedBytes: Long, expectedBytes: Long)
        fun onCompleted(download: CompletedDownload)
        fun onFailed(request: Request, reason: String)
    }

    private class Transfer(val request: Request) {
        val startedAt = System.currentTimeMillis()
        @Volatile var receivedBytes: Long = 0
    }

    private data class Target(
        val uri: Uri,
        val output: OutputStream,
        val legacyFile: File? = null
    )

    /** Read metadata without consuming the GeckoView response stream. */
    fun describe(response: WebResponse): Request? {
        val sourceUrl = response.uri?.trim().orEmpty()
        if (sourceUrl.isEmpty()) return null
        val mime = response.headers["Content-Type"]
            ?.substringBefore(';')
            ?.trim()
            ?.ifBlank { null }
            ?: "application/octet-stream"
        val bytes = response.headers["Content-Length"]?.trim()?.toLongOrNull()?.takeIf { it >= 0L } ?: -1L
        return Request(
            fileName = fileNameFrom(sourceUrl, response.headers),
            sourceUrl = sourceUrl,
            mimeType = mime,
            expectedBytes = bytes,
            requestExternalApp = response.requestExternalApp == true
        )
    }

    /** A response declined in the confirmation dialog must have its stream closed. */
    fun discard(response: WebResponse) {
        runCatching { response.body?.close() }
            .onFailure { Log.w(TAG, "could not close cancelled download response", it) }
    }

    /** Snapshot for the in-app Downloads list. Completed items are stored separately. */
    fun active(): List<ActiveDownload> = transfers.values
        .map { transfer ->
            val request = transfer.request
            ActiveDownload(
                id = request.id,
                fileName = request.fileName,
                sourceUrl = request.sourceUrl,
                mimeType = request.mimeType,
                receivedBytes = transfer.receivedBytes,
                expectedBytes = request.expectedBytes,
                startedAt = transfer.startedAt
            )
        }
        .sortedByDescending { it.startedAt }

    /**
     * Start a previously confirmed response. Results are always delivered on
     * Android's main thread so UI callers never mutate views from the copy task.
     * @return false when the stream/destination cannot be reserved.
     */
    fun start(context: Context, response: WebResponse, request: Request, listener: Listener): Boolean {
        val body = response.body ?: run {
            dispatch { listener.onFailed(request, "The site did not provide a readable download stream") }
            return false
        }
        // A person may take a moment to read the confirmation. GeckoView's
        // normal read timeout is intentionally removed only for this stream;
        // cancellation still closes it immediately.
        runCatching { response.setReadTimeoutMillis(0) }
        val appContext = context.applicationContext
        val target = createTarget(appContext, request.fileName, request.mimeType) ?: run {
            discard(response)
            dispatch { listener.onFailed(request, "Could not create a file in Downloads") }
            return false
        }
        val transfer = Transfer(request)
        transfers[request.id] = transfer
        dispatch { listener.onStarted(request) }

        executor.execute {
            try {
                val copied = copyResponse(body, target.output, transfer, listener)
                publishTarget(appContext, target)
                val completed = CompletedDownload(
                    request = request,
                    localUri = visibleUri(appContext, target),
                    bytes = copied
                )
                transfers.remove(request.id)
                dispatch { listener.onCompleted(completed) }
                Log.i(TAG, "saved ${request.fileName} ($copied bytes)")
            } catch (error: Throwable) {
                transfers.remove(request.id)
                discardTarget(appContext, target)
                Log.w(TAG, "download failed for ${request.sourceUrl}", error)
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                dispatch { listener.onFailed(request, "Download failed: $detail") }
            }
        }
        return true
    }

    private fun copyResponse(
        body: java.io.InputStream,
        output: OutputStream,
        transfer: Transfer,
        listener: Listener
    ): Long {
        var copied = 0L
        var lastProgressAt = 0L
        BufferedInputStream(body, BUFFER_SIZE).use { input ->
            output.use { destination ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                    copied += count
                    transfer.receivedBytes = copied
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                        lastProgressAt = now
                        val progressBytes = copied
                        dispatch {
                            listener.onProgress(transfer.request, progressBytes, transfer.request.expectedBytes)
                        }
                    }
                }
                destination.flush()
            }
        }
        if (copied != transfer.receivedBytes) transfer.receivedBytes = copied
        val finalBytes = copied
        dispatch { listener.onProgress(transfer.request, finalBytes, transfer.request.expectedBytes) }
        return copied
    }

    private fun createTarget(context: Context, name: String, mime: String): Target? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val output = context.contentResolver.openOutputStream(uri) ?: run {
                context.contentResolver.delete(uri, null, null)
                return null
            }
            Target(uri, output)
        } else {
            // App-external storage needs no broad all-files permission and stays
            // usable on Android 8/9. The completed file is exposed via FileProvider.
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads")
            if (!directory.exists() && !directory.mkdirs()) return null
            val file = uniqueFile(directory, name)
            Target(Uri.fromFile(file), FileOutputStream(file), file)
        }
    } catch (error: Throwable) {
        Log.w(TAG, "could not create download destination", error)
        null
    }

    /** Make Android 10+ MediaStore content visible only once it is complete. */
    private fun publishTarget(context: Context, target: Target) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        val changed = context.contentResolver.update(target.uri, values, null, null)
        if (changed != 1) throw IOException("Could not publish completed download")
    }

    private fun discardTarget(context: Context, target: Target) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(target.uri, null, null)
            } else {
                target.legacyFile?.delete()
            }
        }.onFailure { Log.w(TAG, "could not remove incomplete download", it) }
    }

    private fun visibleUri(context: Context, target: Target): Uri = target.legacyFile?.let { file ->
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } ?: target.uri

    private fun uniqueFile(directory: File, requestedName: String): File {
        val dot = requestedName.lastIndexOf('.')
        val stem = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        var candidate = File(directory, requestedName)
        var index = 1
        while (candidate.exists() && index < 10_000) {
            candidate = File(directory, "$stem ($index)$extension")
            index++
        }
        return candidate
    }

    private fun fileNameFrom(uri: String, headers: Map<String, String>): String {
        headers["Content-Disposition"]?.let { disposition ->
            val match = Regex("filename\\*?=(?:UTF-8'')?\\\"?([^\\\";]+)\\\"?", RegexOption.IGNORE_CASE)
                .find(disposition)
            if (match != null) {
                val encoded = match.groupValues[1]
                val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
                return sanitize(decoded)
            }
        }
        return sanitize(Uri.parse(uri).lastPathSegment ?: "download-${System.currentTimeMillis()}")
    }

    private fun sanitize(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("[\\\\/:*?\\\"<>|\\p{Cntrl}]"), "_")
            .take(180)
        return cleaned.ifEmpty { "download-${System.currentTimeMillis()}" }
    }

    private fun dispatch(action: () -> Unit) {
        main.post(action)
    }
}
