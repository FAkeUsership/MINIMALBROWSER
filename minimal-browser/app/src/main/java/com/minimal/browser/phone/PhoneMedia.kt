package com.minimal.browser.phone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import com.minimal.browser.ui.RadarView
import com.minimal.browser.ui.dp
import com.minimal.browser.ui.icon
import com.minimal.browser.ui.roundRect

/** Thin rounded progress bar. Animates smoothly between values. */
class BarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var value = 0f
    private var animator: ValueAnimator? = null

    fun setFraction(fraction: Float) {
        val target = fraction.coerceIn(0f, 1f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(value, target).apply {
            duration = 280
            addUpdateListener {
                value = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        paint.color = Palette.PAPER_DEEP
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, paint)
        if (value > 0f) {
            paint.color = Palette.ACCENT
            rect.set(0f, 0f, maxOf(h, width * value), h)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}

/** A small custom play triangle. Drawn so it stays crisp at any size. */
class PlayGlyph(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK
        style = Paint.Style.FILL
    }
    private val path = Path()

    var tint: Int
        get() = fill.color
        set(value) { fill.color = value }

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

/** Callbacks a card can fire. */
class RowActions(
    val download: () -> Unit,
    val cancel: () -> Unit,
    val open: (PhoneActivity.Job) -> Unit
)

/**
 * Media sheet, in the style of InShot's downloader: the page is scanned and each
 * file appears as a large card with a thumbnail, its quality and format, and one
 * clear action. Downloading turns the card into a progress bar with speed; a
 * finished card offers Play.
 */
class MediaSheet(private val activity: PhoneActivity, private val pageUrl: String) : FrameLayout(activity) {

    /** Registered with the activity so cards redraw when a download changes. */
    val jobListener: (String) -> Unit = { url -> rowChanged(url) }

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
        titles.addView(activity.displayTitle("Found here", 36f))
        titles.addView(subtitle.apply { setPadding(0, activity.dp(4), 0, 0) })
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(radar, LinearLayout.LayoutParams(activity.dp(28), activity.dp(28)).apply { rightMargin = activity.dp(8) })
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
        list.setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(110))
        list.layoutAnimation = LayoutAnimationController(AnimationSet(true).apply {
            addAnimation(AlphaAnimation(0f, 1f))
            addAnimation(TranslateAnimation(0f, 0f, activity.dp(22).toFloat(), 0f))
            duration = 340
        }, 0.1f)
        content.addView(list, FrameLayout.LayoutParams(-1, -1))

        emptyState.setPadding(activity.dp(24), activity.dp(40), activity.dp(24), 0)
        emptyState.addView(activity.displayTitle("Nothing yet", 30f))
        emptyState.addView(activity.label(
            "Press play on the video, then tap Look again. Some sites only show their videos after playback starts.",
            15f, Palette.INK_SOFT
        ).apply { setPadding(0, activity.dp(10), 0, activity.dp(18)) })
        emptyState.addView(activity.iconChip(R.drawable.ic_refresh, "Look again") { startScan() },
            LinearLayout.LayoutParams(activity.dp(52), activity.dp(52)))
        content.addView(emptyState, FrameLayout.LayoutParams(-1, -2))
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = activity.dp(4) })

        footer.apply {
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Type.display
            setTextColor(Palette.WHITE)
            background = activity.roundRect(99, Palette.ACCENT)
            springPress(0.96f)
            setOnClickListener { downloadAllPending() }
        }
        addView(column, FrameLayout.LayoutParams(-1, -1))
        addView(footer, FrameLayout.LayoutParams(-1, activity.dp(58), Gravity.BOTTOM).apply {
            marginStart = activity.dp(22); marginEnd = activity.dp(22); bottomMargin = activity.dp(22)
        })

        activity.addJobListener(jobListener)
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
        footer.visibility = if (visible.isNotEmpty()) View.VISIBLE else View.GONE
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

    /* ---------------- cards ---------------- */

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount(): Int = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = MediaCardView(activity)
            card.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = activity.dp(14) }
            return Holder(card)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = visible[position]
            holder.card.bindFull(item, activity.jobFor(item.url), actionsFor(item))
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
            } else {
                val item = visible[position]
                holder.card.bindState(item, activity.jobFor(item.url), actionsFor(item))
            }
        }
    }

    private fun actionsFor(item: MediaScanner.Item) = RowActions(
        download = { activity.downloadMedia(item) },
        cancel = { activity.cancelMedia(item.url) },
        open = { job -> job.localUri?.let { activity.openDownloadedFile(it, job.mime) } }
    )

    private class Holder(val card: MediaCardView) : RecyclerView.ViewHolder(card)

    companion object {
        private const val PAYLOAD_STATE = "state"
    }
}

