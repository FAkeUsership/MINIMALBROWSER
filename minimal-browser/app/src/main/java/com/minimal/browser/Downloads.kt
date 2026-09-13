package com.minimal.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.URLUtil

/**
 * Download hand-off for Android System WebView.
 *
 * DownloadManager owns the transfer and notification, so navigation never
 * blocks the UI thread and a failed download cannot take down a browser tab.
 */
object Downloads {

    private const val TAG = "Downloads"

    /** @return the visible file name when Android accepted the download. */
    fun start(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ): String? {
        if (url.isBlank()) return null
        return try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(name)
                setDescription("Downloading with Minimal Browser")
                setMimeType(mimeType ?: "application/octet-stream")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
            }
            val cookies = runCatching {
                android.webkit.CookieManager.getInstance().getCookie(url)
            }.getOrNull()
            if (!cookies.isNullOrBlank()) request.addRequestHeader("Cookie", cookies)
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)
                ?.enqueue(request)
                ?: return null
            Log.i(TAG, "queued $name ($contentLength bytes)")
            name
        } catch (e: Throwable) {
            Log.w(TAG, "download enqueue failed: ${e.message}", e)
            null
        }
    }
}
