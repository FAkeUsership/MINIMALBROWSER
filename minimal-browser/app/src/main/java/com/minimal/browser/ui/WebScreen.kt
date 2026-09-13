package com.minimal.browser.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.minimal.browser.R
import com.minimal.browser.Tab
import com.minimal.browser.UrlBar
import org.mozilla.geckoview.GeckoView

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
    private val privateChip: TextView
    val blockedChip: TextView
    private val webDivider: View
    val progressTrack: FrameLayout
    private val progressFill: View
    val container: FrameLayout
    private val startPanel: BrowserStartPanel
    private var attachedGeckoView: GeckoView? = null
    val blockedBanner: TextView

    private val btnBack: ImageView
    private val btnForward: ImageView
    private val btnReload: ImageView
    private var editing = false
    private var pageOnly = false
    private var blankTab = true
    private var navigationPending = false
    private var synchronizedTabId: String? = null
    private var fullAddress = ""

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
                // Do not mutate the hierarchy while GeckoView is dispatching the
                // same motion event. Restore controls on the next main-loop turn.
                this@WebScreen.post { if (pageOnly) onPageDoubleTap?.invoke() }
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

        privateChip = TextView(context).apply {
            text = "PRIVATE"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = context.roundRect(6, 0xFF363636.toInt())
            setPadding(context.dp(6), context.dp(3), context.dp(6), context.dp(3))
            visibility = GONE
        }
        addressPill.addView(privateChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = context.dp(8)
        })

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

        // The bundled GeckoView surface is attached here only after a real tab
        // and a visible Web screen exist. Home and Tabs never own a hidden
        // native compositor surface.
        //
        // Gecko's truly blank document is white. Keep a purposeful native start
        // surface above it until the user opens a real URL, then leave every
        // rendered web page entirely to GeckoView.
        startPanel = BrowserStartPanel(context).apply {
            onSearch = { beginEditing() }
        }
        container.addView(startPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

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
        updateStartPanel()
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
            UiKeys.configureTextInput(this)
            hint = ADDRESS_PLACEHOLDER
            // The visible idle label is a hint, not text the person should have
            // to erase. Keep the full URL separately too: the compact chrome
            // label can be shortened without truncating what the person edits.
            setText(fullAddress)
            setSelection(text?.length ?: 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    commit(this.text?.toString().orEmpty())
                    true
                } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP && UiKeys.isEnterKey(keyCode)) {
                    commit(this.text?.toString().orEmpty())
                    true
                } else {
                    false
                }
            }
        }
        addressPill.addView(input, index, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        input.requestFocus()
        UiKeys.showKeyboard(input)
    }

    private fun commit(raw: String) {
        // A physical Enter can reach both the IME editor action and a key
        // listener. Complete the navigation once, never twice.
        if (!editing) return
        editing = false
        UiKeys.hideKeyboard(this)
        val index = addressPill.indexOfChild(findEditText())
        findEditText()?.let { addressPill.removeView(it) }
        addressPill.addView(addressLabel, index, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        val trimmed = raw.trim()
        fullAddress = trimmed
        addressLabel.text = trimmed.ifEmpty { ADDRESS_PLACEHOLDER }
        navigationPending = trimmed.isNotEmpty()
        if (trimmed.isEmpty()) updateStartPanel() else startPanel.visibility = GONE
        if (trimmed.isNotEmpty()) onSubmitAddress?.invoke(trimmed)
    }

    private fun findEditText(): EditText? {
        for (i in 0 until addressPill.childCount) {
            val c = addressPill.getChildAt(i)
            if (c is EditText) return c
        }
        return null
    }

    /** Restore a blank-tab start surface if navigation was rejected before Gecko started it. */
    fun cancelPendingNavigation() {
        navigationPending = false
        updateStartPanel()
    }

    /** Opens the omnibox for Ctrl+L/F6 and the native blank-tab search card. */
    fun focusAddress() = beginEditing()

    /** @return true when Escape closed an active address edit. */
    fun cancelEditingIfActive(): Boolean {
        if (!editing) return false
        cancelEditing()
        return true
    }

    /** Re-evaluate IME behavior after a hardware keyboard is plugged/unplugged. */
    fun refreshInputMode() {
        findEditText()?.let { UiKeys.configureTextInput(it) }
    }

    /** Called when full screen ends so a stuck editor does not survive. */
    fun cancelEditing() {
        if (!editing) return
        editing = false
        UiKeys.hideKeyboard(this)
        val index = addressPill.indexOfChild(findEditText())
        findEditText()?.let { addressPill.removeView(it) }
        addressPill.addView(addressLabel, index, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        updateStartPanel()
    }

    /* ------------------------------------------------------------------ */
    /*  state                                                              */
    /* ------------------------------------------------------------------ */

    fun syncTo(tab: Tab?) {
        if (synchronizedTabId != tab?.id) {
            // A pending load belongs only to the tab that initiated it. Never
            // hide the start surface when the person switches to a new blank tab.
            synchronizedTabId = tab?.id
            navigationPending = false
        }
        val documentIsBlank = tab == null || tab.url.isBlank() || tab.url.equals("about:blank", ignoreCase = true)
        if (!documentIsBlank) navigationPending = false
        blankTab = documentIsBlank && !navigationPending
        if (!editing && !navigationPending) fullAddress = if (blankTab) "" else tab?.url.orEmpty()
        startPanel.sync(tab?.private == true)
        updateStartPanel()
        if (tab == null) {
            addressLabel.text = ADDRESS_PLACEHOLDER
            privateChip.visibility = GONE
            blockedChip.visibility = GONE
            blockedBanner.visibility = GONE
            lockIcon.icon(R.drawable.ic_search, 0xFF7A7A7A.toInt())
            btnBack.alpha = 0.35f
            btnForward.alpha = 0.35f
            return
        }
        if (!editing) addressLabel.text = if (blankTab) ADDRESS_PLACEHOLDER else UrlBar.shortLabel(tab.url)
        privateChip.visibility = if (tab.private) VISIBLE else GONE
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
        // Gecko delegates can update while page-only mode is active. Never let
        // a shield badge become browser chrome over the clean page.
        blockedBanner.visibility = if (!pageOnly && tab.totalBlocked > 0) VISIBLE else GONE
    }

    private fun updateStartPanel() {
        startPanel.visibility = if (!pageOnly && blankTab && !editing) VISIBLE else GONE
    }

    /**
     * Gecko can deliver several progress values within one display interval.
     * Keep only the newest value and lay out the thin progress line at most
     * once per frame, rather than requesting a layout for every callback.
     */
    fun setProgress(progress: Int) {
        pendingProgress = progress.coerceIn(0, 100)
        if (pageOnly) return
        scheduleProgressRender()
    }

    private var pendingProgress = 0
    private var progressRenderScheduled = false
    private val progressRenderRunnable = Runnable {
        progressRenderScheduled = false
        if (!pageOnly) renderProgress(pendingProgress)
    }

    private fun scheduleProgressRender() {
        if (progressRenderScheduled) return
        progressRenderScheduled = true
        postOnAnimation(progressRenderRunnable)
    }

    private fun renderProgress(p: Int) {
        if (p > 0) {
            removeCallbacks(hideProgress)
            progressTrack.animate().cancel()
            progressTrack.alpha = 1f
            progressTrack.visibility = VISIBLE
        }
        val w = progressTrack.width
        if (w > 0) {
            progressFill.layoutParams = (progressFill.layoutParams as FrameLayout.LayoutParams).apply {
                width = (w * p / 100f).toInt()
            }
            progressFill.requestLayout()
        }
        if (p >= 100) postDelayed(hideProgress, 350)
    }

    private val hideProgress = Runnable {
        progressTrack.animate().alpha(0f).setDuration(200).withEndAction {
            progressTrack.visibility = INVISIBLE
            progressTrack.alpha = 1f
        }.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!pageOnly && w > 0 && pendingProgress > 0) scheduleProgressRender()
    }

    /**
     * Called from GeckoView.dispatchTouchEvent so page gestures are observed
     * even when Gecko's internal native surface consumes the touch stream. The
     * event is never consumed here; Gecko still receives normal page interaction.
     */
    fun observePageTouch(event: MotionEvent) {
        if (pageOnly) pageGestureDetector.onTouchEvent(event)
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
            removeCallbacks(progressRenderRunnable)
            removeCallbacks(hideProgress)
            progressTrack.animate().cancel()
            progressRenderScheduled = false
            blockedBanner.visibility = GONE
            cancelEditing()
        }
        updateStartPanel()
    }

    /** Places only the active bundled-engine GeckoView under browser overlays. */
    fun attachGeckoView(geckoView: GeckoView?) {
        if (attachedGeckoView === geckoView) return
        attachedGeckoView?.let { old ->
            if (old.parent === container) container.removeView(old)
        }
        attachedGeckoView = geckoView
        if (geckoView == null) return

        // A GeckoView can only have one parent. Its session stays owned by
        // TabManager so this UI move never opens or closes a renderer.
        (geckoView.parent as? ViewGroup)?.removeView(geckoView)
        container.addView(geckoView, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    /** Detach, but do not destroy, the current tab while another app screen is shown. */
    fun detachGeckoView() {
        attachedGeckoView?.let { view ->
            if (view.parent === container) container.removeView(view)
        }
        attachedGeckoView = null
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

    private companion object {
        const val ADDRESS_PLACEHOLDER = "Search or type a URL"
    }
}

/**
 * IME and keyboard helpers shared by the native browser chrome.
 *
 * Android normally decides whether to show its software keyboard for a physical
 * keyboard. The old shell overrode that decision and always forced the IME
 * after focusing the omnibox, which made a docked/BT keyboard feel broken.
 */
object UiKeys {
    fun hasHardwareKeyboard(context: Context): Boolean {
        val configured = context.resources.configuration.keyboard
        if (configured == Configuration.KEYBOARD_QWERTY || configured == Configuration.KEYBOARD_12KEY) {
            return true
        }
        return InputDevice.getDeviceIds().any { id ->
            val device = InputDevice.getDevice(id) ?: return@any false
            !device.isVirtual &&
                device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE &&
                (device.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
        }
    }

    fun configureTextInput(input: EditText) {
        input.showSoftInputOnFocus = !hasHardwareKeyboard(input.context)
    }

    fun isEnterKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    fun showKeyboard(view: View) {
        if (hasHardwareKeyboard(view.context)) return
        view.postDelayed({
            if (hasHardwareKeyboard(view.context)) return@postDelayed
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 60)
    }

    fun hideKeyboard(view: View, clearFocus: Boolean = true) {
        // InsetsController is the current API; the InputMethodManager fallback
        // covers older OEM implementations and views not yet fully attached.
        ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        if (clearFocus) view.clearFocus()
    }
}
