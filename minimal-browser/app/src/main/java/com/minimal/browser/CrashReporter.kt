package com.minimal.browser

import android.content.Context
import android.util.Log
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records uncaught exceptions to `filesDir/crash.txt` and shows the first line as a
 * toast, so a crash on a device with no cable attached is still readable.
 *
 * The full trace remains in the app file and can be retrieved with
 * `adb pull /data/data/com.minimal.browser/files/crash.txt` when debugging.
 */
object CrashReporter {

    private const val TAG = "MinimalCrash"
    private const val FILE = "crash.txt"
    private const val MAX_RECORDS = 5
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(thread, error)
            } catch (_: Throwable) {
                /* never let reporting turn a crash into a hang */
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val trace = Log.getStackTraceString(error)
        Log.e(TAG, "uncaught on ${thread.name}", error)

        val ctx = appContext ?: return
        val file = File(ctx.filesDir, FILE)
        val header = "=== $stamp  ${error.javaClass.name}: ${error.message} ==="
        val body = file.takeIf { it.exists() }?.readText().orEmpty()
        val merged = (listOf("$header\n$trace") + body.split("\n=== ").take(MAX_RECORDS - 1))
            .joinToString("\n=== ").removePrefix("=== ").let { "=== $it" }
        runCatching { file.writeText(merged.take(64 * 1024)) }

        // Surface it: a silent crash on a phone with no adb is useless to debug.
        val short = "${error.javaClass.simpleName}: ${error.message ?: "no message"}"
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(ctx, "Crash captured — see crash.txt\n$short", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Most recent recorded trace, or null. Used by the Settings screen. */
    fun readLatest(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
