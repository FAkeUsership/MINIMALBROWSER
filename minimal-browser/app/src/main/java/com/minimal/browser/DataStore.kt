package com.minimal.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/* ------------------------------------------------------------------ */
/*  Models                                                             */
/* ------------------------------------------------------------------ */

data class HistoryEntry(val id: Long, val url: String, val title: String, val visits: Int, val lastVisit: Long)
data class Bookmark(val id: Long, val url: String, val title: String, val addedAt: Long)
data class DownloadRow(
    val id: Long,
    val fileName: String,
    val url: String,
    val mime: String,
    val bytes: Long,
    val localUri: String,
    val createdAt: Long
)
data class BlockedRow(val id: Long, val ts: Long, val host: String, val kind: String)
data class TabRow(val id: String, val url: String, val title: String, val private: Boolean, val state: String?)

/* ------------------------------------------------------------------ */
/*  SQLite store — history, bookmarks, downloads, blocked counters,    */
/*  and tab/session restore.                                           */
/* ------------------------------------------------------------------ */

class DataStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "minimal_browser.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE history (
                   _id INTEGER PRIMARY KEY AUTOINCREMENT,
                   url TEXT NOT NULL UNIQUE,
                   title TEXT NOT NULL DEFAULT '',
                   visits INTEGER NOT NULL DEFAULT 1,
                   last_visit INTEGER NOT NULL
               )"""
        )
        db.execSQL("CREATE INDEX idx_history_last ON history(last_visit DESC)")
        db.execSQL(
            """CREATE TABLE bookmarks (
                   _id INTEGER PRIMARY KEY AUTOINCREMENT,
                   url TEXT NOT NULL UNIQUE,
                   title TEXT NOT NULL DEFAULT '',
                   added_at INTEGER NOT NULL
               )"""
        )
        db.execSQL(
            """CREATE TABLE downloads (
                   _id INTEGER PRIMARY KEY AUTOINCREMENT,
                   file_name TEXT NOT NULL,
                   url TEXT NOT NULL,
                   mime TEXT NOT NULL DEFAULT '',
                   bytes INTEGER NOT NULL DEFAULT 0,
                   local_uri TEXT NOT NULL DEFAULT '',
                   created_at INTEGER NOT NULL
               )"""
        )
        db.execSQL(
            """CREATE TABLE blocked (
                   _id INTEGER PRIMARY KEY AUTOINCREMENT,
                   ts INTEGER NOT NULL,
                   host TEXT NOT NULL DEFAULT '',
                   kind TEXT NOT NULL,
                   count INTEGER NOT NULL DEFAULT 1
               )"""
        )
        db.execSQL("CREATE INDEX idx_blocked_ts ON blocked(ts)")
        db.execSQL(
            """CREATE TABLE tabs (
                   id TEXT PRIMARY KEY,
                   url TEXT NOT NULL DEFAULT '',
                   title TEXT NOT NULL DEFAULT '',
                   private INTEGER NOT NULL DEFAULT 0,
                   state TEXT
               )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Existing v1 rows each represented one request, so their default
            // count preserves the historical daily total exactly.
            db.execSQL("ALTER TABLE blocked ADD COLUMN count INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 3) {
            // v1.2.5 records the actual Android content URI only after a file
            // has been fully written, so the Downloads list can open a real
            // completed file instead of behaving like a dead history entry.
            db.execSQL("ALTER TABLE downloads ADD COLUMN local_uri TEXT NOT NULL DEFAULT ''")
        }
    }

    /* ---------------- history ---------------- */

    fun recordVisit(url: String, title: String) {
        if (url.isEmpty() || url.startsWith("about:")) return
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("title", title.ifEmpty { url })
            put("last_visit", now)
        }
        val updated = writableDatabase.update("history", cv, "url = ?", arrayOf(url))
        if (updated == 0) {
            cv.put("url", url)
            cv.put("visits", 1)
            writableDatabase.insertWithOnConflict("history", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            writableDatabase.execSQL("UPDATE history SET visits = visits + 1 WHERE url = ?", arrayOf(url))
        }
    }

    fun retitle(url: String, title: String) {
        if (url.isEmpty() || title.isEmpty()) return
        val cv = ContentValues().apply { put("title", title) }
        writableDatabase.update("history", cv, "url = ?", arrayOf(url))
    }

    fun history(limit: Int = 200): List<HistoryEntry> = readableDatabase.query(
        "history", null, null, null, null, null, "last_visit DESC", limit.toString()
    ).use { c ->
        val out = ArrayList<HistoryEntry>(c.count)
        while (c.moveToNext()) {
            out += HistoryEntry(
                c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4)
            )
        }
        out
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    /* ---------------- bookmarks ---------------- */

    fun addBookmark(url: String, title: String) {
        val cv = ContentValues().apply {
            put("url", url)
            put("title", title.ifEmpty { url })
            put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("bookmarks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
    }

    fun isBookmarked(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        readableDatabase.query("bookmarks", arrayOf("_id"), "url = ?", arrayOf(url), null, null, null)
            .use { return it.count > 0 }
    }

    fun bookmarks(): List<Bookmark> = readableDatabase.query(
        "bookmarks", null, null, null, null, null, "added_at DESC"
    ).use { c ->
        val out = ArrayList<Bookmark>(c.count)
        while (c.moveToNext()) out += Bookmark(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))
        out
    }

    /* ---------------- downloads ---------------- */

    fun addDownload(fileName: String, url: String, mime: String, bytes: Long, localUri: String) {
        val cv = ContentValues().apply {
            put("file_name", fileName)
            put("url", url)
            put("mime", mime)
            put("bytes", bytes)
            put("local_uri", localUri)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("downloads", null, cv)
    }

    fun downloads(): List<DownloadRow> = readableDatabase.query(
        "downloads",
        arrayOf("_id", "file_name", "url", "mime", "bytes", "local_uri", "created_at"),
        null, null, null, null, "created_at DESC"
    ).use { c ->
        val out = ArrayList<DownloadRow>(c.count)
        while (c.moveToNext()) {
            out += DownloadRow(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                c.getLong(4), c.getString(5), c.getLong(6)
            )
        }
        out
    }

    /* ---------------- blocked counters ---------------- */

    /**
     * Persists one compact row per host/kind batch rather than one transaction
     * for every blocked subresource. `count` keeps the Home total exact while
     * avoiding tracker-heavy pages creating a large SQLite write queue.
     */
    fun recordBlockedBatch(counts: Map<Pair<String, String>, Int>) {
        if (counts.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for ((key, count) in counts) {
                if (count <= 0) continue
                val (host, kind) = key
                val cv = ContentValues().apply {
                    put("ts", now)
                    put("host", host)
                    put("kind", kind)
                    put("count", count)
                }
                db.insert("blocked", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun blockedSince(ts: Long): Int = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(count), 0) FROM blocked WHERE ts >= ?", arrayOf(ts.toString())
    ).use {
        if (it.moveToFirst()) it.getLong(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
    }

    fun clearBlocked() {
        writableDatabase.delete("blocked", null, null)
    }

    /* ---------------- tab / session restore ---------------- */

    fun saveTabs(rows: List<TabRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("tabs", null, null)
            for (r in rows) {
                val cv = ContentValues().apply {
                    put("id", r.id)
                    put("url", r.url)
                    put("title", r.title)
                    put("private", if (r.private) 1 else 0)
                    put("state", r.state)
                }
                db.insert("tabs", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadTabs(): List<TabRow> = readableDatabase.query(
        "tabs", null, null, null, null, null, null
    ).use { c ->
        val out = ArrayList<TabRow>(c.count)
        while (c.moveToNext()) {
            out += TabRow(
                c.getString(0), c.getString(1), c.getString(2),
                c.getInt(3) == 1, c.getString(4)
            )
        }
        out
    }

    /* ---------------- everything ---------------- */

    fun clearAll() {
        writableDatabase.apply {
            delete("history", null, null)
            delete("bookmarks", null, null)
            delete("downloads", null, null)
            delete("blocked", null, null)
        }
    }

    companion object {
        @Volatile private var instance: DataStore? = null
        fun get(context: Context): DataStore =
            instance ?: synchronized(this) {
                instance ?: DataStore(context).also { instance = it }
            }
    }
}
