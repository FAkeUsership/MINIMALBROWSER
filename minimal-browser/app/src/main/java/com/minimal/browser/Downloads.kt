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

/**
 * Real downloads. Gecko hands us the response stream from
 * `ContentDelegate.onExternalResponse`; we write it into the public
 * Downloads collection.
 */
object Downloads {

    private const val TAG = "Downloads"

    /** @return the file name we saved to, or null if the download was skipped. */
    fun start(context: Context, response: WebResponse): String? {
        val uri = response.uri ?: return null
        val mime = response.headers["Content-Type"]?.substringBefore(';')?.trim() ?: "application/octet-stream"
        val name = fileNameFrom(uri, response.headers)

        val body = response.body ?: return null

        val target = createTarget(context, name, mime) ?: return null
        Thread {
            try {
                target.outputStream.use { out ->
                    BufferedInputStream(body, 64 * 1024).use { input ->
                        input.copyTo(out, 64 * 1024)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    markComplete(context, target.uri)
                }
                Log.i(TAG, "saved $name")
            } catch (e: Exception) {
                Log.w(TAG, "download failed: ${e.message}")
            }
        }.start()

        return name
    }

    private class Target(val uri: android.net.Uri, val outputStream: java.io.OutputStream)

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
            val out = context.contentResolver.openOutputStream(uri) ?: return null
            Target(uri, out)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            Target(android.net.Uri.fromFile(f), FileOutputStream(f))
        }
    } catch (e: Exception) {
        Log.w(TAG, "cannot create download target: ${e.message}")
        null
    }

    private fun markComplete(context: Context, uri: android.net.Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        try {
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "mark complete failed: ${e.message}")
        }
    }

    private fun fileNameFrom(uri: String, headers: Map<String, String>): String {
        headers["Content-Disposition"]?.let { cd ->
            val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)
            if (m != null) return sanitize(m.groupValues[1])
        }
        val guess = android.net.Uri.parse(uri).lastPathSegment
        return sanitize(guess ?: "download-${System.currentTimeMillis()}")
    }

    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifEmpty { "download-${System.currentTimeMillis()}" }
    }
}
