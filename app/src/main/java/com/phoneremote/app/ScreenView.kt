package com.phoneremote.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Shows the picture of the PC screen and turns finger touches into mouse actions.
 *
 * One finger:   slide = the pointer follows your finger, tap = click,
 *               hold then slide = drag (hold the button down)
 * Two fingers:  pinch = zoom, slide = move around when zoomed, tap = right-click
 * "Scroll" on:  one finger sliding scrolls instead of moving the pointer
 *
 * Places on the screen are given as fractions from 0 to 1 (left to right, top to bottom).
 */
class ScreenView(ctx: Context, private val listener: Listener) : View(ctx) {

    interface Listener {
        fun onPointer(fx: Float, fy: Float)
        fun onTap(fx: Float, fy: Float, right: Boolean)
        fun onHoldStart(fx: Float, fy: Float)
        fun onHoldEnd(fx: Float, fy: Float)
        fun onWheel(wheel: Int)
    }

    @Volatile
    var frame: Bitmap? = null

    @Volatile
    var message: String = ""

    var scrollMode: Boolean = false

    private val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slop: Float = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    private var zoom: Float = 1f
    private var tx: Float = 0f
    private var ty: Float = 0f

    private val modeNone = 0
    private val modeUndecided = 1
    private val modePointer = 2
    private val modeHold = 3
    private val modeScroll = 4
    private val modeZoom = 5
    private var mode: Int = 0

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTime: Long = 0L
    private var lastY: Float = 0f
    private var lastFx: Float = 0f
    private var lastFy: Float = 0f
    private var scrollAcc: Float = 0f

    private var zoomMoved: Boolean = false
    private var twoDownTime: Long = 0L
    private var startDist: Float = 0f
    private var lastDist: Float = 0f
    private var startMidX: Float = 0f
    private var startMidY: Float = 0f
    private var lastMidX: Float = 0f
    private var lastMidY: Float = 0f

    init {
        textPaint.color = Color.WHITE
        textPaint.textSize = dp(ctx, 16).toFloat()
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.setShadowLayer(dp(ctx, 4).toFloat(), 0f, 0f, Color.BLACK)
    }

    fun resetZoom() {
        zoom = 1f
        invalidate()
    }

    // ------------------------------------------------------------------ drawing

    private fun scaleFor(bmp: Bitmap): Float {
        val fit: Float = Math.min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        return fit * zoom
    }

