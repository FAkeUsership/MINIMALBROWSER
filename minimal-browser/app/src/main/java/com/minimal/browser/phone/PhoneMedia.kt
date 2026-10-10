package com.minimal.browser.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.LayoutAnimationController
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minimal.browser.Fmt
import com.minimal.browser.MediaScanner
import com.minimal.browser.Prefs
import com.minimal.browser.R
import com.minimal.browser.ui.CheckMarkView
import com.minimal.browser.ui.RadarView
import com.minimal.browser.ui.dp
import com.minimal.browser.ui.icon
import com.minimal.browser.ui.roundRect

/**
 * Media sheet, modelled on the InShot-style video downloader: the page is scanned,
 * every file is listed with its type, quality, and size, and each one has its own
 * download control. Progress rings, speed, and the finished state update in place.
 */
class MediaSheet(private val activity: PhoneActivity, private val pageUrl: String) : FrameLayout(activity) {

    private var all: List<MediaScanner.Item> = activity.detectedMedia.toList()
    private var visible: List<MediaScanner.Item> = emptyList()
    private var filter = 0
    private var scanning = true
    private var scanError: String? = null

    private val subtitle = activity.label("", 14f, Palette.INK_SOFT)
    private val radar = RadarView(activity).apply { setRunning(true) }
    private val segmented = Segmented(activity, listOf("All", "Videos", "Images")) { setFilter(it) }
    private val list = RecyclerView(activity)
    private val emptyState = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val footer = TextView(activity)
    private val adapter = Adapter()

