package com.phoneremote.app

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * The big touchpad area.
 * One finger moves the mouse, a tap clicks, two fingers sliding scroll,
 * and a two-finger tap is a right click.
 */
class TouchPad(ctx: Context, private val listener: Listener) : FrameLayout(ctx) {

    interface Listener {
        fun onMove(dx: Int, dy: Int)
        fun onTap()
        fun onTwoFingerTap()
        fun onScroll(wheel: Int)
    }

    private val slop: Float = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val gain: Float = 1.8f

    private var downTime: Long = 0L
    private var startX: Float = 0f
    private var startY: Float = 0f
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var remX: Float = 0f
    private var remY: Float = 0f
    private var scrollAcc: Float = 0f
    private var lastAvgY: Float = 0f
    private var twoStartY: Float = 0f
    private var maxPointers: Int = 0
    private var moved: Boolean = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downTime = e.eventTime
                startX = e.x
                startY = e.y
                lastX = e.x
                lastY = e.y
                remX = 0f
                remY = 0f
                scrollAcc = 0f
                maxPointers = 1
                moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = Math.max(maxPointers, e.pointerCount)
                if (e.pointerCount >= 2) {
                    lastAvgY = (e.getY(0) + e.getY(1)) / 2f
                    twoStartY = lastAvgY
                    scrollAcc = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val avg: Float = (e.getY(0) + e.getY(1)) / 2f
                    val dy: Float = avg - lastAvgY
                    lastAvgY = avg
                    if (Math.abs(avg - twoStartY) > slop) moved = true
                    scrollAcc += dy
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
                    if (wheel != 0) listener.onScroll(wheel)
                } else {
                    val dx: Float = e.x - lastX
                    val dy: Float = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    if (!moved) {
                        val far: Double = Math.hypot((e.x - startX).toDouble(), (e.y - startY).toDouble())
                        if (far > slop) moved = true
                    }
                    if (moved) {
                        remX += dx * gain
                        remY += dy * gain
                        val ix: Int = remX.toInt()
                        val iy: Int = remY.toInt()
                        if (ix != 0 || iy != 0) {
                            remX -= ix.toFloat()
                            remY -= iy.toFloat()
                            listener.onMove(ix, iy)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // one of two fingers was lifted: carry on from the finger that is left, without a jump
                val left: Int = if (e.actionIndex == 0) 1 else 0
                if (left < e.pointerCount) {
                    lastX = e.getX(left)
                    lastY = e.getY(left)
                }
            }
            MotionEvent.ACTION_UP -> {
                val quick: Boolean = e.eventTime - downTime < 300L
                if (!moved && quick) {
                    if (maxPointers >= 2) listener.onTwoFingerTap() else listener.onTap()
                }
            }
        }
        return true
    }
}
