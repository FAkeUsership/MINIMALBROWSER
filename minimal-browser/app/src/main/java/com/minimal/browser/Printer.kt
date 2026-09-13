package com.minimal.browser

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
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

/** Renders the active bundled-engine page to PDF and opens Android's print sheet. */
object Printer {

    private const val TAG = "Printer"
    private val main = Handler(Looper.getMainLooper())

    fun printCurrentPage(context: Context, session: GeckoSession?): Boolean {
        if (session == null) return false
        return try {
            // saveAsPdf renders ordinary HTML pages. SessionPdfFileSaver.save()
            // is for an already-loaded PDF and cannot print general web content.
            session.saveAsPdf().accept(
                { stream ->
                    if (stream != null) printStream(context, stream, suggestedName())
                    else Log.w(TAG, "bundled engine returned no PDF stream")
                },
                { error -> Log.w(TAG, "PDF render failed", error) }
            )
            true
        } catch (error: Throwable) {
            Log.w(TAG, "could not start PDF rendering", error)
            false
        }
    }

    private fun suggestedName(): String {
        val raw = TabManager.active?.displayTitle ?: "page"
        return raw.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "page" }.take(48)
    }

    /** Copies Gecko's PDF stream off-thread, then invokes PrintManager on the UI thread. */
    private fun printStream(context: Context, stream: InputStream, title: String) {
        Thread({
            val file = try {
                File.createTempFile("minimal-print-", ".pdf", context.cacheDir)
            } catch (error: Throwable) {
                runCatching { stream.close() }
                Log.w(TAG, "could not create temporary PDF", error)
                return@Thread
            }
            try {
                stream.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output, 64 * 1024) }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "could not copy PDF", error)
                file.delete()
                return@Thread
            }
            main.post {
                try {
                    val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                    if (manager == null) {
                        file.delete()
                    } else {
                        val attributes = PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                            .setResolution(
                                PrintAttributes.Resolution("minimal", "Minimal Browser", 300, 300)
                            )
                            .build()
                        manager.print(title, PdfAdapter(file, title), attributes)
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "could not open print sheet", error)
                    file.delete()
                }
            }
        }, "minimal-browser-print").start()
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
            Thread({
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return@Thread
                }
                try {
                    FileInputStream(file).use { input ->
                        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                            input.copyTo(output, 64 * 1024)
                        }
                    }
                    if (cancellationSignal?.isCanceled == true) callback.onWriteCancelled()
                    else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (error: Throwable) {
                    Log.w(TAG, "could not write PDF to print service", error)
                    callback.onWriteFailed(error.message)
                }
            }, "minimal-browser-print-write").start()
        }

        override fun onFinish() {
            runCatching { file.delete() }
        }
    }
}