    init {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(22), activity.dp(22), activity.dp(18), 0)
        }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(activity.displayTitle("Media", 36f))
        titles.addView(subtitle.apply { setPadding(0, activity.dp(4), 0, 0) })
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(radar, LinearLayout.LayoutParams(activity.dp(30), activity.dp(30)).apply { rightMargin = activity.dp(8) })
        header.addView(activity.iconChip(R.drawable.ic_x, "Close") { activity.sheet.dismiss() },
            LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)))
        column.addView(header, LinearLayout.LayoutParams(-1, -2))

        column.addView(segmented, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = activity.dp(22); rightMargin = activity.dp(22); topMargin = activity.dp(16)
        })

        val content = FrameLayout(activity)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.clipToPadding = false
        list.overScrollMode = OVER_SCROLL_NEVER
        list.setPadding(activity.dp(18), activity.dp(14), activity.dp(18), activity.dp(110))
        list.layoutAnimation = LayoutAnimationController(AnimationSet(true).apply {
            addAnimation(AlphaAnimation(0f, 1f))
            addAnimation(TranslateAnimation(0f, 0f, activity.dp(22).toFloat(), 0f))
            duration = 340
        }, 0.12f)
        content.addView(list, FrameLayout.LayoutParams(-1, -1))

        emptyState.setPadding(activity.dp(24), activity.dp(40), activity.dp(24), 0)
        emptyState.addView(activity.displayTitle("Nothing here yet", 28f))
        emptyState.addView(activity.label(
            "Start a video on the page, then tap Look again. Some sites only reveal their videos after you press play.",
            15f, Palette.INK_SOFT
        ).apply { setPadding(0, activity.dp(10), 0, activity.dp(18)) })
        emptyState.addView(activity.iconChip(R.drawable.ic_refresh, "Look again") {
            startScan()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(56), activity.dp(56))
        }, LinearLayout.LayoutParams(activity.dp(56), activity.dp(56)))
        content.addView(emptyState, FrameLayout.LayoutParams(-1, -2))
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = activity.dp(4) })

        // The footer floats over the list so "Download all" is always in reach.
        footer.apply {
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Type.display
            letterSpacing = 0.01f
            setTextColor(Palette.WHITE)
            background = activity.roundRect(99, Palette.ACCENT)
            springPress(0.96f)
            setOnClickListener { downloadAllPending() }
        }
        addView(column, FrameLayout.LayoutParams(-1, -1))
        addView(footer, FrameLayout.LayoutParams(-1, activity.dp(58), android.view.Gravity.BOTTOM).apply {
            marginStart = activity.dp(22); marginEnd = activity.dp(22); bottomMargin = activity.dp(22)
        })

        activity.onJobChanged = { url -> rowChanged(url) }
        startScan()
        refreshUi(animate = true)
    }

    /* ---------------- scanning ---------------- */

    private fun startScan() {
        scanning = true
        scanError = null
        refreshUi(animate = false)
        Thread({
            val result = runCatching {
                MediaScanner.scan(pageUrl, Prefs.mediaMinImagePx, Prefs.mediaMinVideoMb, includeImages = true)
            }
            activity.main.post {
                if (activity.isDestroyed) return@post
                scanning = false
                result.onSuccess { found ->
                    all = found.items
                    scanError = found.error
                }.onFailure {
                    scanError = "This page could not be read"
                }
                refreshUi(animate = true)
            }
        }, "media-scan-sheet").start()
    }

    /* ---------------- state ---------------- */

    private fun setFilter(index: Int) {
        filter = index
        refreshUi(animate = true)
    }

    private fun refreshUi(animate: Boolean) {
        visible = when (filter) {
            1 -> all.filter { it.kind == MediaScanner.Kind.VIDEO }
            2 -> all.filter { it.kind == MediaScanner.Kind.IMAGE }
            else -> all
        }
        subtitle.text = when {
            scanning && all.isEmpty() -> "Scanning this page…"
            scanning -> "${all.size} found so far · still scanning"
            scanError != null && all.isEmpty() -> scanError
            else -> {
                val videos = all.count { it.kind == MediaScanner.Kind.VIDEO }
                val images = all.size - videos
                "$videos ${if (videos == 1) "video" else "videos"} · $images ${if (images == 1) "image" else "images"}"
            }
        }
        radar.visibility = if (scanning) View.VISIBLE else View.GONE
        val empty = !scanning && visible.isEmpty()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        list.visibility = if (empty) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
        if (animate && visible.isNotEmpty()) list.scheduleLayoutAnimation()
        updateFooter()
    }

    private fun rowChanged(url: String) {
        val index = visible.indexOfFirst { it.url == url }
        if (index >= 0) adapter.notifyItemChanged(index, PAYLOAD_STATE)
        updateFooter()
    }

    private fun pendingItems(): List<MediaScanner.Item> = visible.filter { item ->
        val job = activity.jobFor(item.url)
        job == null || job.state == PhoneActivity.Job.State.FAILED
    }

    private fun updateFooter() {
        val pending = pendingItems()
        val running = visible.count { activity.jobFor(it.url)?.state == PhoneActivity.Job.State.RUNNING }
        val visibleNow = visible.isNotEmpty()
        footer.visibility = if (visibleNow) View.VISIBLE else View.GONE
        when {
            pending.isEmpty() && running > 0 -> {
                footer.text = "Downloading $running…"
                footer.isEnabled = false
            }
            pending.isEmpty() -> {
                footer.text = "All saved"
                footer.isEnabled = false
            }
            else -> {
                footer.text = "Download ${pending.size} ${if (pending.size == 1) "file" else "files"}"
                footer.isEnabled = true
            }
        }
        footer.alpha = if (footer.isEnabled) 1f else 0.55f
    }

    private fun downloadAllPending() {
        pendingItems().forEach { activity.downloadMedia(it) }
        updateFooter()
    }

    /* ---------------- rows ---------------- */

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount(): Int = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = MediaRowView(activity)
            row.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = activity.dp(12) }
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = visible[position]
            holder.row.bindFull(item, activity.jobFor(item.url), actionsFor(item))
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
            } else {
                val item = visible[position]
                holder.row.bindState(item, activity.jobFor(item.url), actionsFor(item))
            }
        }
    }

    private fun actionsFor(item: MediaScanner.Item) = RowActions(
        download = { activity.downloadMedia(item) },
        cancel = { activity.cancelMedia(item.url) },
        open = { job -> job.localUri?.let { activity.openDownloadedFile(it, job.mime) } }
    )

    private class Holder(val row: MediaRowView) : RecyclerView.ViewHolder(row)

    companion object {
        private const val PAYLOAD_STATE = "state"
    }
}

