package com.minimal.browser.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minimal.browser.MediaScanner
import com.minimal.browser.MediaScanner.Kind
import com.minimal.browser.Prefs
import com.minimal.browser.R
import java.util.Locale

/**
 * The media downloader sheet: scans the current page, lists every image and
 * video that passes the minimum-size settings, and downloads only what the
 * person ticks. Images are listed largest first, and each video shows its
 * resolution label when the site provides one.
 */
class MediaDownloadDialog(
    private val context: Context,
    private val pageUrl: String,
    private val onDownload: (List<MediaScanner.Item>) -> Unit
) {

    private enum class Filter(val label: String) {
        ALL("All"),
        IMAGES("Images"),
        VIDEOS("Videos")
    }

    private val main = Handler(Looper.getMainLooper())
    private val all = ArrayList<MediaScanner.Item>()
    private val selected = HashSet<String>()
    private val tabViews = LinkedHashMap<Filter, TextView>()
    private var visible: List<MediaScanner.Item> = emptyList()
    private var filter = Filter.ALL
    private var alive = true
    private var dialog: AlertDialog? = null

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: RecyclerView
    private lateinit var listAdapter: Adapter

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(8), context.dp(16), 0)
        }

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progress = ProgressBar(context)
        statusRow.addView(progress, LinearLayout.LayoutParams(context.dp(22), context.dp(22)).apply {
            rightMargin = context.dp(10)
        })
        status = TextView(context).apply {
            text = "Scanning this page…"
            setTextColor(TEXT)
            textSize = 13f
        }
        statusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(statusRow)

        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(10), 0, 0)
        }
        Filter.values().forEach { option ->
            val tab = TextView(context).apply {
                text = option.label
                textSize = 13f
                setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
                background = context.roundRect(99, 0xFFEFEFEF.toInt())
                setOnClickListener { setFilter(option) }
            }
            tabViews[option] = tab
            tabs.addView(tab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = context.dp(8) })
        }
        root.addView(tabs)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, context.dp(6), 0, 0)
        }
        actions.addView(actionText("Select all") { selectAllVisible() })
        actions.addView(actionText("Clear") {
            selected.clear()
            listAdapter.notifyDataSetChanged()
            updateButton()
        })
        root.addView(actions)

        listAdapter = Adapter()
        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(300)).apply {
            topMargin = context.dp(6)
        })

        val built = AlertDialog.Builder(context, R.style.Theme_Minimal_Dialog)
            .setTitle("Download media")
            .setView(root)
            .setPositiveButton("Download selected", null)
            .setNegativeButton("Close", null)
            .create()
        built.setOnDismissListener { alive = false }
        built.show()
        // Handle the positive button ourselves so an empty selection keeps the sheet open.
        built.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { downloadSelected() }
        dialog = built
        updateTabs()
        scan()
    }

    /* ------------------------------------------------------------------ */
    /*  scanning                                                           */
    /* ------------------------------------------------------------------ */

    private fun scan() {
        val minImage = Prefs.mediaMinImagePx
        val minVideo = Prefs.mediaMinVideoMb
        Thread(Runnable {
            val result = runCatching { MediaScanner.scan(pageUrl, minImage, minVideo) }
                .getOrElse { error ->
                    MediaScanner.Result(emptyList(), 0, 0, 0, error.message ?: "The scan failed")
                }
            main.post { if (alive) finishScan(result) }
        }, "media-scan").start()
    }

    private fun finishScan(result: MediaScanner.Result) {
        progress.visibility = android.view.View.GONE
        if (result.error != null) {
            status.text = result.error
            return
        }
        all.clear()
        all.addAll(result.items)
        selected.clear()
        applyFilter()

        val images = all.count { it.kind == Kind.IMAGE }
        val videos = all.size - images
        if (all.isEmpty()) {
            status.text = "No downloadable images or videos were found. Some sites load media with JavaScript after the page opens, and this scanner cannot see that media."
            return
        }
        val parts = ArrayList<String>()
        parts += "$images image(s), $videos video(s)"
        if (result.hiddenBySize > 0) parts += "${result.hiddenBySize} hidden by your minimum size"
        if (result.streamsSkipped > 0) parts += "${result.streamsSkipped} stream(s) skipped (HLS/DASH)"
        status.text = parts.joinToString(" · ")
    }

    /* ------------------------------------------------------------------ */
    /*  selection                                                          */
    /* ------------------------------------------------------------------ */

    private fun setFilter(option: Filter) {
        filter = option
        applyFilter()
    }

    private fun applyFilter() {
        visible = when (filter) {
            Filter.ALL -> all.toList()
            Filter.IMAGES -> all.filter { it.kind == Kind.IMAGE }
            Filter.VIDEOS -> all.filter { it.kind == Kind.VIDEO }
        }
        updateTabs()
        listAdapter.notifyDataSetChanged()
        updateButton()
    }

    private fun selectAllVisible() {
        visible.forEach { selected.add(it.url) }
        listAdapter.notifyDataSetChanged()
        updateButton()
    }

    private fun updateTabs() {
        tabViews.forEach { (option, view) ->
            val active = option == filter
            view.setTextColor(if (active) 0xFF111111.toInt() else 0xFF8A8A8A.toInt())
            view.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun updateButton() {
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text =
            if (selected.isEmpty()) "Download selected" else "Download ${selected.size}"
    }

    private fun downloadSelected() {
        val chosen = all.filter { it.url in selected }
        if (chosen.isEmpty()) {
            status.text = "Tick at least one item to download."
            return
        }
        dialog?.dismiss()
        onDownload(chosen)
    }

    /* ------------------------------------------------------------------ */
    /*  list rows                                                          */
    /* ------------------------------------------------------------------ */

    private fun actionText(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(0xFF111111.toInt())
        setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
        setOnClickListener { onClick() }
    }

    private fun describe(item: MediaScanner.Item): String {
        val size = if (item.bytes >= 0) formatBytes(item.bytes) else "size unknown"
        return when (item.kind) {
            Kind.IMAGE -> "Image · ${item.width}x${item.height} · $size"
            Kind.VIDEO -> "Video · ${item.quality.ifBlank { "quality not labeled" }} · $size"
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        class Holder(
            val row: LinearLayout,
            val thumb: ImageView,
            val title: TextView,
            val detail: TextView,
            val check: CheckBox
        ) : RecyclerView.ViewHolder(row)

        override fun getItemCount(): Int = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val check = CheckBox(ctx)
            val thumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFEFEFEF.toInt())
            }
            val title = TextView(ctx).apply {
                setTextColor(TEXT)
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val detail = TextView(ctx).apply {
                setTextColor(0xFF6A6A6A.toInt())
                textSize = 11.5f
                maxLines = 1
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(detail)
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(4), ctx.dp(6))
                addView(check)
                addView(thumb, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)).apply {
                    leftMargin = ctx.dp(6)
                })
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = ctx.dp(10)
                })
            }
            row.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return Holder(row, thumb, title, detail, check)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = visible[position]
            holder.title.text = item.fileName
            holder.detail.text = describe(item)

            val preview = item.thumbnail
            if (preview != null) {
                holder.thumb.imageTintList = null
                holder.thumb.setImageBitmap(BitmapFactory.decodeByteArray(preview, 0, preview.size))
            } else {
                holder.thumb.icon(R.drawable.ic_file, 0xFF7A7A7A.toInt())
            }

            // Clear the listener first, otherwise rebinding would fire it and change the selection.
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = item.url in selected
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(item.url) else selected.remove(item.url)
                updateButton()
            }
            holder.row.setOnClickListener { holder.check.isChecked = !holder.check.isChecked }
        }
    }

    private companion object {
        val TEXT: Int = 0xFF1A1A1A.toInt()
    }
}
