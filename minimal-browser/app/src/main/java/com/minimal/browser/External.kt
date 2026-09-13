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

    /**
     * Opens a non-web URI in a matching Android app. `intent:` links need
     * Intent.parseUri rather than ACTION_VIEW alone; if their target is absent,
     * Android's documented browser_fallback_url is opened instead.
     *
     * @return true when an activity or a safe fallback took the URI.
     */
    fun openUri(context: Context, rawUri: String): Boolean {
        if (rawUri.isBlank()) return false
        return try {
            val intent = if (rawUri.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(rawUri, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(rawUri))
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (fallback.isNullOrBlank() || !fallback.startsWith("http", ignoreCase = true)) {
                    false
                } else {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "nothing handles $rawUri (${e.message})")
            false
        }
    }
}
