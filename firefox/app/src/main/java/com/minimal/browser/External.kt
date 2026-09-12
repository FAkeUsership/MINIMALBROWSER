package com.minimal.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/** Intent helpers: share, external schemes, and the "open in another app" path. */
object External {

    private const val TAG = "External"

    fun share(context: Context, url: String?, title: String?) {
        if (url.isNullOrEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title ?: url)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        try {
            context.startActivity(Intent.createChooser(send, "Share"))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity to share with")
        }
    }

    /** @return true when some other app took the URI. */
    fun openUri(context: Context, uri: String): Boolean {
        if (uri.isBlank()) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "nothing handles $uri (${e.message})")
            false
        }
    }
}
