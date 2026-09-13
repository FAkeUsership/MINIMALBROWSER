package com.minimal.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import android.content.res.ColorStateList

/* -------------------------------------------------------------- */
/*  dp / px                                                        */
/* -------------------------------------------------------------- */

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

fun View.dp(value: Int): Int = context.dp(value)
fun View.dp(value: Float): Float = context.dp(value)

/* -------------------------------------------------------------- */
/*  shape helpers (everything in this app is a rounded rectangle)  */
/* -------------------------------------------------------------- */

fun Context.roundRect(
    radiusDp: Int,
    @ColorInt fill: Int = Color.TRANSPARENT,
    @ColorInt stroke: Int = Color.TRANSPARENT,
    strokeWidthDp: Int = 1
): GradientDrawable {
    val d = GradientDrawable()
    d.shape = GradientDrawable.RECTANGLE
    d.cornerRadius = dp(radiusDp.toFloat())
    d.setColor(fill)
    if (stroke != Color.TRANSPARENT) d.setStroke(dp(strokeWidthDp), stroke)
    return d
}

fun Context.pillWhite(): Drawable = roundRect(99, Color.WHITE)

fun Context.circle(@ColorInt fill: Int): Drawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(fill)
}

/* -------------------------------------------------------------- */
/*  colours straight from the HTML palette                         */
/* -------------------------------------------------------------- */

object Ink {
    const val SHELL = 0xFF0A0A0A.toInt()
    const val SHELL2 = 0xFF101010.toInt()
    const val PANEL = 0xFF161616.toInt()
    const val PANEL2 = 0xFF1D1D1D.toInt()
    const val EDGE = 0xFF262626.toInt()
    const val EDGE2 = 0xFF333333.toInt()
    const val TEXT = 0xFFF2F2F2.toInt()
    const val MUTED = 0xFF9C9C9C.toInt()
    const val DIM = 0xFF6A6A6A.toInt()
    const val PLINE = 0xFFE3E3E3.toInt()
    const val SCRIM = 0x99000000.toInt()
    const val WHITE = Color.WHITE
    const val BLACK = Color.BLACK
}

/* -------------------------------------------------------------- */
/*  icons                                                          */
/* -------------------------------------------------------------- */

fun ImageView.icon(@DrawableRes res: Int, @ColorInt tint: Int) {
    setImageResource(res)
    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(tint))
}

fun Context.tintedIcon(@DrawableRes res: Int, @ColorInt tint: Int): Drawable? =
    ContextCompat.getDrawable(this, res)?.mutate()?.also {
        it.setTint(tint)
    }

/* -------------------------------------------------------------- */
/*  layout shortcuts                                               */
/* -------------------------------------------------------------- */

fun ViewGroup.MarginLayoutParams.setMarginsAll(v: Int) {
    setMargins(v, v, v, v)
}

fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)
