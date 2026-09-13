package com.minimal.browser

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Sync & backup" — a plain JSON export/import of bookmarks and history, so the
 * data is portable without any account or server.
 */
object Backup {

    private const val TAG = "Backup"

    fun export(context: Context, uri: Uri, notify: (String) -> Unit) {
        Thread {
            try {
                val db = DataStore.get(context)
                val root = JSONObject()
                root.put("app", "Minimal Browser")
                root.put("version", 1)
                root.put("exportedAt", System.currentTimeMillis())

                val bookmarks = JSONArray()
                db.bookmarks().forEach {
                    bookmarks.put(JSONObject().apply {
                        put("url", it.url)
                        put("title", it.title)
                        put("addedAt", it.addedAt)
                    })
                }
                root.put("bookmarks", bookmarks)

                val history = JSONArray()
                db.history(500).forEach {
                    history.put(JSONObject().apply {
                        put("url", it.url)
                        put("title", it.title)
                        put("visits", it.visits)
                        put("lastVisit", it.lastVisit)
                    })
                }
                root.put("history", history)

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(root.toString(2).toByteArray())
                }
                notifyOnMain(notify, "Exported ${bookmarks.length()} bookmarks, ${history.length()} history entries")
            } catch (e: Exception) {
                Log.w(TAG, "export failed: ${e.message}")
                notifyOnMain(notify, "Export failed")
            }
        }.start()
    }

    fun importFrom(context: Context, uri: Uri, notify: (String) -> Unit) {
        Thread {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (text.isNullOrEmpty()) {
                    notifyOnMain(notify, "Nothing to import")
                    return@Thread
                }
                val root = JSONObject(text)
                val db = DataStore.get(context)
                var bookmarks = 0
                var history = 0

                root.optJSONArray("bookmarks")?.let { array ->
                    for (i in 0 until array.length()) {
                        val o = array.optJSONObject(i) ?: continue
                        val url = o.optString("url")
                        if (url.isNotEmpty()) {
                            db.addBookmark(url, o.optString("title", url))
                            bookmarks++
                        }
                    }
                }
                root.optJSONArray("history")?.let { array ->
                    for (i in 0 until array.length()) {
                        val o = array.optJSONObject(i) ?: continue
                        val url = o.optString("url")
                        if (url.isNotEmpty()) {
                            db.recordVisit(url, o.optString("title", url))
                            history++
                        }
                    }
                }
                notifyOnMain(notify, "Imported $bookmarks bookmarks, $history history entries")
            } catch (e: Exception) {
                Log.w(TAG, "import failed: ${e.message}")
                notifyOnMain(notify, "Import failed — is that a Minimal backup?")
            }
        }.start()
    }

    /** Backup work runs off the UI thread; view callbacks must not. */
    private fun notifyOnMain(notify: (String) -> Unit, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { notify(message) }
    }
}