/**
 * One detected file as a card. Layout top to bottom: thumbnail with quality and
 * kind badges, title, facts, and one action row whose content depends on state.
 */
class MediaCardView(context: Context) : LinearLayout(context) {

    private val thumbBox = FrameLayout(context).apply { setBackgroundColor(0xFF17171A.toInt()) }
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val glyphDisc = FrameLayout(context).apply {
        background = context.roundRect(99, Palette.PAPER)
    }
    private val glyph = PlayGlyph(context).apply { tint = Palette.INK }
    private val qualityBadge = badge(context)
    private val kindBadge = badge(context)

    private val title = TextView(context).apply {
        textSize = 16f
        typeface = Type.title
        setTextColor(Palette.INK)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.MIDDLE
        includeFontPadding = false
    }
    private val meta = TextView(context).apply {
        textSize = 13f
        typeface = Type.body
        setTextColor(Palette.INK_SOFT)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, context.dp(4), 0, 0)
    }

    private val actions = FrameLayout(context)
    private val idleButton = pill(context, "Download", Palette.ACCENT, Palette.WHITE)
    private val doneButton = pill(context, "Play", Palette.INK, Palette.WHITE)
    private val retryButton = pill(context, "Try again", Palette.ACCENT, Palette.WHITE)
    private val runningRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val bar = BarView(context)
    private val cancelChip = context.iconChip(R.drawable.ic_x, "Cancel", Palette.INK_SOFT) {}

    private var actionsRef: RowActions? = null

    init {
        orientation = LinearLayout.VERTICAL
        background = context.roundRect(26, Palette.CARD, Palette.LINE)
        clipToOutline = true

        thumbBox.addView(image, FrameLayout.LayoutParams(-1, -1))
        glyphDisc.addView(glyph, FrameLayout.LayoutParams(context.dp(20), context.dp(20), Gravity.CENTER))
        thumbBox.addView(glyphDisc, FrameLayout.LayoutParams(context.dp(58), context.dp(58), Gravity.CENTER))
        thumbBox.addView(qualityBadge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, context.dp(26), Gravity.TOP or Gravity.START
        ).apply { leftMargin = context.dp(12); topMargin = context.dp(12) })
        thumbBox.addView(kindBadge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, context.dp(26), Gravity.TOP or Gravity.END
        ).apply { rightMargin = context.dp(12); topMargin = context.dp(12) })
        addView(thumbBox, LinearLayout.LayoutParams(-1, context.dp(172)))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(16))
        }
        body.addView(title, LinearLayout.LayoutParams(-1, -2))
        body.addView(meta, LinearLayout.LayoutParams(-1, -2))

        runningRow.addView(bar, LinearLayout.LayoutParams(0, context.dp(8), 1f).apply { rightMargin = context.dp(12) })
        runningRow.addView(cancelChip, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))

        actions.addView(idleButton, FrameLayout.LayoutParams(-1, context.dp(50), Gravity.CENTER))
        actions.addView(doneButton, FrameLayout.LayoutParams(-1, context.dp(50), Gravity.CENTER))
        actions.addView(retryButton, FrameLayout.LayoutParams(-1, context.dp(50), Gravity.CENTER))
        actions.addView(runningRow, FrameLayout.LayoutParams(-1, context.dp(50), Gravity.CENTER))
        body.addView(actions, LinearLayout.LayoutParams(-1, context.dp(50)).apply { topMargin = context.dp(14) })

        addView(body, LinearLayout.LayoutParams(-1, -2))
    }

    fun bindFull(item: MediaScanner.Item, job: PhoneActivity.Job?, actions: RowActions) {
        val isVideo = item.kind == MediaScanner.Kind.VIDEO
        glyphDisc.visibility = if (isVideo) View.VISIBLE else View.GONE
        image.visibility = if (isVideo) View.GONE else View.VISIBLE
        if (!isVideo) {
            val bytes = item.thumbnail
            val bitmap: Bitmap? = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) image.setImageBitmap(bitmap) else image.setImageDrawable(null)
        }
        title.text = item.fileName
        setBadge(qualityBadge, if (isVideo) item.quality else "")
        setBadge(kindBadge, if (isVideo) "VIDEO" else "IMAGE")
        bindState(item, job, actions)
    }

    fun bindState(item: MediaScanner.Item, job: PhoneActivity.Job?, actions: RowActions) {
        actionsRef = actions
        val isVideo = item.kind == MediaScanner.Kind.VIDEO
        val ext = item.mimeType.substringAfter('/').substringBefore(';').uppercase()
            .let { if (it.isBlank() || it == "OCTET-STREAM") (if (isVideo) "VIDEO" else "IMAGE") else it }
        val size = if (item.bytes > 0) Fmt.bytes(item.bytes) else ""
        val dims = if (!isVideo && item.width > 0) "${item.width}×${item.height}" else ""
        val facts = listOf(ext, dims, size).filter { it.isNotBlank() }.joinToString("  ·  ")

        meta.setTextColor(Palette.INK_SOFT)
        idleButton.visibility = View.GONE
        doneButton.visibility = View.GONE
        retryButton.visibility = View.GONE
        runningRow.visibility = View.GONE

        when {
            job == null -> {
                meta.text = facts
                idleButton.visibility = View.VISIBLE
                idleButton.setOnClickListener { actions.download() }
                setOnClickListener(null)
            }
            job.state == PhoneActivity.Job.State.RUNNING -> {
                val pct = (job.fraction * 100).toInt()
                val speed = if (job.bytesPerSecond > 0) "  ·  ${Fmt.bytes(job.bytesPerSecond)}/s" else ""
                meta.text = if (job.expected > 0) "$pct%  ·  ${Fmt.bytes(job.received)} of ${Fmt.bytes(job.expected)}$speed"
                else "${Fmt.bytes(job.received)} received$speed"
                runningRow.visibility = View.VISIBLE
                bar.setFraction(job.fraction)
                cancelChip.setOnClickListener { actions.cancel() }
                setOnClickListener(null)
            }
            job.state == PhoneActivity.Job.State.DONE -> {
                meta.text = "Saved  ·  $facts"
                doneButton.visibility = View.VISIBLE
                doneButton.setOnClickListener { actions.open(job) }
                setOnClickListener { actions.open(job) }
            }
            else -> {
                meta.text = job.reason.ifBlank { "Download failed" }
                meta.setTextColor(Palette.DANGER)
                retryButton.visibility = View.VISIBLE
                retryButton.setOnClickListener { actions.download() }
                setOnClickListener(null)
            }
        }
    }

    private fun pill(context: Context, text: String, fill: Int, ink: Int): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Type.display
        letterSpacing = 0.01f
        setTextColor(ink)
        background = context.roundRect(99, fill)
        springPress(0.97f)
    }

    private fun badge(context: Context): TextView = TextView(context).apply {
        textSize = 11f
        typeface = Type.display
        letterSpacing = 0.06f
        setTextColor(Palette.WHITE)
        gravity = Gravity.CENTER
        setPadding(context.dp(9), 0, context.dp(9), 0)
        background = context.roundRect(8, 0xB3000000.toInt())
        visibility = View.GONE
    }

    private fun setBadge(view: TextView, text: String) {
        view.text = text
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }
}
