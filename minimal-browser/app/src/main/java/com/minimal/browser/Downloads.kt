package com.minimal.browser

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.mozilla.geckoview.WebResponse
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Download handling for bundled GeckoView responses.
 *
 * Gecko gives the browser an InputStream only for a response it declines to
 * render. The stream is copied off the UI thread into Android's public
 * Downloads collection on Android 10+ (or app external storage on older
 * releases) so a failed transfer cannot crash a tab or Activity.
 */
object Downloads {

    private const val TAG = "Downloads"

    /** @return the visible filename once a destination was reserved. */
    fun start(context: Context, response: WebResponse): String? {
        val sourceUrl = response.uri ?: return null
        val mime = response.headers["Content-Type"]
            ?.substringBefore(';')
            ?.trim()
            ?.ifBlank { null }
            ?: "application/octet-stream"
        val name = fileNameFrom(sourceUrl, response.headers)
        val body = response.body ?: return null
        val target = createTarget(context, name, mime) ?: run {
            // WebResponse explicitly requires callers to close ignored streams.
            runCatching { body.close() }
            return null
        }

        Thread({
            try {
                target.output.use { output ->
                    BufferedInputStream(body, 64 * 1024).use { input ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) markComplete(context, target.uri)
                Log.i(TAG, "saved $name")
            } catch (error: Throwable) {
                Log.w(TAG, "download failed for $sourceUrl", error)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) discardIncomplete(context, target.uri)
                else target.file?.delete()
            }
        }, "minimal-browser-download").start()

        return name
    }

    private data class Target(
        val uri: android.net.Uri,
        val output: OutputStream,
        val file: File? = null
    )

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
            // Pre-scoped-storage Android needs a dangerous storage permission for
            // the public collection. Keep downloads in this app's external files
            // directory instead of requesting a browser-wide storage permission.
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads")
            if (!directory.exists() && !directory.mkdirs()) return null
            val file = uniqueFile(directory, name)
            Target(android.net.Uri.fromFile(file), FileOutputStream(file), file)
        }
    } catch (error: Throwable) {
        Log.w(TAG, "could not create download destination", error)
        null
    }

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

    private fun markComplete(context: Context, uri: android.net.Uri) {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, values, null, null) }
            .onFailure { Log.w(TAG, "could not finalize download", it) }
    }

    private fun discardIncomplete(context: Context, uri: android.net.Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { Log.w(TAG, "could not remove incomplete download", it) }
    }

    private fun fileNameFrom(uri: String, headers: Map<String, String>): String {
        headers["Content-Disposition"]?.let { disposition ->
            val match = Regex("filename\\*?=(?:UTF-8'')?\\\"?([^\\\";]+)\\\"?", RegexOption.IGNORE_CASE)
                .find(disposition)
            if (match != null) return sanitize(match.groupValues[1])
        }
        return sanitize(android.net.Uri.parse(uri).lastPathSegment ?: "download-${System.currentTimeMillis()}")
    }

    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\\\"<>|]"), "_")
        return cleaned.ifEmpty { "download-${System.currentTimeMillis()}" }
    }
}
