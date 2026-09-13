package com.minimal.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.minimal.browser.R
import com.minimal.browser.Tab
import com.minimal.browser.UrlBar
import android.webkit.WebView

/**
 * `#s-web` — the browser screen.
 *
 * The HTML mock had a fake status bar (clock / 5G / battery). That was a mock of
 * the *Android* status bar and has been removed on purpose: on a real device the
 * OS draws it, and in full screen this app hides it properly.
 */
class WebScreen(context: Context) : LinearLayout(context) {

    /* ---- callbacks ---- */
    var onBack: (() -> Unit)? = null
    var onForward: (() -> Unit)? = null
    var onReload: (() -> Unit)? = null
    var onTabs: (() -> Unit)? = null
    var onMenu: (() -> Unit)? = null
    var onSubmitAddress: ((String) -> Unit)? = null
    /** Used only while page-only mode is active. */
    var onPageDoubleTap: (() -> Unit)? = null
    var onAddressFocused: (() -> Unit)? = null

    /* ---- views ---- */
    val webBar: LinearLayout
    val addressPill: LinearLayout
    val addressLabel: TextView
    val lockIcon: ImageView
    val blockedChip: TextView
    private val webDivider: View
    val progressTrack: FrameLayout
    private val progressFill: View
    val container: FrameLayout
    private var attachedWebView: WebView? = null
    val blockedBanner: TextView

    private val btnBack: ImageView
    private val btnForward: ImageView
    private val btnReload: ImageView
    private var editing = false
    private var pageOnly = false

