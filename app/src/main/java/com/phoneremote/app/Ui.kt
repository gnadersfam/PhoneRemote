package com.phoneremote.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH: Int = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT

/** The colours used everywhere in the app. */
object C {
    val BG: Int = Color.parseColor("#0F1115")
    val CARD: Int = Color.parseColor("#1A1D24")
    val CARD2: Int = Color.parseColor("#262B35")
    val LINE: Int = Color.parseColor("#2F3541")
    val TEXT: Int = Color.parseColor("#F2F4F7")
    val MUTED: Int = Color.parseColor("#A3AAB8")
    val ACCENT: Int = Color.parseColor("#4FD1C5")
    val DANGER: Int = Color.parseColor("#FF8A8A")
    val DANGER_BG: Int = Color.parseColor("#3A2326")
    val GREEN: Int = Color.parseColor("#4ADE80")
    val ON_ACCENT: Int = Color.parseColor("#0F1115")
}

fun dp(ctx: Context, v: Int): Int {
    return (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}

fun rounded(ctx: Context, color: Int, radiusDp: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(ctx, radiusDp).toFloat()
    return g
}

fun withRipple(ctx: Context, color: Int, radiusDp: Int): RippleDrawable {
    val content = rounded(ctx, color, radiusDp)
    val mask = rounded(ctx, Color.WHITE, radiusDp)
    return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
}

fun linp(w: Int, h: Int, weight: Float, l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(l, t, r, b)
    return p
}

fun linp(w: Int, h: Int): LinearLayout.LayoutParams {
    return LinearLayout.LayoutParams(w, h)
}

fun label(ctx: Context, text: String, sp: Float, color: Int, bold: Boolean): TextView {
    val t = TextView(ctx)
    t.text = text
    t.textSize = sp
    t.setTextColor(color)
    if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
    return t
}

/** A rounded button made from a TextView. */
fun pill(ctx: Context, text: String, bg: Int, fg: Int, sp: Float, radiusDp: Int): TextView {
    val t = label(ctx, text, sp, fg, true)
    t.gravity = Gravity.CENTER
    t.background = withRipple(ctx, bg, radiusDp)
    t.isClickable = true
    t.isFocusable = true
    return t
}

fun circleButton(ctx: Context, iconRes: Int, sizeDp: Int, iconDp: Int, bg: Int, tint: Int, desc: String): ImageView {
    val v = ImageView(ctx)
    v.setImageResource(iconRes)
    v.imageTintList = ColorStateList.valueOf(tint)
    val pad: Int = dp(ctx, (sizeDp - iconDp) / 2)
    v.setPadding(pad, pad, pad, pad)
    val shape = GradientDrawable()
    shape.shape = GradientDrawable.OVAL
    shape.setColor(bg)
    val mask = GradientDrawable()
    mask.shape = GradientDrawable.OVAL
    mask.setColor(Color.WHITE)
    v.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, mask)
    v.contentDescription = desc
    v.isClickable = true
    v.isFocusable = true
    return v
}

fun plainIcon(ctx: Context, iconRes: Int, tint: Int): ImageView {
    val v = ImageView(ctx)
    v.setImageResource(iconRes)
    v.imageTintList = ColorStateList.valueOf(tint)
    return v
}
