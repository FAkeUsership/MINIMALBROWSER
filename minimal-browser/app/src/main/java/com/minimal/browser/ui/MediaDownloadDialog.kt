package com.minimal.browser.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minimal.browser.Downloads
import com.minimal.browser.MediaScanner
import com.minimal.browser.MediaScanner.Kind
import com.minimal.browser.Prefs
import com.minimal.browser.R
import java.util.Locale

/**
 * The media downloader sheet. It moves through three phases:
 *
 *  1. Scan: a radar sweep while the page is read, then the list fades in row by row.
 *  2. Pick: filter, select all or some items, and see the sizes before saving.
 *  3. Download: every chosen file gets its own live progress bar, speed, and
 *     cancel button. Each finished file ticks itself off with an animated check,
 *     and a failed file shakes so it is easy to spot.
 *
 * Downloads keep running if the sheet is closed ("Run in background"). They
 * stay in the Downloads list. [onStart] receives a listener that the caller
 * forwards to [Downloads], so these UI updates and the history stay in sync.
 */
class MediaDownloadDialog(
    private val context: Context,
    private val pageUrl: String,
    private val onStart: (List<MediaScanner.Item>, Downloads.Listener) -> Unit
) {

    private enum class Filter(val label: String) {
        ALL("All"),
        IMAGES("Images"),
        VIDEOS("Videos")
    }

    private enum class Phase { SCANNING, PICK, RUNNING, FINISHED }

    private enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    /** Live state for one file while it downloads. */
    private class Row(val item: MediaScanner.Item) {
        var state = State.QUEUED
        var requestId: String? = null
        var cancelRequested = false
        var received = 0L
        var expected = -1L
        var speed = 0.0
        var lastBytes = 0L
        var lastAt = 0L
        var progress = 0.0
        var reason = ""
        var doneAnimated = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val all = ArrayList<MediaScanner.Item>()
    private val selected = HashSet<String>()
    private val rows = LinkedHashMap<String, Row>()
    private val introduced = HashSet<String>()
    private val tabViews = LinkedHashMap<Filter, TextView>()
    private var visible: List<MediaScanner.Item> = emptyList()
    private var filter = Filter.ALL
    private var phase = Phase.SCANNING
    private var alive = true
    private var dialog: AlertDialog? = null

    private lateinit var status: TextView
    private lateinit var radar: RadarView
    private lateinit var overall: ProgressBar
    private lateinit var tabsRow: LinearLayout
    private lateinit var actionsRow: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var listAdapter: Adapter

    /** Listener handed to the caller. Events are matched to rows by the source URL. */
    private val progressListener = object : Downloads.Listener {
        override fun onStarted(request: Downloads.Request) {
            val row = rows[request.sourceUrl] ?: return
            row.requestId = request.id
            row.state = State.RUNNING
            row.lastAt = SystemClock.elapsedRealtime()
            if (row.cancelRequested) Downloads.cancel(request.id)
            notifyRow(row, payloadOnly = false)
            updateSummary()
        }

        override fun onProgress(request: Downloads.Request, receivedBytes: Long, expectedBytes: Long) {
            val row = rows[request.sourceUrl] ?: return
            val now = SystemClock.elapsedRealtime()
            if (row.lastAt > 0L && now > row.lastAt) {
                val instant = (receivedBytes - row.lastBytes) * 1000.0 / (now - row.lastAt)
                // Smooth the speed so the number does not jitter on every update.
                row.speed = if (row.speed == 0.0) instant else row.speed * 0.6 + instant * 0.4
            }
            row.lastAt = now
            row.lastBytes = receivedBytes
            row.received = receivedBytes
            row.expected = expectedBytes
            row.progress = if (expectedBytes > 0L) {
                (receivedBytes.toDouble() / expectedBytes).coerceIn(0.0, 1.0)
            } else {
                row.progress
            }
            notifyRow(row, payloadOnly = true)
            updateSummary()
        }

        override fun onCompleted(download: Downloads.CompletedDownload) {
            val row = rows[download.request.sourceUrl] ?: return
            row.state = State.DONE
            row.progress = 1.0
            row.received = download.bytes
            notifyRow(row, payloadOnly = false)
            updateSummary()
            checkFinished()
        }

        override fun onFailed(request: Downloads.Request, reason: String) {
            val row = rows[request.sourceUrl] ?: return
            val cancelled = reason == Downloads.CANCELLED_REASON
            row.state = if (cancelled) State.CANCELLED else State.FAILED
            row.reason = if (cancelled) "" else reason
            notifyRow(row, payloadOnly = false)
            if (!cancelled) {
                val index = visible.indexOfFirst { it.url == row.item.url }
                if (index >= 0) {
                    list.postDelayed({
                        list.findViewHolderForAdapterPosition(index)?.let { Motion.shake(it.itemView) }
                    }, 120)
                }
            }
            updateSummary()
            checkFinished()
        }
    }

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(8), context.dp(16), 0)
        }

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        radar = RadarView(context)
        statusRow.addView(radar, LinearLayout.LayoutParams(context.dp(30), context.dp(30)).apply {
            rightMargin = context.dp(12)
        })
        status = TextView(context).apply {
            text = "Scanning this page…"
            setTextColor(TEXT)
            textSize = 13f
        }
        statusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(statusRow)

        overall = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressDrawable = thinBar(context)
            visibility = View.GONE
        }
        root.addView(overall, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(6)).apply {
            topMargin = context.dp(10)
        })

        tabsRow = LinearLayout(context).apply {
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
            tabsRow.addView(tab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = context.dp(8) })
        }
        root.addView(tabsRow)

        actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, context.dp(6), 0, 0)
        }
        actionsRow.addView(actionText("Select all") { selectAllVisible() })
        actionsRow.addView(actionText("Clear") {
            selected.clear()
            listAdapter.notifyDataSetChanged()
            updateButtons()
        })
        root.addView(actionsRow)

        listAdapter = Adapter()
        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
            clipToPadding = false
            setPadding(0, 0, 0, context.dp(4))
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
        dialog = built
        built.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            Motion.press(it)
            onPositive()
        }
        built.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            Motion.press(it)
            onNegative()
        }
        updateButtons()
        updateTabs()

        // The sheet itself springs open, so it feels like it arrives rather than appears.
        root.alpha = 0f
        root.scaleX = 0.9f
        root.scaleY = 0.9f
        root.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(320)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

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
        radar.setRunning(false)
        Motion.fadeOutGone(radar)
        phase = Phase.PICK
        if (result.error != null) {
            status.text = result.error
            updateButtons()
            return
        }
        all.clear()
        all.addAll(result.items)
        selected.clear()
        visible = all.toList()
        updateButtons()

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

        Motion.fadeIn(tabsRow)
        Motion.fadeIn(actionsRow)
        applyFilter()
    }

    /* ------------------------------------------------------------------ */
    /*  buttons and phases                                                 */
    /* ------------------------------------------------------------------ */

    private fun onPositive() {
        when (phase) {
            Phase.PICK -> startDownloads()
            Phase.RUNNING, Phase.FINISHED -> dialog?.dismiss()
            Phase.SCANNING -> dialog?.dismiss()
        }
    }

    private fun onNegative() {
        when (phase) {
            Phase.RUNNING -> cancelAll()
            else -> dialog?.dismiss()
        }
    }

    private fun updateButtons() {
        val positive = dialog?.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        val negative = dialog?.getButton(AlertDialog.BUTTON_NEGATIVE) ?: return
        when (phase) {
            Phase.SCANNING -> {
                positive.text = "Scanning…"
                positive.isEnabled = false
                negative.text = "Close"
                negative.visibility = View.VISIBLE
            }
            Phase.PICK -> {
                positive.isEnabled = selected.isNotEmpty()
                positive.text = if (selected.isEmpty()) "Download selected" else "Download ${selected.size}"
                negative.text = "Close"
                negative.visibility = View.VISIBLE
            }
            Phase.RUNNING -> {
                positive.isEnabled = true
                positive.text = "Run in background"
                negative.text = "Cancel all"
                negative.visibility = View.VISIBLE
            }
            Phase.FINISHED -> {
                positive.isEnabled = true
                positive.text = "Done"
                negative.visibility = View.GONE
            }
        }
    }

    private fun startDownloads() {
        val chosen = all.filter { it.url in selected }
        if (chosen.isEmpty()) {
            status.text = "Tick at least one item to download."
            return
        }
        phase = Phase.RUNNING
        rows.clear()
        chosen.forEach { rows[it.url] = Row(it) }
        visible = chosen

        Motion.fadeOutGone(tabsRow)
        Motion.fadeOutGone(actionsRow)
        overall.visibility = View.VISIBLE
        overall.progress = 0
        status.text = "Starting ${chosen.size} download(s)…"
        updateButtons()
        listAdapter.notifyDataSetChanged()
        updateSummary()

        onStart(chosen, progressListener)
    }

    private fun cancelRow(row: Row) {
        if (row.state != State.QUEUED && row.state != State.RUNNING) return
        row.cancelRequested = true
        val id = row.requestId
        if (id != null) Downloads.cancel(id)
        // Show the cancel at once. The engine confirms it through onFailed shortly after.
        if (row.state == State.QUEUED) {
            row.state = State.CANCELLED
            notifyRow(row, payloadOnly = false)
            updateSummary()
            checkFinished()
        }
    }

    private fun cancelAll() {
        rows.values.toList().forEach { cancelRow(it) }
    }

    private fun updateSummary() {
        if (phase != Phase.RUNNING && phase != Phase.FINISHED) return
        val total = rows.size.coerceAtLeast(1)
        val done = rows.values.count { it.state == State.DONE }
        val finished = rows.values.count {
            it.state == State.DONE || it.state == State.FAILED || it.state == State.CANCELLED
        }
        val fraction = rows.values.sumOf { it.progress } / total
        overall.setProgress((fraction * 1000).toInt(), true)
        if (phase == Phase.RUNNING) {
            status.text = "Downloading $finished of $total · $done saved"
        }
    }

    private fun checkFinished() {
        if (phase != Phase.RUNNING) return
        if (!rows.values.all {
                it.state == State.DONE || it.state == State.FAILED || it.state == State.CANCELLED
            }
        ) return

        phase = Phase.FINISHED
        val saved = rows.values.count { it.state == State.DONE }
        val failed = rows.values.count { it.state == State.FAILED }
        val cancelled = rows.values.count { it.state == State.CANCELLED }
        val parts = ArrayList<String>()
        parts += "$saved saved"
        if (failed > 0) parts += "$failed failed"
        if (cancelled > 0) parts += "$cancelled cancelled"
        status.text = parts.joinToString(" · ")
        overall.setProgress(1000, true)
        updateButtons()
    }

    /* ------------------------------------------------------------------ */
    /*  selection and filters                                              */
    /* ------------------------------------------------------------------ */

    private fun setFilter(option: Filter) {
        if (phase != Phase.PICK) return
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
        updateButtons()
    }

    private fun selectAllVisible() {
        if (phase != Phase.PICK) return
        visible.forEach { selected.add(it.url) }
        listAdapter.notifyDataSetChanged()
        updateButtons()
    }

    private fun updateTabs() {
        tabViews.forEach { (option, view) ->
            val active = option == filter
            view.setTextColor(if (active) 0xFF111111.toInt() else 0xFF8A8A8A.toInt())
            view.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            view.background = context.roundRect(99, if (active) 0xFFE2E2E2.toInt() else 0xFFEFEFEF.toInt())
        }
    }

    /* ------------------------------------------------------------------ */
    /*  row helpers                                                        */
    /* ------------------------------------------------------------------ */

    private fun notifyRow(row: Row, payloadOnly: Boolean) {
        val index = visible.indexOfFirst { it.url == row.item.url }
        if (index < 0) return
        if (payloadOnly) listAdapter.notifyItemChanged(index, PAYLOAD_PROGRESS)
        else listAdapter.notifyItemChanged(index)
    }

    private fun actionText(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(0xFF111111.toInt())
        setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
        setOnClickListener {
            Motion.press(it)
            onClick()
        }
    }

    private fun describe(item: MediaScanner.Item): String {
        val size = if (item.bytes >= 0) formatBytes(item.bytes) else "size unknown"
        return when (item.kind) {
            Kind.IMAGE -> "Image · ${item.width}x${item.height} · $size"
            Kind.VIDEO -> "Video · ${item.quality.ifBlank { "quality not labeled" }} · $size"
        }
    }

    private fun detailFor(row: Row): String = when (row.state) {
        State.QUEUED -> "Waiting in line…"
        State.RUNNING -> {
            val amount = if (row.expected > 0L) {
                "${formatBytes(row.received)} of ${formatBytes(row.expected)}"
            } else {
                formatBytes(row.received)
            }
            val speed = if (row.speed >= 1.0) " · ${formatBytes(row.speed.toLong())}/s" else ""
            amount + speed
        }
        State.DONE -> "Saved · ${formatBytes(row.received)}"
        State.FAILED -> row.reason.ifBlank { "Failed" }
        State.CANCELLED -> "Cancelled"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun thinBar(ctx: Context): Drawable {
        val track = ctx.roundRect(3, 0xFFE6E6E6.toInt())
        val fill = ClipDrawable(ctx.roundRect(3, 0xFF111111.toInt()), Gravity.START, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf<Drawable>(track, fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    /* ------------------------------------------------------------------ */
    /*  list                                                               */
    /* ------------------------------------------------------------------ */

    /** One list row. Kept outside [Adapter] because Kotlin forbids nested classes inside inner classes. */
    private class Holder(
        val row: LinearLayout,
        val thumb: ImageView,
        val title: TextView,
        val detail: TextView,
        val check: CheckBox,
        val bar: ProgressBar,
        val cancel: ImageView,
        val done: CheckMarkView
    ) : RecyclerView.ViewHolder(row)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        override fun getItemCount(): Int = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val check = CheckBox(ctx)
            val thumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFEFEFEF.toInt())
                clipToOutline = true
                background = ctx.roundRect(8, 0xFFEFEFEF.toInt())
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
                ellipsize = TextUtils.TruncateAt.END
            }
            val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progressDrawable = thinBar(ctx)
                visibility = View.GONE
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(detail)
                addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(5)).apply {
                    topMargin = ctx.dp(5)
                })
            }
            val cancel = ImageView(ctx).apply {
                icon(R.drawable.ic_x, 0xFF4A4A4A.toInt())
                contentDescription = "Cancel this download"
                background = ctx.roundRect(99, 0xFFEFEFEF.toInt())
                setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
                visibility = View.GONE
            }
            val done = CheckMarkView(ctx).apply { visibility = View.GONE }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(4), ctx.dp(7), ctx.dp(4), ctx.dp(7))
                addView(check)
                addView(thumb, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)).apply {
                    leftMargin = ctx.dp(6)
                })
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = ctx.dp(10)
                    rightMargin = ctx.dp(8)
                })
                addView(cancel, LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)))
                addView(done, LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)).apply {
                    leftMargin = ctx.dp(4)
                })
            }
            row.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return Holder(row, thumb, title, detail, check, bar, cancel, done)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            bind(holder, position, full = true)
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_PROGRESS)) {
                val row = rows[visible[position].url] ?: return
                bindProgress(holder, row)
            } else {
                bind(holder, position, full = true)
            }
        }

        private fun bind(holder: Holder, position: Int, full: Boolean) {
            val item = visible[position]
            val row = rows.getOrPut(item.url) { Row(item) }
            val picking = phase == Phase.PICK || phase == Phase.SCANNING

            holder.title.text = item.fileName
            val preview = item.thumbnail
            if (preview != null) {
                holder.thumb.imageTintList = null
                holder.thumb.setImageBitmap(BitmapFactory.decodeByteArray(preview, 0, preview.size))
            } else {
                holder.thumb.setImageDrawable(null)
                holder.thumb.icon(R.drawable.ic_file, 0xFF7A7A7A.toInt())
            }

            // Clear the listener first, otherwise rebinding would change the selection.
            holder.check.setOnCheckedChangeListener(null)
            holder.check.visibility = if (picking) View.VISIBLE else View.GONE
            if (picking) {
                holder.check.isChecked = item.url in selected
                holder.check.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(item.url) else selected.remove(item.url)
                    updateButtons()
                }
                holder.row.isClickable = true
                holder.row.setOnClickListener {
                    Motion.press(holder.row)
                    holder.check.isChecked = !holder.check.isChecked
                }
                holder.detail.text = describe(item)
            } else {
                holder.row.isClickable = false
                holder.row.setOnClickListener(null)
                holder.detail.text = detailFor(row)
            }
            holder.detail.setTextColor(if (row.state == State.FAILED) 0xFFB00020.toInt() else 0xFF6A6A6A.toInt())

            holder.bar.visibility = if (!picking) View.VISIBLE else View.GONE
            holder.bar.isIndeterminate = !picking && row.state == State.RUNNING && row.expected <= 0L
            holder.bar.setProgress((row.progress * 1000).toInt(), false)

            val live = !picking && (row.state == State.QUEUED || row.state == State.RUNNING)
            holder.cancel.visibility = if (live) View.VISIBLE else View.GONE
            holder.cancel.setOnClickListener {
                Motion.press(it)
                cancelRow(row)
            }

            val finished = !picking && row.state == State.DONE
            holder.done.visibility = if (finished) View.VISIBLE else View.GONE
            if (finished && !row.doneAnimated) {
                row.doneAnimated = true
                holder.done.animateIn()
            }

            if (full && introduced.add(item.url)) {
                Motion.springIn(holder.row, Motion.staggerDelay(position))
            }
        }

        private fun bindProgress(holder: Holder, row: Row) {
            holder.bar.isIndeterminate = row.state == State.RUNNING && row.expected <= 0L
            holder.bar.setProgress((row.progress * 1000).toInt(), true)
            holder.detail.text = detailFor(row)
        }
    }

    private companion object {
        val TEXT: Int = 0xFF1A1A1A.toInt()
        const val PAYLOAD_PROGRESS = "progress"
    }
}
