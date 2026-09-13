package com.minimal.browser

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.webkit.WebView

/** Opens Android's native print sheet for the current WebView page. */
object Printer {

    private const val TAG = "Printer"

    fun printCurrentPage(context: Context, webView: WebView?): Boolean {
        if (webView == null) return false
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
        val raw = TabManager.active?.displayTitle ?: "page"
        val title = raw.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "page" }
        return try {
            val adapter = webView.createPrintDocumentAdapter(title.take(48))
            manager.print(
                title.take(48),
                adapter,
                PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                    .build()
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "print setup failed: ${e.message}", e)
            false
        }
    }
}
