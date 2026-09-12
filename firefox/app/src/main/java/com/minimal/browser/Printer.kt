package com.minimal.browser

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * "Print…" from the menu.
 *
 * Gecko renders the page to a PDF (`GeckoSession.getPdfFileSaver().save()`), we
 * hand those bytes to the Android print framework — so you get the normal
 * system print dialog, including "Save as PDF".
 */
object Printer {

    private const val TAG = "Printer"

    fun printCurrentPage(context: Context, session: GeckoSession?): Boolean {
        if (session == null) return false
        session.pdfFileSaver.save().accept(
            { response ->
                val body = response?.body
                if (body == null) {
                    Log.w(TAG, "engine returned no PDF")
                    return@accept
                }
                printStream(context, body, suggestedName(session))
            },
            { e -> Log.w(TAG, "pdf render failed: ${e?.message}") }
        )
        return true
    }

    private fun suggestedName(session: GeckoSession): String {
        val t = TabManager.active?.displayTitle ?: "page"
        val safe = t.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "page" }
        return if (safe.length > 48) safe.substring(0, 48) else safe
    }

    /** Writes the stream to a temp file, then opens the system print dialog. */
    fun printStream(context: Context, stream: InputStream, title: String) {
        Thread {
            val file = try {
                File.createTempFile("minimal-print-", ".pdf", context.cacheDir)
            } catch (e: Exception) {
                Log.w(TAG, "temp file failed: ${e.message}")
                return@Thread
            }
            try {
                FileOutputStream(file).use { out -> stream.copyTo(out, 64 * 1024) }
            } catch (e: Exception) {
                Log.w(TAG, "pdf copy failed: ${e.message}")
                return@Thread
            }
            (context.getSystemService(Context.PRINT_SERVICE) as? PrintManager)?.let { pm ->
                val attrs = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                    .setResolution(PrintAttributes.Resolution("minimal", "Minimal", 300, 300))
                    .build()
                pm.print(title, PdfAdapter(file, title), attrs)
            }
        }.start()
    }

    private class PdfAdapter(private val file: File, private val jobName: String) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(jobName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            } finally {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }
    }
}
