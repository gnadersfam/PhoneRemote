package com.phoneremote.app

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/** The "Here I am!" screen that appears over the lock screen while the phone is ringing. */
class RingActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val animators = ArrayList<ObjectAnimator>()

    private val checker: Runnable = object : Runnable {
        override fun run() {
            if (!RingService.ringing) {
                finish()
                return
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ctx = this
        val rootView = FrameLayout(ctx)
        rootView.setBackgroundColor(C.BG)

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(ctx, 24), dp(ctx, 24), dp(ctx, 24), dp(ctx, 32))

        col.addView(View(ctx), linp(MATCH, 0, 1f, 0, 0, 0, 0))

        // pulsing rings around a phone icon
        val stack = FrameLayout(ctx)
        for (i in 0 until 3) {
            val ring = View(ctx)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(Color.TRANSPARENT)
            g.setStroke(dp(ctx, 3), C.ACCENT)
            ring.background = g
            stack.addView(ring, FrameLayout.LayoutParams(dp(ctx, 110), dp(ctx, 110), Gravity.CENTER))
            val a: ObjectAnimator = ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 2.3f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 2.3f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0f)
            )
            a.duration = 1800L
            a.startDelay = (i * 600).toLong()
            a.repeatCount = ValueAnimator.INFINITE
            animators.add(a)
        }
        val centre = FrameLayout(ctx)
        val cg = GradientDrawable()
        cg.shape = GradientDrawable.OVAL
        cg.setColor(C.ACCENT)
        centre.background = cg
        centre.addView(plainIcon(ctx, R.drawable.ic_ring, C.ON_ACCENT), FrameLayout.LayoutParams(dp(ctx, 52), dp(ctx, 52), Gravity.CENTER))
        stack.addView(centre, FrameLayout.LayoutParams(dp(ctx, 110), dp(ctx, 110), Gravity.CENTER))
        col.addView(stack, linp(dp(ctx, 280), dp(ctx, 280)))

        val t = label(ctx, "Here I am!", 38f, C.TEXT, true)
        t.gravity = Gravity.CENTER
        col.addView(t, linp(MATCH, WRAP, 0f, 0, dp(ctx, 24), 0, 0))
        val m = label(ctx, "Your PC is ringing this phone.", 18f, C.MUTED, false)
        m.gravity = Gravity.CENTER
        col.addView(m, linp(MATCH, WRAP, 0f, 0, dp(ctx, 10), 0, 0))

        col.addView(View(ctx), linp(MATCH, 0, 1f, 0, 0, 0, 0))

        val stop = pill(ctx, "Stop ringing", C.ACCENT, C.ON_ACCENT, 20f, 24)
        stop.setOnClickListener {
            val i = Intent(ctx, RingService::class.java)
            i.action = RingService.ACTION_STOP
            try {
                startService(i)
            } catch (e: Exception) {
            }
            finish()
        }
        col.addView(stop, linp(MATCH, dp(ctx, 72)))

        rootView.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(rootView)
    }

    override fun onStart() {
        super.onStart()
        for (a in animators) a.start()
        handler.removeCallbacks(checker)
        handler.post(checker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(checker)
        for (a in animators) a.cancel()
    }
}
