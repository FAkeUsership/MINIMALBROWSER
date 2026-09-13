package com.minimal.browser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.minimal.browser.Prefs
import com.minimal.browser.R

/**
 * One button in the left rail — port of `.rbtn` from the HTML.
 *
 * Tap = navigate. On the Web button, *holding* it for exactly five seconds
 * fills the progress ring and enters page-only mode.
 */
class RailButton(
    context: Context,
    @DrawableRes iconRes: Int,
    label: String,
    val supportsHold: Boolean = false
) : LinearLayout(context) {

    var onTap: (() -> Unit)? = null
    var onHoldProgressStart: (() -> Unit)? = null
    var onHoldComplete: (() -> Unit)? = null

    private val icon = ImageView(context)
    private val text = TextView(context)

    private var active = false
    private var animator: ValueAnimator? = null
    private var holdProgress = 0f
    private var showingRing = false
    private var holdFired = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val ringRect = RectF()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true

        icon.setImageResource(iconRes)
        addView(icon, LayoutParams(context.dp(20), context.dp(20)))

        text.text = label
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        text.gravity = Gravity.CENTER
        (text.layoutParams as? LayoutParams)?.let {}
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(2)
        })

        setWillNotDraw(false)
        applyStyle()
    }

    /* ---------------- styling (.rbtn / .rbtn.active) ---------------- */

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        applyStyle()
    }

    fun isActive() = active

    private fun applyStyle() {
        val fg = if (active) Ink.BLACK else Ink.MUTED
        background = if (active) context.roundRect(12, Color.WHITE) else null
        icon.icon(iconDrawable(), fg)
        text.setTextColor(fg)
        text.setTypeface(text.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    @DrawableRes
    private var iconResCached: Int = 0

    private fun iconDrawable(): Int =
        if (iconResCached != 0) iconResCached else R.drawable.ic_globe

    /** Remember the icon so re-tinting on active/inactive is cheap. */
    fun rememberIcon(@DrawableRes res: Int) {
        iconResCached = res
        applyStyle()
    }

    /* ---------------- hold-to-full-screen ring ---------------- */

    private fun startHold() {
        if (!supportsHold) return
        showingRing = true
        holdFired = false
        holdProgress = 0f
        onHoldProgressStart?.invoke()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Prefs.holdMillis
            addUpdateListener {
                holdProgress = it.animatedFraction
                invalidate()
            }
            start()
        }
        animator?.doOnEndGuarded {
            showingRing = false
            holdFired = true
            holdProgress = 0f
            invalidate()
            vibrate()
            onHoldComplete?.invoke()
        }
    }

    private fun cancelHold() {
        animator?.cancel()
        animator = null
        if (showingRing) {
            showingRing = false
            holdProgress = 0f
            invalidate()
        }
    }

    private fun vibrate() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(18)
            }
        } catch (_: Exception) {
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showingRing || holdProgress <= 0f) return
        // 54dp ring centred on the button, starting at 12 o'clock (HTML rotates -90deg)
        val r = context.dp(27f)
        val cx = width / 2f
        val cy = height / 2f
        ringRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.save()
        canvas.rotate(-90f, cx, cy)
        canvas.drawArc(ringRect, 0f, 360f * holdProgress, false, ringPaint)
        canvas.restore()
    }

    /* ---------------- touch handling ---------------- */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startHold()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (showingRing) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy > touchSlop * touchSlop * 4f) {
                        cancelHold()
                        return true
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val completedHold = holdFired
                cancelHold()
                // A short press must still be an ordinary Web-button tap. Only
                // suppress it after the precise five-second hold has completed,
                // otherwise lifting after the hold would immediately undo it.
                if (!completedHold) performClick()
                holdFired = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}

/** Small helper so a cancelled animator does not fire its end action. */
private fun ValueAnimator.doOnEndGuarded(action: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        private var cancelled = false
        override fun onAnimationCancel(animation: android.animation.Animator) {
            cancelled = true
        }

        override fun onAnimationEnd(animation: android.animation.Animator) {
            if (!cancelled) action()
        }
    })
}