/** Callbacks a row can fire. Kept as a value so rebinding never allocates a listener chain. */
class RowActions(
    val download: () -> Unit,
    val cancel: () -> Unit,
    val open: (PhoneActivity.Job) -> Unit
)

/** One media file: thumbnail, name, facts, and the control for its current state. */
class MediaRowView(context: Context) : FrameLayout(context) {

    private val thumb = FrameLayout(context).apply {
        background = context.roundRect(18, Palette.PAPER_DEEP)
        clipToOutline = true
    }
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val play = PlayGlyph(context)
    private val title = TextView(context).apply {
        textSize = 15f
        typeface = Type.title
        setTextColor(Palette.INK)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    private val meta = TextView(context).apply {
        textSize = 13f
        typeface = Type.body
        setTextColor(Palette.INK_SOFT)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, context.dp(4), 0, 0)
    }
    private val quality = TextView(context).apply {
        textSize = 11f
        typeface = Type.display
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER
        background = context.roundRect(8, Palette.PAPER_DEEP)
        setPadding(context.dp(7), 0, context.dp(7), 0)
        visibility = View.GONE
    }
    private val action = FrameLayout(context).apply { springPress(0.86f) }
    private val disc = ImageView(context).apply {
        background = context.roundRect(99, Palette.ACCENT)
        icon(R.drawable.ic_download, Palette.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "Download"
    }
    private val ring = RingView(context)
    private val percent = TextView(context).apply {
        textSize = 10f
        typeface = Type.display
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER
    }
    private val done = CheckMarkView(context)
    private val retry = ImageView(context).apply {
        background = context.roundRect(99, Palette.PAPER_DEEP)
        icon(R.drawable.ic_refresh, Palette.ACCENT)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "Retry"
    }

    private var actions: RowActions? = null
    private var currentJob: PhoneActivity.Job? = null
    private var currentItem: MediaScanner.Item? = null

    init {
        background = context.roundRect(24, Palette.CARD, Palette.LINE)
        clipToOutline = true

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), context.dp(12), context.dp(14), context.dp(12))
        }
        thumb.addView(image, FrameLayout.LayoutParams(-1, -1))
        thumb.addView(play, FrameLayout.LayoutParams(context.dp(30), context.dp(30), Gravity.CENTER))
        row.addView(thumb, LinearLayout.LayoutParams(context.dp(72), context.dp(72)))

        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), 0, context.dp(8), 0)
        }
        val titleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(quality, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(22)).apply { leftMargin = context.dp(6) })
        text.addView(titleRow, LinearLayout.LayoutParams(-1, -2))
        text.addView(meta, LinearLayout.LayoutParams(-1, -2))
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        action.addView(disc, FrameLayout.LayoutParams(context.dp(46), context.dp(46), Gravity.CENTER))
        action.addView(ring, FrameLayout.LayoutParams(context.dp(46), context.dp(46), Gravity.CENTER))
        action.addView(percent, FrameLayout.LayoutParams(context.dp(46), context.dp(46), Gravity.CENTER))
        action.addView(done, FrameLayout.LayoutParams(context.dp(46), context.dp(46), Gravity.CENTER))
        action.addView(retry, FrameLayout.LayoutParams(context.dp(46), context.dp(46), Gravity.CENTER))
        row.addView(action, LinearLayout.LayoutParams(context.dp(46), context.dp(46)))

        addView(row, FrameLayout.LayoutParams(-1, -2))
    }

    /** Full bind: thumbnail and facts, then the state. Used when a row first appears. */
    fun bindFull(item: MediaScanner.Item, job: PhoneActivity.Job?, actions: RowActions) {
        currentItem = item
        val isVideo = item.kind == MediaScanner.Kind.VIDEO
        play.visibility = if (isVideo) View.VISIBLE else View.GONE
        image.visibility = if (isVideo) View.GONE else View.VISIBLE
        if (!isVideo) {
            val bytes = item.thumbnail
            val bitmap: Bitmap? = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) image.setImageBitmap(bitmap) else image.setImageResource(0)
        }
        title.text = item.fileName
        quality.text = item.quality
        quality.visibility = if (isVideo && item.quality.isNotBlank()) View.VISIBLE else View.GONE
        bindState(item, job, actions)
    }

    /** Light bind: just the state. Used for progress ticks, so thumbnails are not decoded again. */
    fun bindState(item: MediaScanner.Item, job: PhoneActivity.Job?, actions: RowActions) {
        this.actions = actions
        currentItem = item
        currentJob = job
        val size = if (item.bytes > 0) Fmt.bytes(item.bytes) else ""
        val ext = item.mimeType.substringAfter('/').substringBefore(';').uppercase()
            .let { if (it.isBlank() || it == "OCTET-STREAM") "FILE" else it }
        val facts = if (item.kind == MediaScanner.Kind.VIDEO) {
            listOf(ext, size)
        } else {
            listOf(if (item.width > 0) "${item.width}×${item.height}" else "", size)
        }.filter { it.isNotBlank() }.joinToString("  ·  ")

        disc.visibility = View.GONE
        ring.visibility = View.GONE
        percent.visibility = View.GONE
        done.visibility = View.GONE
        retry.visibility = View.GONE
        action.setOnClickListener(null)
        action.isClickable = false
        setOnClickListener(null)
        meta.setTextColor(Palette.INK_SOFT)

        when {
            job == null -> {
                disc.visibility = View.VISIBLE
                meta.text = facts.ifBlank { ext }
                action.isClickable = true
                action.setOnClickListener { actions.download() }
                action.contentDescription = "Download"
            }
            job.state == PhoneActivity.Job.State.RUNNING -> {
                ring.visibility = View.VISIBLE
                percent.visibility = View.VISIBLE
                val pct = (job.fraction * 100).toInt()
                percent.text = if (job.expected > 0) "$pct%" else "…"
                ring.setProgress(job.fraction)
                val speed = if (job.bytesPerSecond > 0) "  ·  ${Fmt.bytes(job.bytesPerSecond)}/s" else ""
                meta.text = if (job.expected > 0) {
                    "${Fmt.bytes(job.received)} of ${Fmt.bytes(job.expected)}$speed"
                } else {
                    "${Fmt.bytes(job.received)} received$speed"
                }
                action.isClickable = true
                action.setOnClickListener { actions.cancel() }
                action.contentDescription = "Cancel"
            }
            job.state == PhoneActivity.Job.State.DONE -> {
                done.visibility = View.VISIBLE
                done.animateIn()
                meta.text = "Saved  ·  " + facts
                isClickable = true
                setOnClickListener { actions.open(job) }
                action.isClickable = true
                action.setOnClickListener { actions.open(job) }
                action.contentDescription = "Open"
            }
            else -> {
                retry.visibility = View.VISIBLE
                val reason = job.reason.ifBlank { "Failed" }
                meta.text = reason
                meta.setTextColor(Palette.DANGER)
                action.isClickable = true
                action.setOnClickListener { actions.download() }
                action.contentDescription = "Retry"
            }
        }
    }
}

/** A small, custom play triangle inside a paper disc. Drawn, not an icon font, so it stays crisp. */
class PlayGlyph(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK
        style = Paint.Style.FILL
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        path.moveTo(w * 0.36f, h * 0.26f)
        path.lineTo(w * 0.76f, h * 0.5f)
        path.lineTo(w * 0.36f, h * 0.74f)
        path.close()
        canvas.drawPath(path, fill)
    }
}