    /**
     * Returning false from the touch listener below keeps ordinary web content
     * gestures intact. The detector merely observes a double tap while the page
     * is in the clean, page-only mode.
     */
    private val pageGestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (!pageOnly) return false
                onPageDoubleTap?.invoke()
                return true
            }
        }
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)

        /* ---------------- web bar ---------------- */
        webBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = context.dp(12)
            setPadding(p, context.dp(8), p, context.dp(8))
        }
        webBar.setBackgroundColor(Color.WHITE)

        btnBack = webButton(R.drawable.ic_back, R.string.cd_back) { onBack?.invoke() }
        btnForward = webButton(R.drawable.ic_fwd, R.string.cd_forward) { onForward?.invoke() }
        btnReload = webButton(R.drawable.ic_refresh, R.string.cd_reload) { onReload?.invoke() }
        webBar.addView(btnBack, barButtonParams())
        webBar.addView(btnForward, barButtonParams())
        webBar.addView(btnReload, barButtonParams())

        /* address pill (.addr) */
        addressPill = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundRect(99, 0xFFEFEFEF.toInt())
            setPadding(context.dp(14), 0, context.dp(14), 0)
            isClickable = true
            isFocusable = true
        }
        lockIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_lock)
            icon(R.drawable.ic_lock, 0xFF7A7A7A.toInt())
        }
        addressPill.addView(lockIcon, LayoutParams(context.dp(14), context.dp(14)).apply {
            rightMargin = context.dp(9)
        })

        addressLabel = TextView(context).apply {
            text = "Search or type a URL"
            setTextColor(0xFF1A1A1A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.NORMAL)
            isSingleLine = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addressPill.addView(addressLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 1f })

        blockedChip = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
        }
        addressPill.addView(blockedChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = context.dp(10)
        })

        addressPill.setOnClickListener { beginEditing() }
        webBar.addView(addressPill, LayoutParams(0, context.dp(34)).apply {
            weight = 1f
            leftMargin = context.dp(8)
            rightMargin = context.dp(8)
        })

        webBar.addView(webButton(R.drawable.ic_tabs, R.string.cd_tabs) { onTabs?.invoke() }, barButtonParams())
        webBar.addView(webButton(R.drawable.ic_menu, R.string.cd_menu) { onMenu?.invoke() }, barButtonParams())

        addView(webBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // `border-bottom:1px solid #e3e3e3` from the HTML. It is deliberately
        // removed in page-only mode so no app pixel remains above the web page.
        webDivider = View(context).apply { setBackgroundColor(Ink.PLINE.toInt()) }
        addView(webDivider, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1)))

        /* ---------------- progress track (.webtrack) ---------------- */
        progressTrack = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        progressFill = View(context).apply { setBackgroundColor(0xFF111111.toInt()) }
        progressTrack.addView(progressFill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        progressTrack.visibility = INVISIBLE
        addView(progressTrack, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(3)))

        /* ---------------- page + overlays ---------------- */
        container = FrameLayout(context).apply { setBackgroundColor(Ink.SHELL) }

        // The active Android System WebView is attached here by attachWebView().
        // Keeping the surface empty until a real tab exists avoids all hidden
        // browser-renderer startup work on Home and Tabs.

        // .blocked — the floating "3 ads & 1 tracker blocked" pill
        blockedBanner = TextView(context).apply {
            background = context.roundRect(99, 0xFF111111.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setPadding(context.dp(16), context.dp(9), context.dp(16), context.dp(9))
            visibility = GONE
        }
        container.addView(blockedBanner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = context.dp(10)
        })

        // Page-only mode intentionally has no floating back pill or overlay.
        // Android Back and a double tap on the page restore normal controls.

        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
    }

    /* ------------------------------------------------------------------ */
    /*  address-bar editing                                                */
    /* ------------------------------------------------------------------ */

    private fun beginEditing() {
        if (editing) return
        editing = true
        onAddressFocused?.invoke()
        val index = addressPill.indexOfChild(addressLabel)
        addressPill.removeView(addressLabel)

        val input = EditText(context).apply {
            setTextColor(0xFF1A1A1A.toInt())
            setHintTextColor(0xFF5B5B5B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = true
            maxLines = 1
            background = null
            setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = "Search or type a URL"
            setText(addressLabel.text)
            setSelection(text?.length ?: 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    commit(this.text?.toString().orEmpty())
                    true
                } else false
            }
        }
        addressPill.addView(input, index, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        input.requestFocus()
        UiKeys.showKeyboard(input)
    }

    private fun commit(raw: String) {
        editing = false
        UiKeys.hideKeyboard(this)
        val index = addressPill.indexOfChild(findEditText())
        findEditText()?.let { addressPill.removeView(it) }
        addressPill.addView(addressLabel, index, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        val trimmed = raw.trim()
        addressLabel.text = trimmed.ifEmpty { "Search or type a URL" }
        if (trimmed.isNotEmpty()) onSubmitAddress?.invoke(trimmed)
    }

    private fun findEditText(): EditText? {
        for (i in 0 until addressPill.childCount) {
            val c = addressPill.getChildAt(i)
            if (c is EditText) return c
        }
        return null
    }

    /** Called when full screen ends so a stuck editor does not survive. */
    fun cancelEditing() {
        if (!editing) return
        editing = false
        UiKeys.hideKeyboard(this)
        val index = addressPill.indexOfChild(findEditText())
        findEditText()?.let { addressPill.removeView(it) }
        addressPill.addView(addressLabel, index, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 1f })
    }

    /* ------------------------------------------------------------------ */
    /*  state                                                              */
    /* ------------------------------------------------------------------ */

    fun syncTo(tab: Tab?) {
        if (tab == null) {
            addressLabel.text = "Search or type a URL"
            blockedChip.visibility = GONE
            lockIcon.icon(R.drawable.ic_search, 0xFF7A7A7A.toInt())
            btnBack.alpha = 0.35f
            btnForward.alpha = 0.35f
            return
        }
        if (!editing) addressLabel.text = UrlBar.shortLabel(tab.url)
        lockIcon.icon(
            if (tab.isSecure) R.drawable.ic_lock else R.drawable.ic_eyeoff,
            if (tab.isSecure) 0xFF7A7A7A.toInt() else 0xFF9C9C9C.toInt()
        )
        val total = tab.totalBlocked
        if (total > 0) {
            blockedChip.visibility = VISIBLE
            blockedChip.text = "$total blocked"
        } else {
            blockedChip.visibility = GONE
        }
        btnBack.alpha = if (tab.canGoBack) 1f else 0.35f
        btnForward.alpha = if (tab.canGoForward) 1f else 0.35f

        blockedBanner.text = context.resources.getQuantityString(
            R.plurals.blocked_fmt, tab.blockedAds, tab.blockedAds, tab.blockedTrackers
        )
        blockedBanner.visibility = if (tab.totalBlocked > 0) VISIBLE else GONE
    }

    fun setProgress(progress: Int) {
        val p = progress.coerceIn(0, 100)
        if (p > 0) {
            removeCallbacks(hideProgress)
            progressTrack.alpha = 1f
            progressTrack.visibility = VISIBLE
        }
        val w = progressTrack.width
        if (w > 0) {
            progressFill.layoutParams = (progressFill.layoutParams as FrameLayout.LayoutParams).apply {
                width = (w * p / 100f).toInt()
            }
            progressFill.requestLayout()
        } else {
            pendingProgress = p
        }
        if (p >= 100) postDelayed(hideProgress, 350)
    }

    private var pendingProgress = 0
    private val hideProgress = Runnable {
        progressTrack.animate().alpha(0f).setDuration(200).withEndAction {
            progressTrack.visibility = INVISIBLE
            progressTrack.alpha = 1f
        }.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && pendingProgress > 0) setProgress(pendingProgress)
    }

    /**
     * Page-only mode leaves the active web page as the sole visible view: no
     * address bar, divider, loading line, blocked badge, or floating control.
     */
    fun setFullScreen(on: Boolean) {
        pageOnly = on
        webBar.visibility = if (on) GONE else VISIBLE
        webDivider.visibility = if (on) GONE else VISIBLE
        progressTrack.visibility = if (on) GONE else INVISIBLE
        if (on) {
            blockedBanner.visibility = GONE
            cancelEditing()
        }
    }

    /** Places only the active tab's WebView under the browser overlays. */
    fun attachWebView(webView: WebView?) {
        if (attachedWebView === webView) return
        attachedWebView?.let { old ->
            if (old.parent === container) container.removeView(old)
        }
        attachedWebView = webView
        if (webView == null) return

        // A WebView can only have one parent. This also makes renderer recovery
        // safe when Android hands TabManager a replacement WebView instance.
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.setOnTouchListener { _, event ->
            if (pageOnly) pageGestureDetector.onTouchEvent(event)
            false // observe the double tap; never consume normal page gestures
        }
        container.addView(webView, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    /** Detach, but do not destroy, the current tab while another app screen is shown. */
    fun detachWebView() {
        attachedWebView?.let { view ->
            if (view.parent === container) container.removeView(view)
        }
        attachedWebView = null
    }

    /* ------------------------------------------------------------------ */
    /*  helpers                                                            */
    /* ------------------------------------------------------------------ */

    private fun webButton(iconRes: Int, contentDesc: Int, onClick: () -> Unit) =
        ImageView(context).apply {
            icon(iconRes, 0xFF4A4A4A.toInt())
            this.contentDescription = context.getString(contentDesc)
            background = context.roundRect(10)
            isClickable = true
            isFocusable = true
            val pad = context.dp(7)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }

    private fun barButtonParams() = LayoutParams(context.dp(34), context.dp(34)).apply {
        rightMargin = context.dp(8)
    }

}

/** Small IME helper so the address bar behaves like a real one. */
object UiKeys {
    fun showKeyboard(view: View) {
        view.postDelayed({
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 60)
    }

    fun hideKeyboard(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }
}
