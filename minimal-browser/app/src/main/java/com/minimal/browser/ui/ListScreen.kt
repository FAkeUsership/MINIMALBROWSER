package com.minimal.browser.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minimal.browser.DataStore
import com.minimal.browser.Downloads
import com.minimal.browser.Fmt

/**
 * One reusable list screen used for History, Bookmarks and Downloads.
 * Same visual language as the home screen's "Continue reading" rows.
 */
class ListScreen(context: Context) : LinearLayout(context) {

    data class Item(
        val title: String,
        val subtitle: String,
        val trailing: String? = null,
        val url: String? = null,
        val payload: Any? = null
    )

    var onOpenUrl: ((String) -> Unit)? = null
    var onOpenDownload: ((String, String) -> Unit)? = null
    var onDeleteBookmark: ((String) -> Unit)? = null
    var bookmarksMode = false
    private var downloadsMode = false

    private val heading: TextView
    private val subheading: TextView
    private val list: RecyclerView
    private val empty: TextView
    private val adapter = Adapter()
    private var items: List<Item> = emptyList()
    private var loadGeneration = 0L

    init {
        orientation = VERTICAL
        setBackgroundColor(Ink.SHELL)
        setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(16))

        heading = TextView(context).apply {
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
        }
        addView(heading)

        subheading = TextView(context).apply {
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        addView(subheading, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(6)
            bottomMargin = context.dp(14)
        })

        empty = TextView(context).apply {
            setTextColor(Ink.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            visibility = GONE
            setPadding(context.dp(4), context.dp(10), context.dp(4), context.dp(10))
        }
        addView(empty)

        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ListScreen.adapter
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
    }

    fun showHistory() {
        bookmarksMode = false
        downloadsMode = false
        heading.text = "History"
        val generation = ++loadGeneration
        Thread {
            val rows = runCatching {
                DataStore.get(context).history(300).map {
                    Item(
                        title = it.title.ifEmpty { it.url },
                        subtitle = it.url,
                        trailing = "${it.visits}× · ${Fmt.when_ago(it.lastVisit)}",
                        url = it.url
                    )
                }
            }.getOrElse { emptyList() }
            post {
                if (generation == loadGeneration) {
                    submit("History", "${rows.size} pages · most recent first", rows)
                }
            }
        }.start()
    }

    fun showBookmarks() {
        bookmarksMode = true
        downloadsMode = false
        heading.text = "Bookmarks"
        val generation = ++loadGeneration
        Thread {
            val rows = runCatching {
                DataStore.get(context).bookmarks().map {
                    Item(
                        title = it.title.ifEmpty { it.url },
                        subtitle = it.url,
                        trailing = "✕",
                        url = it.url
                    )
                }
            }.getOrElse { emptyList() }
            post {
                if (generation == loadGeneration) {
                    submit("Bookmarks", "${rows.size} saved pages", rows)
                }
            }
        }.start()
    }

    fun showDownloads() {
        bookmarksMode = false
        downloadsMode = true
        heading.text = "Downloads"
        val generation = ++loadGeneration
        val active = Downloads.active().map {
            val progress = if (it.expectedBytes >= 0L) {
                "${Fmt.bytes(it.receivedBytes)} / ${Fmt.bytes(it.expectedBytes)}"
            } else {
                Fmt.bytes(it.receivedBytes)
            }
            Item(
                title = it.fileName,
                subtitle = it.sourceUrl,
                trailing = "$progress · downloading"
            )
        }
        Thread {
            val completed = runCatching {
                DataStore.get(context).downloads().map {
                    Item(
                        title = it.fileName,
                        subtitle = it.url,
                        trailing = if (it.localUri.isBlank()) {
                            "Unverified older entry"
                        } else {
                            "${Fmt.bytes(it.bytes)} · ${Fmt.when_ago(it.createdAt)}"
                        },
                        url = it.localUri.takeIf { value -> value.isNotBlank() },
                        payload = it.mime
                    )
                }
            }.getOrElse { emptyList() }
            val rows = active + completed
            post {
                if (downloadsMode && generation == loadGeneration) {
                    submit("Downloads", "${rows.size} files · saved to your Downloads folder", rows)
                }
            }
        }.start()
    }

    private fun submit(title: String, subtitle: String, rows: List<Item>) {
        heading.text = title
        subheading.text = subtitle
        items = rows
        empty.visibility = if (rows.isEmpty()) VISIBLE else GONE
        empty.text = when (title) {
            "Bookmarks" -> "Nothing bookmarked yet. Use the menu → Bookmark this page."
            "Downloads" -> "No downloads yet."
            else -> "No history yet — private tabs are never recorded."
        }
        adapter.notifyDataSetChanged()
    }

    /* ------------------------------------------------------------------ */

    private inner class Adapter : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = context.getDrawable(com.minimal.browser.R.drawable.bg_row_hover)
                setPadding(context.dp(14), context.dp(11), context.dp(14), context.dp(11))
                isClickable = true
                isFocusable = true
            }
            val texts = LinearLayout(context).apply { orientation = VERTICAL }
            val title = TextView(context).apply {
                setTextColor(Ink.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTypeface(typeface, Typeface.BOLD)
                isSingleLine = true
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                tag = "t"
            }
            val sub = TextView(context).apply {
                setTextColor(Ink.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                isSingleLine = true
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                tag = "s"
            }
            texts.addView(title)
            texts.addView(sub)
            row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 1f })

            row.addView(TextView(context).apply {
                setTextColor(Ink.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                gravity = Gravity.END
                tag = "r"
            })
            val holder = FrameLayout(context)
            holder.addView(row, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            holder.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(4)
            }
            return RowHolder(holder)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val item = items[position]
            val row = (holder.itemView as FrameLayout).getChildAt(0)
            row.findViewWithTag<TextView>("t").text = item.title
            row.findViewWithTag<TextView>("s").text = item.subtitle
            row.findViewWithTag<TextView>("r").text = item.trailing ?: ""
            row.setOnClickListener {
                if (bookmarksMode && item.url != null) {
                    Thread {
                        runCatching { DataStore.get(context).removeBookmark(item.url) }
                        post {
                            onDeleteBookmark?.invoke(item.url)
                            showBookmarks()
                        }
                    }.start()
                } else if (downloadsMode && item.url != null) {
                    onOpenDownload?.invoke(item.url, item.payload as? String ?: "application/octet-stream")
                } else if (item.url != null) {
                    onOpenUrl?.invoke(item.url)
                }
            }
        }
    }

    class RowHolder(v: View) : RecyclerView.ViewHolder(v)
}
