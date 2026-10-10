package com.minimal.browser.phone

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.minimal.browser.ui.dp
import com.minimal.browser.ui.icon
import com.minimal.browser.ui.roundRect

/**
 * Visual language of the phone shell. Warm paper, ink-black type, and one
 * vermilion accent that is reserved for the thing that matters most: the
 * download action and live progress. No gradients, no drop-shadow cards.
 */
object Palette {
    val PAPER: Int = 0xFFF3F0E8.toInt()
    val PAPER_DEEP: Int = 0xFFE9E5DA.toInt()
    val CARD: Int = 0xFFFFFFFF.toInt()
    val INK: Int = 0xFF0C0C0E.toInt()
    val INK_SOFT: Int = 0xFF5B5B63.toInt()
    val INK_FAINT: Int = 0xFF9A9AA2.toInt()
    val LINE: Int = 0x1F0C0C0E
    val ACCENT: Int = 0xFFFF5A36.toInt()
    val ACCENT_SOFT: Int = 0x26FF5A36
    val WHITE: Int = 0xFFFFFFFF.toInt()
    val DANGER: Int = 0xFFD2232A.toInt()
}

object Type {
    val display: Typeface by lazy { Typeface.create("sans-serif-black", Typeface.NORMAL) }
    val title: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    val body: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
}

fun Context.label(text: String, size: Float = 14f, color: Int = Palette.INK, font: Typeface = Type.body): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = font
        includeFontPadding = false
    }

fun Context.icon(res: Int, tint: Int, desc: String? = null): ImageView =
    ImageView(this).apply {
        icon(res, tint)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = desc
    }

/** Subtle spring on press. Returns false so the normal click still fires. */
fun View.springPress(pressed: Float = 0.92f): View {
    isClickable = true
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().cancel()
                v.animate().scaleX(pressed).scaleY(pressed).setDuration(90).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().cancel()
                v.animate().scaleX(1f).scaleY(1f).setDuration(420)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
            }
        }
        false
    }
    return this
}

/** Fade and rise a view into place. Used for every screen change and list row. */
fun View.enter(delay: Long = 0L) {
    val lift = context.dp(14).toFloat()
    animate().cancel()
    alpha = 0f
    translationY = lift
    animate().alpha(1f).translationY(0f).setStartDelay(delay)
        .setDuration(340).setInterpolator(DecelerateInterpolator(1.8f)).start()
}

/**
 * Circular progress ring with an optional centre glyph. Animates smoothly
 * between values so per-file progress feels continuous, not stepped.
 */
class RingView(context: Context) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3f)
        color = Palette.LINE
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3f)
        strokeCap = Paint.Cap.ROUND
        color = Palette.ACCENT
    }
    private val oval = RectF()
    private var value = 0f
    private var animator: ValueAnimator? = null

    fun setProgress(fraction: Float, animate: Boolean = true) {
        val target = fraction.coerceIn(0f, 1f)
        animator?.cancel()
        if (!animate) {
            value = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(value, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                value = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val inset = arc.strokeWidth / 2f
        oval.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(oval, 0f, 360f, false, track)
        canvas.drawArc(oval, -90f, 360f * value, false, arc)
    }
}

/**
 * A bottom sheet that rises over the app with a dimmed scrim. Dismissing it
 * (tap the scrim, press back, or call [dismiss]) plays the same motion in reverse.
 */
class SheetHost(private val activity: Activity, private val root: FrameLayout) {

    private var scrim: View? = null
    private var panel: FrameLayout? = null
    private var onClosed: (() -> Unit)? = null

    val isShowing: Boolean get() = panel != null

    fun show(content: View, heightFraction: Float = 0.86f, onDismissed: (() -> Unit)? = null) {
        if (isShowing) dismiss()
        onClosed = onDismissed
        val height = (activity.resources.displayMetrics.heightPixels * heightFraction).toInt()

        val scrimView = View(activity).apply {
            setBackgroundColor(Palette.INK)
            alpha = 0f
            setOnClickListener { dismiss() }
        }
        val sheet = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                setColor(Palette.PAPER)
                val r = activity.dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            isClickable = true
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            translationY = height.toFloat()
        }

        root.addView(scrimView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(sheet, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM
        ))
        scrim = scrimView
        panel = sheet

        scrimView.animate().alpha(0.42f).setDuration(260).start()
        sheet.animate().translationY(0f).setDuration(420)
            .setInterpolator(OvershootInterpolator(0.9f)).start()
    }

    fun dismiss() {
        val sheet = panel ?: return
        val sc = scrim
        panel = null
        scrim = null
        val height = sheet.height.coerceAtLeast(1).toFloat()
        sc?.animate()?.alpha(0f)?.setDuration(220)?.start()
        sheet.animate().translationY(height).setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                root.removeView(sheet)
                if (sc != null) root.removeView(sc)
                onClosed?.invoke()
                onClosed = null
            }
            .start()
    }
}
