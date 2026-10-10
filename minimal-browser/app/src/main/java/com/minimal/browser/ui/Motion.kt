package com.minimal.browser.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Shared motion language for the media downloader. Everything here is short,
 * physical, and interruptible: a tap should always feel answered, and nothing
 * should block the person from acting while it plays.
 */
object Motion {

    const val ENTER_MS = 300L

    /** Rows arrive one after another, capped so long lists do not wait forever. */
    fun staggerDelay(index: Int): Long = minOf(index, 12) * 35L

    /** Fade, rise, and settle a view into place. */
    fun springIn(view: View, delay: Long = 0L) {
        val d = view.context.dp(12).toFloat()
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = d
        view.scaleX = 0.97f
        view.scaleY = 0.97f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delay)
            .setDuration(ENTER_MS)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /** Quick press-in, then a spring back. Gives every tap a tactile response. */
    fun press(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(70)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(420)
                    .setInterpolator(OvershootInterpolator(2.4f))
                    .start()
            }
            .start()
    }

    /** Horizontal shake for a failed item. */
    fun shake(view: View) {
        val d = view.context.dp(1).toFloat()
        ObjectAnimator.ofFloat(
            view, View.TRANSLATION_X,
            0f, -10 * d, 9 * d, -7 * d, 5 * d, -2 * d, 0f
        ).apply {
            duration = 380
            start()
        }
    }

    /** Pop a view in from nothing, with a small overshoot. */
    fun pop(view: View) {
        view.animate().cancel()
        view.scaleX = 0f
        view.scaleY = 0f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(440)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
    }

    /** Fade out, then hide, so layout collapses only after the motion finishes. */
    fun fadeOutGone(view: View) {
        if (view.visibility == View.GONE) return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
            }
            .start()
    }

    /** Show a view with a short fade. */
    fun fadeIn(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(200).start()
    }
}

/**
 * A soft radar sweep for the scanning state. It draws three rings that expand
 * and fade out, so the person can see that work is in progress.
 */
class RadarView(context: Context) : View(context) {

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
        color = 0xFF111111.toInt()
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF111111.toInt()
    }
    private var phase = 0f
    private var running = true

    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setRunning(on: Boolean) {
        running = on
        if (on) {
            if (isAttachedToWindow && !animator.isRunning) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running && !animator.isRunning) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = minOf(width, height) / 2f - ring.strokeWidth
        for (i in 0 until 3) {
            val p = (phase + i / 3f) % 1f
            ring.alpha = ((1f - p) * 255f).toInt()
            canvas.drawCircle(cx, cy, maxRadius * p, ring)
        }
        canvas.drawCircle(cx, cy, maxRadius * 0.16f, core)
    }
}

/**
 * A black disc with a white tick that draws itself in. It marks a finished file,
 * so the moment of success is visible.
 */
class CheckMarkView(context: Context) : View(context) {

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF111111.toInt()
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private var progress = 1f

    /** Draw the tick from nothing to full, starting once the view is laid out. */
    fun animateIn() {
        progress = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            startDelay = 60
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f, disc)

        val path = Path().apply {
            moveTo(w * 0.27f, h * 0.52f)
            lineTo(w * 0.44f, h * 0.68f)
            lineTo(w * 0.74f, h * 0.36f)
        }
        val measure = PathMeasure(path, false)
        val partial = Path()
        measure.getSegment(0f, measure.length * progress, partial, true)
        canvas.drawPath(partial, tick)
    }
}
