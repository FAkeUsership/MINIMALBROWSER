package com.minimal.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.URLUtil

/** Downloads go straight through the system DownloadManager. */
object Downloads {

    private const val TAG = "Downloads"

    /** @return the file name, or null when the download could not be queued. */
    fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ): String? {
        if (url.isBlank()) return null
        val name = try {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (e: Exception) {
            "download-${System.currentTimeMillis()}"
        }
        return try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let {
                    addRequestHeader("Cookie", it)
                }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                setTitle(name)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                allowScanningByMediaScanner()
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return null
            dm.enqueue(request)
            name
        } catch (e: Exception) {
            Log.w(TAG, "enqueue failed: ${e.message}")
            null
        }
    }
}