    /** Keeps the picture from being dragged off the screen, and centres it when it is small. */
    private fun clampPan(bmp: Bitmap) {
        val s: Float = scaleFor(bmp)
        val cw: Float = bmp.width * s
        val ch: Float = bmp.height * s
        val vw: Float = width.toFloat()
        val vh: Float = height.toFloat()
        tx = if (cw <= vw) (vw - cw) / 2f else tx.coerceIn(vw - cw, 0f)
        ty = if (ch <= vh) (vh - ch) / 2f else ty.coerceIn(vh - ch, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bmp: Bitmap? = frame
        if (bmp != null && width > 0 && height > 0) {
            clampPan(bmp)
            val s: Float = scaleFor(bmp)
            canvas.save()
            canvas.translate(tx, ty)
            canvas.scale(s, s)
            canvas.drawBitmap(bmp, 0f, 0f, picturePaint)
            canvas.restore()
        }
        val msg: String = message
        if (msg.isNotEmpty()) {
            canvas.drawText(msg, width / 2f, height / 2f, textPaint)
        }
    }

    // ------------------------------------------------------------------ touch

    /** Where on the PC screen (0 to 1) a touch is. With strict on, touches outside the picture give null. */
    private fun toFraction(x: Float, y: Float, strict: Boolean): FloatArray? {
        val bmp: Bitmap = frame ?: return null
        if (width <= 0 || height <= 0) return null
        val s: Float = scaleFor(bmp)
        val fx: Float = (x - tx) / s / bmp.width
        val fy: Float = (y - ty) / s / bmp.height
        if (strict && (fx < 0f || fx > 1f || fy < 0f || fy > 1f)) return null
        return floatArrayOf(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
    }

    private fun follow(x: Float, y: Float) {
        val f: FloatArray = toFraction(x, y, false) ?: return
        lastFx = f[0]
        lastFy = f[1]
        listener.onPointer(f[0], f[1])
    }

    private val longPress = Runnable {
        if (mode == modeUndecided) {
            val f: FloatArray? = toFraction(downX, downY, true)
            if (f != null) {
                mode = modeHold
                lastFx = f[0]
                lastFy = f[1]
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener.onHoldStart(f[0], f[1])
            }
        }
    }

    private fun endHold() {
        listener.onHoldEnd(lastFx, lastFy)
    }

    private fun distance(e: MotionEvent): Float {
        return Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = modeUndecided
                downX = e.x
                downY = e.y
                downTime = e.eventTime
                lastY = e.y
                scrollAcc = 0f
                postDelayed(longPress, 420L)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    removeCallbacks(longPress)
                    if (mode == modeHold) endHold()
                    mode = modeZoom
                    zoomMoved = false
                    twoDownTime = e.eventTime
                    lastDist = distance(e)
                    startDist = lastDist
                    lastMidX = (e.getX(0) + e.getX(1)) / 2f
                    lastMidY = (e.getY(0) + e.getY(1)) / 2f
                    startMidX = lastMidX
                    startMidY = lastMidY
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == modeUndecided) {
                    val far: Double = Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble())
                    if (far > slop) {
                        removeCallbacks(longPress)
                        if (scrollMode) {
                            mode = modeScroll
                            lastY = e.y
                            scrollAcc = 0f
                        } else {
                            mode = modePointer
                            follow(e.x, e.y)
                        }
                    }
                } else if (mode == modePointer || mode == modeHold) {
                    follow(e.x, e.y)
                } else if (mode == modeScroll) {
                    scrollAcc += e.y - lastY
                    lastY = e.y
                    val step = 24f
                    var wheel = 0
                    while (scrollAcc >= step) {
                        wheel += 120
                        scrollAcc -= step
                    }
                    while (scrollAcc <= -step) {
                        wheel -= 120
                        scrollAcc += step
                    }
                    if (wheel != 0) listener.onWheel(wheel)
                } else if (mode == modeZoom && e.pointerCount >= 2) {
                    val d: Float = distance(e)
                    val mx: Float = (e.getX(0) + e.getX(1)) / 2f
                    val my: Float = (e.getY(0) + e.getY(1)) / 2f
                    val bmp: Bitmap? = frame
                    if (bmp != null && lastDist > 0f) {
                        val newZoom: Float = (zoom * d / lastDist).coerceIn(1f, 6f)
                        val f: Float = newZoom / zoom
                        // keep the spot between your fingers where it is, then follow their slide
                        tx = mx - (mx - tx) * f + (mx - lastMidX)
                        ty = my - (my - ty) * f + (my - lastMidY)
                        zoom = newZoom
                        clampPan(bmp)
                        invalidate()
                    }
                    if (Math.abs(d - startDist) > slop ||
                        Math.hypot((mx - startMidX).toDouble(), (my - startMidY).toDouble()) > slop
                    ) {
                        zoomMoved = true
                    }
                    lastDist = d
                    lastMidX = mx
                    lastMidY = my
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == modeZoom) {
                    if (!zoomMoved && e.eventTime - twoDownTime < 350L) {
                        val f: FloatArray? = toFraction(lastMidX, lastMidY, true)
                        if (f != null) listener.onTap(f[0], f[1], true)
                    }
                    // the finger that is left should not start moving the pointer
                    mode = modeNone
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (mode == modeUndecided && e.eventTime - downTime < 350L) {
                    val f: FloatArray? = toFraction(e.x, e.y, true)
                    if (f != null) listener.onTap(f[0], f[1], false)
                } else if (mode == modeHold) {
                    val f: FloatArray? = toFraction(e.x, e.y, false)
                    if (f != null) {
                        lastFx = f[0]
                        lastFy = f[1]
                    }
                    endHold()
                }
                mode = modeNone
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (mode == modeHold) endHold()
                mode = modeNone
            }
        }
        return true
    }
}
