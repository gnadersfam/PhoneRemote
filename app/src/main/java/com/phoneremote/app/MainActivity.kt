package com.phoneremote.app

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import java.net.URLEncoder
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newFixedThreadPool(2)
    private val cmd: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var root: FrameLayout
    private lateinit var prefs: SharedPreferences

    private var host: String = ""
    private var port: Int = 47801
    private var token: String = ""
    private var pcName: String = "My PC"

    private var screen: Int = 0 // 1 connect, 2 main remote, 3 can't find PC
    private var failures: Int = 0
    private var polling: Boolean = false
    private var reconnecting: Boolean = false

    private var nowTitleV: TextView? = null
    private var nowSubV: TextView? = null
    private var volTextV: TextView? = null
    private var volBarV: SeekBar? = null
    private var muteV: ImageView? = null
    private var retryBtnV: TextView? = null

    // Find my phone card on the main screen
    private var findTitleV: TextView? = null
    private var findTextV: TextView? = null
    private var findBtnV: TextView? = null

    // Mouse and typing
    private val moveLock = Any()
    private var accDx: Int = 0
    private var accDy: Int = 0
    private var moveQueued: Boolean = false
    private var lastLostToast: Long = 0L

    private var userDragging: Boolean = false
    private var lastLocalChange: Long = 0L
    private var pendingVol: Int = 0
    private var volScheduled: Boolean = false

    // ------------------------------------------------------------------ set-up

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("remote", MODE_PRIVATE)
        host = prefs.getString("host", "") ?: ""
        port = prefs.getInt("port", 47801)
        token = prefs.getString("token", "") ?: ""
        pcName = prefs.getString("name", "My PC") ?: "My PC"

        root = FrameLayout(this)
        root.setBackgroundColor(C.BG)
        root.fitsSystemWindows = true
        setContentView(root)

        if (token.isEmpty() || host.isEmpty()) showConnect() else showMain()
    }

    override fun onResume() {
        super.onResume()
        if (screen == 2) {
            startPolling()
            refreshFindCard()
        }
    }

    override fun onBackPressed() {
        if (screen == 5) {
            hideKeyboard()
            showTouchpad()
        } else if (screen == 4) {
            showMain()
        } else {
            super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startFindService()
        refreshFindCard()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    private fun setScreen(v: View) {
        root.removeAllViews()
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun savePc() {
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("token", token)
            .putString("name", pcName)
            .apply()
    }

    private fun forget() {
        token = ""
        host = ""
        prefs.edit().remove("token").remove("host").apply()
        try {
            stopService(Intent(this, RingService::class.java))
        } catch (e: Exception) {
        }
    }

    private fun startFindService() {
        if (token.isEmpty()) return
        try {
            startForegroundService(Intent(this, RingService::class.java))
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ screen 1: connect

    private fun boxBg(active: Boolean): GradientDrawable {
        val g = rounded(this, C.CARD, 16)
        g.setStroke(dp(this, 2), if (active) C.ACCENT else C.LINE)
        return g
    }

    private fun showConnect() {
        screen = 1
        stopPolling()
        val ctx = this

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(ctx, 24), dp(ctx, 32), dp(ctx, 24), dp(ctx, 24))

        val badge = FrameLayout(ctx)
        badge.background = rounded(ctx, C.CARD, 22)
        badge.addView(plainIcon(ctx, R.drawable.ic_pc, C.ACCENT), FrameLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36), Gravity.CENTER))
        col.addView(badge, linp(dp(ctx, 72), dp(ctx, 72), 0f, 0, dp(ctx, 24), 0, 0))

        col.addView(label(ctx, "Connect to your PC", 30f, C.TEXT, true), linp(MATCH, WRAP, 0f, 0, dp(ctx, 24), 0, 0))
        col.addView(
            label(ctx, "Open the Phone Remote program on your PC. It shows a short code. Type that code in below. You only do this once.", 16f, C.MUTED, false),
            linp(MATCH, WRAP, 0f, 0, dp(ctx, 10), 0, 0)
        )

        col.addView(label(ctx, "Code from your PC", 14f, Color.parseColor("#D5D9E2"), false), linp(MATCH, WRAP, 0f, 0, dp(ctx, 32), 0, dp(ctx, 12)))

        val codeBox = FrameLayout(ctx)
        val boxes = LinearLayout(ctx)
        boxes.orientation = LinearLayout.HORIZONTAL
        val digits = ArrayList<TextView>()
        for (i in 0 until 4) {
            val d = label(ctx, "", 28f, C.TEXT, true)
            d.gravity = Gravity.CENTER
            d.background = boxBg(i == 0)
            boxes.addView(d, linp(0, dp(ctx, 64), 1f, if (i > 0) dp(ctx, 10) else 0, 0, 0, 0))
            digits.add(d)
        }
        val input = EditText(ctx)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(4))
        input.setTextColor(Color.TRANSPARENT)
        input.isCursorVisible = false
        input.setBackgroundColor(Color.TRANSPARENT)
        input.contentDescription = "Code from your PC"
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val t: String = s?.toString() ?: ""
                for (i in 0 until 4) {
                    digits[i].text = if (i < t.length) t.substring(i, i + 1) else ""
                    digits[i].background = boxBg(i == Math.min(t.length, 3))
                }
            }
        })
        codeBox.addView(boxes, FrameLayout.LayoutParams(MATCH, WRAP))
        codeBox.addView(input, FrameLayout.LayoutParams(MATCH, dp(ctx, 64)))
        col.addView(codeBox, linp(MATCH, WRAP))

        col.addView(
            label(ctx, "Your phone and PC need to be on the same home Wi-Fi.", 13f, C.MUTED, false),
            linp(MATCH, WRAP, 0f, 0, dp(ctx, 12), 0, 0)
        )

        val addrLink = label(ctx, "Can't connect? Type your PC's address instead", 14f, C.ACCENT, false)
        addrLink.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12))
        col.addView(addrLink, linp(WRAP, WRAP, 0f, 0, dp(ctx, 8), 0, 0))
        val addr = EditText(ctx)
        addr.hint = "PC address, shown in the PC program"
        addr.setHintTextColor(C.MUTED)
        addr.setTextColor(C.TEXT)
        addr.textSize = 16f
        addr.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        addr.background = rounded(ctx, C.CARD, 14)
        addr.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        addr.visibility = View.GONE
        col.addView(addr, linp(MATCH, WRAP))
        addrLink.setOnClickListener {
            addr.visibility = if (addr.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val status = label(ctx, "", 14f, C.MUTED, false)
        col.addView(status, linp(MATCH, WRAP, 0f, 0, dp(ctx, 12), 0, 0))

        col.addView(View(ctx), linp(MATCH, 0, 1f, 0, 0, 0, 0))

        val btn = pill(ctx, "Connect", C.ACCENT, C.ON_ACCENT, 18f, 20)
        col.addView(btn, linp(MATCH, dp(ctx, 64)))

        fun fail(msg: String) {
            status.setTextColor(C.DANGER)
            status.text = msg
            btn.isEnabled = true
            btn.alpha = 1f
        }

        btn.setOnClickListener {
            val code: String = input.text.toString().trim()
            if (code.length != 4) {
                status.setTextColor(C.DANGER)
                status.text = "Type the 4-digit code shown on your PC."
                return@setOnClickListener
            }
            val manual: String = if (addr.visibility == View.VISIBLE) addr.text.toString().trim() else ""
            btn.isEnabled = false
            btn.alpha = 0.6f
            status.setTextColor(C.MUTED)
            status.text = "Looking for your PC..."
            io.execute {
                var h = manual
                var p = 47801
                var found = true
                if (manual.isEmpty()) {
                    val pc: PcInfo? = Net.discover(5000)
                    if (pc == null) {
                        found = false
                    } else {
                        h = pc.host
                        p = pc.port
                    }
                }
                if (!found) {
                    handler.post { fail("Couldn't find your PC. Check it is switched on, the Phone Remote program is running, and your phone is on the same Wi-Fi.") }
                    return@execute
                }
                val r: Net.Reply? = Net.call(h, p, "POST", "/pair", null, "code=" + code)
                handler.post {
                    if (r == null) {
                        fail("Couldn't reach your PC. Check the address and try again.")
                    } else if (r.code == 200) {
                        try {
                            val j = JSONObject(r.body)
                            token = j.getString("token")
                            pcName = j.optString("name", "My PC")
                            host = h
                            port = p
                            savePc()
                            showMain()
                        } catch (e: Exception) {
                            fail("Something went wrong. Please try again.")
                        }
                    } else if (r.code == 403) {
                        fail("That code isn't right. Check the code on your PC and try again.")
                    } else {
                        fail("Something went wrong. Please try again.")
                    }
                }
            }
        }

        val scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        setScreen(scroll)
    }

    // ------------------------------------------------------------------ screen 2: the remote

    private fun buildHeader(topLabel: String, online: Boolean): LinearLayout {
        val ctx = this
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val left = LinearLayout(ctx)
        left.orientation = LinearLayout.VERTICAL
        left.addView(label(ctx, topLabel, 13f, C.MUTED, false))
        left.addView(label(ctx, pcName, 24f, C.TEXT, true))
        row.addView(left, linp(0, WRAP, 1f, 0, 0, 0, 0))

        val pillBox = LinearLayout(ctx)
        pillBox.orientation = LinearLayout.HORIZONTAL
        pillBox.gravity = Gravity.CENTER_VERTICAL
        pillBox.background = rounded(ctx, C.CARD, 20)
        pillBox.setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
        val dot = View(ctx)
        val dotBg = GradientDrawable()
        dotBg.shape = GradientDrawable.OVAL
        dotBg.setColor(if (online) C.GREEN else C.DANGER)
        dot.background = dotBg
        pillBox.addView(dot, linp(dp(ctx, 8), dp(ctx, 8), 0f, 0, 0, dp(ctx, 8), 0))
        pillBox.addView(label(ctx, if (online) "On your Wi-Fi" else "Not connected", 13f, Color.parseColor("#D5D9E2"), false))
        row.addView(pillBox, linp(WRAP, WRAP))
        return row
    }

    private fun styleSeekBar(bar: SeekBar) {
        val ctx = this
        val trackBg = rounded(ctx, C.CARD2, 6)
        trackBg.setSize(-1, dp(ctx, 12))
        val fill = rounded(ctx, C.ACCENT, 6)
        fill.setSize(-1, dp(ctx, 12))
        val clip = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)
        val layers = LayerDrawable(arrayOf<Drawable>(trackBg, clip))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        bar.progressDrawable = layers
        val thumb = GradientDrawable()
        thumb.shape = GradientDrawable.OVAL
        thumb.setColor(Color.WHITE)
        thumb.setSize(dp(ctx, 28), dp(ctx, 28))
        bar.thumb = thumb
        bar.thumbOffset = 0
        bar.setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
        bar.max = 100
    }

    private fun powerButton(iconRes: Int, text: String, fg: Int): LinearLayout {
        val ctx = this
        val b = LinearLayout(ctx)
        b.orientation = LinearLayout.VERTICAL
        b.gravity = Gravity.CENTER
        b.background = withRipple(ctx, C.CARD, 20)
        b.isClickable = true
        b.isFocusable = true
        b.contentDescription = text
        b.addView(plainIcon(ctx, iconRes, fg), linp(dp(ctx, 24), dp(ctx, 24)))
        b.addView(label(ctx, text, 14f, fg, false), linp(WRAP, WRAP, 0f, 0, dp(ctx, 8), 0, 0))
        return b
    }

    private fun showMain() {
        screen = 2
        failures = 0
        reconnecting = false
        val ctx = this

        val scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(ctx, 24), dp(ctx, 16), dp(ctx, 24), dp(ctx, 24))
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        col.addView(buildHeader("Connected to", true), linp(MATCH, WRAP))

        // ---- now playing card
        val now = LinearLayout(ctx)
        now.orientation = LinearLayout.VERTICAL
        now.background = rounded(ctx, C.CARD, 24)
        now.setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 20))

        val nowRow = LinearLayout(ctx)
        nowRow.orientation = LinearLayout.HORIZONTAL
        nowRow.gravity = Gravity.CENTER_VERTICAL
        val art = FrameLayout(ctx)
        art.background = rounded(ctx, C.CARD2, 16)
        art.addView(plainIcon(ctx, R.drawable.ic_music, C.ACCENT), FrameLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32), Gravity.CENTER))
        nowRow.addView(art, linp(dp(ctx, 72), dp(ctx, 72)))

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        val tag = label(ctx, "NOW PLAYING", 12f, C.MUTED, false)
        tag.letterSpacing = 0.08f
        texts.addView(tag)
        val title = label(ctx, "Nothing playing", 19f, C.TEXT, true)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        texts.addView(title, linp(MATCH, WRAP, 0f, 0, dp(ctx, 4), 0, 0))
        val sub = label(ctx, "Spotify, YouTube and VLC show here", 14f, C.MUTED, false)
        texts.addView(sub, linp(MATCH, WRAP, 0f, 0, dp(ctx, 4), 0, 0))
        nowRow.addView(texts, linp(0, WRAP, 1f, dp(ctx, 16), 0, 0, 0))
        now.addView(nowRow, linp(MATCH, WRAP))
        nowTitleV = title
        nowSubV = sub

        val controls = LinearLayout(ctx)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        val prev = circleButton(ctx, R.drawable.ic_prev, 56, 24, C.CARD2, C.TEXT, "Previous")
        val play = circleButton(ctx, R.drawable.ic_playpause, 72, 30, C.ACCENT, C.ON_ACCENT, "Play or pause")
        val next = circleButton(ctx, R.drawable.ic_next, 56, 24, C.CARD2, C.TEXT, "Next")
        controls.addView(prev, linp(dp(ctx, 56), dp(ctx, 56)))
        controls.addView(play, linp(dp(ctx, 72), dp(ctx, 72), 0f, dp(ctx, 20), 0, dp(ctx, 20), 0))
        controls.addView(next, linp(dp(ctx, 56), dp(ctx, 56)))
        now.addView(controls, linp(MATCH, WRAP, 0f, 0, dp(ctx, 20), 0, 0))
        col.addView(now, linp(MATCH, WRAP, 0f, 0, dp(ctx, 20), 0, 0))

        prev.setOnClickListener { sendMedia("prev") }
        play.setOnClickListener { sendMedia("playpause") }
        next.setOnClickListener { sendMedia("next") }

        // ---- volume card
        val vol = LinearLayout(ctx)
        vol.orientation = LinearLayout.VERTICAL
        vol.background = rounded(ctx, C.CARD, 24)
        vol.setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 20))

        val volHead = LinearLayout(ctx)
        volHead.orientation = LinearLayout.HORIZONTAL
        volHead.addView(label(ctx, "Volume", 16f, C.TEXT, true), linp(0, WRAP, 1f, 0, 0, 0, 0))
        val volText = label(ctx, "--", 16f, Color.parseColor("#D5D9E2"), false)
        volHead.addView(volText, linp(WRAP, WRAP))
        vol.addView(volHead, linp(MATCH, WRAP))
        volTextV = volText

        val volRow = LinearLayout(ctx)
        volRow.orientation = LinearLayout.HORIZONTAL
        volRow.gravity = Gravity.CENTER_VERTICAL
        val mute = circleButton(ctx, R.drawable.ic_mute, 56, 24, C.CARD2, C.TEXT, "Mute")
        volRow.addView(mute, linp(dp(ctx, 56), dp(ctx, 56)))
        val bar = SeekBar(ctx)
        styleSeekBar(bar)
        volRow.addView(bar, linp(0, dp(ctx, 40), 1f, dp(ctx, 12), 0, 0, 0))
        vol.addView(volRow, linp(MATCH, WRAP, 0f, 0, dp(ctx, 16), 0, 0))
        col.addView(vol, linp(MATCH, WRAP, 0f, 0, dp(ctx, 20), 0, 0))
        volBarV = bar
        muteV = mute

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                volTextV?.text = "" + progress + "%"
                if (fromUser) {
                    lastLocalChange = System.currentTimeMillis()
                    queueVolume(progress)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userDragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userDragging = false
                lastLocalChange = System.currentTimeMillis()
                sendVolumeNow(sb?.progress ?: 0)
            }
        })

        mute.setOnClickListener {
            post("/mute") { r ->
                if (r == null) {
                    toast("Couldn't reach your PC")
                } else if (r.code == 200) {
                    try {
                        showMuted(JSONObject(r.body).optBoolean("muted", false))
                    } catch (e: Exception) {
                    }
                }
            }
        }

        // ---- keyboard and mouse
        val kmRow = LinearLayout(ctx)
        kmRow.orientation = LinearLayout.HORIZONTAL
        kmRow.gravity = Gravity.CENTER_VERTICAL
        kmRow.background = withRipple(ctx, C.CARD, 24)
        kmRow.isClickable = true
        kmRow.isFocusable = true
        kmRow.setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        val kmIcon = FrameLayout(ctx)
        kmIcon.background = rounded(ctx, C.CARD2, 14)
        kmIcon.addView(plainIcon(ctx, R.drawable.ic_mouse, C.ACCENT), FrameLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24), Gravity.CENTER))
        kmRow.addView(kmIcon, linp(dp(ctx, 48), dp(ctx, 48)))
        val kmTexts = LinearLayout(ctx)
        kmTexts.orientation = LinearLayout.VERTICAL
        kmTexts.addView(label(ctx, "Keyboard & mouse", 17f, C.TEXT, true))
        kmTexts.addView(label(ctx, "Use your phone as a touchpad and keyboard", 13f, C.MUTED, false), linp(MATCH, WRAP, 0f, 0, dp(ctx, 2), 0, 0))
        kmRow.addView(kmTexts, linp(0, WRAP, 1f, dp(ctx, 14), 0, dp(ctx, 8), 0))
        kmRow.addView(plainIcon(ctx, R.drawable.ic_chevron_right, C.MUTED), linp(dp(ctx, 24), dp(ctx, 24)))
        col.addView(kmRow, linp(MATCH, WRAP, 0f, 0, dp(ctx, 20), 0, 0))
        kmRow.setOnClickListener { showTouchpad() }

        // ---- screen view
        val scRow = LinearLayout(ctx)
        scRow.orientation = LinearLayout.HORIZONTAL
        scRow.gravity = Gravity.CENTER_VERTICAL
        scRow.background = withRipple(ctx, C.CARD, 24)
        scRow.isClickable = true
        scRow.isFocusable = true
        scRow.setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        val scIcon = FrameLayout(ctx)
        scIcon.background = rounded(ctx, C.CARD2, 14)
        scIcon.addView(plainIcon(ctx, R.drawable.ic_pc, C.ACCENT), FrameLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24), Gravity.CENTER))
        scRow.addView(scIcon, linp(dp(ctx, 48), dp(ctx, 48)))
        val scTexts = LinearLayout(ctx)
        scTexts.orientation = LinearLayout.VERTICAL
        scTexts.addView(label(ctx, "Screen view", 17f, C.TEXT, true))
        scTexts.addView(label(ctx, "Watch and control your PC screen, with sound", 13f, C.MUTED, false), linp(MATCH, WRAP, 0f, 0, dp(ctx, 2), 0, 0))
        scRow.addView(scTexts, linp(0, WRAP, 1f, dp(ctx, 14), 0, dp(ctx, 8), 0))
        scRow.addView(plainIcon(ctx, R.drawable.ic_chevron_right, C.MUTED), linp(dp(ctx, 24), dp(ctx, 24)))
        col.addView(scRow, linp(MATCH, WRAP, 0f, 0, dp(ctx, 12), 0, 0))
        scRow.setOnClickListener { startActivity(Intent(this, ScreenActivity::class.java)) }

        // ---- find my phone
        val findRow = LinearLayout(ctx)
        findRow.orientation = LinearLayout.VERTICAL
        findRow.background = rounded(ctx, C.CARD, 24)
        findRow.setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 16))
        val findHead = LinearLayout(ctx)
        findHead.orientation = LinearLayout.HORIZONTAL
        findHead.gravity = Gravity.CENTER_VERTICAL
        val findIcon = FrameLayout(ctx)
        findIcon.background = rounded(ctx, C.CARD2, 14)
        findIcon.addView(plainIcon(ctx, R.drawable.ic_ring, C.ACCENT), FrameLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24), Gravity.CENTER))
        findHead.addView(findIcon, linp(dp(ctx, 48), dp(ctx, 48)))
        val findTitle = label(ctx, "Find my phone", 17f, C.TEXT, true)
        findHead.addView(findTitle, linp(0, WRAP, 1f, dp(ctx, 14), 0, 0, 0))
        findRow.addView(findHead, linp(MATCH, WRAP))
        val findText = label(ctx, "", 14f, C.MUTED, false)
        findRow.addView(findText, linp(MATCH, WRAP, 0f, 0, dp(ctx, 10), 0, 0))
        val findBtn = pill(ctx, "Allow", C.ACCENT, C.ON_ACCENT, 16f, 16)
        findRow.addView(findBtn, linp(MATCH, dp(ctx, 52), 0f, 0, dp(ctx, 12), 0, 0))
        col.addView(findRow, linp(MATCH, WRAP, 0f, 0, dp(ctx, 12), 0, 0))
        findTitleV = findTitle
        findTextV = findText
        findBtnV = findBtn
        findBtn.setOnClickListener { askForFindPermission() }

        // ---- power
        col.addView(View(ctx), linp(MATCH, 0, 1f, 0, 0, 0, 0))
        col.addView(label(ctx, "Power", 14f, C.MUTED, false), linp(WRAP, WRAP, 0f, 0, dp(ctx, 20), 0, dp(ctx, 12)))
        val powerRow = LinearLayout(ctx)
        powerRow.orientation = LinearLayout.HORIZONTAL
        val sleepB = powerButton(R.drawable.ic_sleep, "Sleep", C.TEXT)
        val restartB = powerButton(R.drawable.ic_restart, "Restart", C.TEXT)
        val offB = powerButton(R.drawable.ic_power, "Shut down", C.DANGER)
        powerRow.addView(sleepB, linp(0, dp(ctx, 80), 1f, 0, 0, 0, 0))
        powerRow.addView(restartB, linp(0, dp(ctx, 80), 1f, dp(ctx, 12), 0, 0, 0))
        powerRow.addView(offB, linp(0, dp(ctx, 80), 1f, dp(ctx, 12), 0, 0, 0))
        col.addView(powerRow, linp(MATCH, WRAP))

        sleepB.setOnClickListener {
            post("/power?action=sleep") { r ->
                if (r == null) toast("Couldn't reach your PC") else toast("Putting your PC to sleep...")
            }
        }
        restartB.setOnClickListener {
            confirmPower("restart", "Restart your PC?", "Your PC will restart. This takes a minute or two.", "Yes, restart")
        }
        offB.setOnClickListener {
            confirmPower("shutdown", "Shut down your PC?", "Your PC will turn off. You'll need to switch it on by hand to use it again.", "Yes, shut down")
        }

        setScreen(scroll)
        refreshFindCard()
        startFindService()
        startPolling()
    }

    // ------------------------------------------------------------------ find my phone: permissions

    private fun needNotifPermission(): Boolean {
        return Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    private fun needBatteryExemption(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshFindCard() {
        val text = findTextV ?: return
        val btn = findBtnV ?: return
        if (needNotifPermission()) {
            text.text = "Allow notifications, so your PC can make this phone ring if you lose it."
            btn.text = "Allow notifications"
            btn.visibility = View.VISIBLE
        } else if (needBatteryExemption()) {
            text.text = "One more step: let Phone Remote keep running in the background, so this phone can always hear your PC."
            btn.text = "Allow"
            btn.visibility = View.VISIBLE
        } else {
            text.text = "On. Press Find my phone in the PC program and this phone rings loudly, even on silent. It needs to be on your home Wi-Fi."
            btn.visibility = View.GONE
        }
    }

    private fun askForFindPermission() {
        if (needNotifPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        } else if (needBatteryExemption()) {
            try {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:" + packageName)
                startActivity(i)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    toast("Open your phone's Settings and set Phone Remote's battery to Unrestricted")
                }
            }
        }
    }

    private fun showMuted(muted: Boolean) {
        val m = muteV ?: return
        m.imageTintList = android.content.res.ColorStateList.valueOf(if (muted) C.DANGER else C.TEXT)
        if (muted) volTextV?.text = "Muted"
    }

    private fun confirmPower(action: String, titleText: String, message: String, yesText: String) {
        val ctx = this
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = rounded(ctx, C.CARD, 28)
        card.setPadding(dp(ctx, 24), dp(ctx, 28), dp(ctx, 24), dp(ctx, 24))

        val circle = FrameLayout(ctx)
        val circleBg = GradientDrawable()
        circleBg.shape = GradientDrawable.OVAL
        circleBg.setColor(C.DANGER_BG)
        circle.background = circleBg
        circle.addView(plainIcon(ctx, R.drawable.ic_power, C.DANGER), FrameLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30), Gravity.CENTER))
        card.addView(circle, linp(dp(ctx, 64), dp(ctx, 64)))

        val t = label(ctx, titleText, 24f, C.TEXT, true)
        t.gravity = Gravity.CENTER
        card.addView(t, linp(MATCH, WRAP, 0f, 0, dp(ctx, 16), 0, 0))
        val m = label(ctx, message, 16f, C.MUTED, false)
        m.gravity = Gravity.CENTER
        card.addView(m, linp(MATCH, WRAP, 0f, 0, dp(ctx, 12), 0, dp(ctx, 24)))

        val yes = pill(ctx, yesText, C.DANGER, Color.parseColor("#1A0A0B"), 17f, 18)
        card.addView(yes, linp(MATCH, dp(ctx, 60)))
        val no = pill(ctx, "Cancel", C.CARD2, C.TEXT, 17f, 18)
        card.addView(no, linp(MATCH, dp(ctx, 60), 0f, 0, dp(ctx, 12), 0, 0))

        dlg.setContentView(card)
        val w = dlg.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(resources.displayMetrics.widthPixels - dp(ctx, 48), WRAP)
        }
        no.setOnClickListener { dlg.dismiss() }
        yes.setOnClickListener {
            dlg.dismiss()
            post("/power?action=" + action) { r ->
                if (r == null) {
                    toast("Couldn't reach your PC")
                } else if (action == "shutdown") {
                    toast("Shutting down your PC...")
                } else {
                    toast("Restarting your PC...")
                }
            }
        }
        dlg.show()
    }

    // ------------------------------------------------------------------ screens 4 and 5: touchpad and typing

    private fun backHeader(titleText: String, onBack: () -> Unit): LinearLayout {
        val ctx = this
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val back = circleButton(ctx, R.drawable.ic_chevron_left, 48, 24, C.CARD, C.TEXT, "Back")
        back.setOnClickListener { onBack() }
        row.addView(back, linp(dp(ctx, 48), dp(ctx, 48)))
        row.addView(label(ctx, titleText, 22f, C.TEXT, true), linp(0, WRAP, 1f, dp(ctx, 14), 0, 0, 0))
        return row
    }

    private fun wideButton(iconRes: Int?, text: String): LinearLayout {
        val ctx = this
        val b = LinearLayout(ctx)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER
        b.background = withRipple(ctx, C.CARD, 18)
        b.isClickable = true
        b.isFocusable = true
        b.contentDescription = text
        if (iconRes != null) {
            b.addView(plainIcon(ctx, iconRes, C.TEXT), linp(dp(ctx, 22), dp(ctx, 22), 0f, 0, 0, dp(ctx, 10), 0))
        }
        b.addView(label(ctx, text, 16f, C.TEXT, true))
        return b
    }

    private fun lostConnection() {
        val now: Long = System.currentTimeMillis()
        if (now - lastLostToast > 4000L) {
            lastLostToast = now
            toast("Lost the connection to your PC")
        }
    }

    private fun showTouchpad() {
        screen = 4
        stopPolling()
        val ctx = this

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 20))
        col.addView(backHeader("Keyboard & mouse") { showMain() }, linp(MATCH, WRAP))

        val pad = TouchPad(ctx, object : TouchPad.Listener {
            override fun onMove(dx: Int, dy: Int) {
                queueMove(dx, dy)
            }

            override fun onTap() {
                sendQuick("/mouse/click?button=left")
            }

            override fun onTwoFingerTap() {
                sendQuick("/mouse/click?button=right")
            }

            override fun onScroll(wheel: Int) {
                sendQuick("/mouse/scroll?dy=" + wheel)
            }
        })
        pad.background = rounded(ctx, C.CARD, 28)
        val hint = label(ctx, "Slide to move the mouse\nTap to click\n\nTwo fingers: tap to right-click, slide to scroll", 15f, C.MUTED, false)
        hint.gravity = Gravity.CENTER
        pad.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        col.addView(pad, linp(MATCH, 0, 1f, 0, dp(ctx, 16), 0, 0))

        val clicks = LinearLayout(ctx)
        clicks.orientation = LinearLayout.HORIZONTAL
        val left = wideButton(null, "Left click")
        val right = wideButton(null, "Right click")
        clicks.addView(left, linp(0, dp(ctx, 60), 1f, 0, 0, 0, 0))
        clicks.addView(right, linp(0, dp(ctx, 60), 1f, dp(ctx, 12), 0, 0, 0))
        col.addView(clicks, linp(MATCH, WRAP, 0f, 0, dp(ctx, 12), 0, 0))
        left.setOnClickListener { sendQuick("/mouse/click?button=left") }
        right.setOnClickListener { sendQuick("/mouse/click?button=right") }

        val kb = pill(ctx, "Keyboard", C.ACCENT, C.ON_ACCENT, 18f, 18)
        col.addView(kb, linp(MATCH, dp(ctx, 60), 0f, 0, dp(ctx, 12), 0, 0))
        kb.setOnClickListener { showTyping() }

        setScreen(col)
    }

    /** Collects mouse movement and sends it in batches, so the pointer stays smooth. */
    private fun queueMove(dx: Int, dy: Int) {
        synchronized(moveLock) {
            accDx += dx
            accDy += dy
            if (moveQueued) return
            moveQueued = true
        }
        val h = host
        val p = port
        val tk = token
        cmd.execute {
            var mx = 0
            var my = 0
            synchronized(moveLock) {
                mx = accDx
                my = accDy
                accDx = 0
                accDy = 0
                moveQueued = false
            }
            if (mx != 0 || my != 0) {
                val r: Net.Reply? = Net.call(h, p, "POST", "/mouse/move?dx=" + mx + "&dy=" + my, tk, "")
                if (r == null) {
                    handler.post { lostConnection() }
                } else if (r.code == 401) {
                    handler.post { needsPairing() }
                }
            }
        }
    }

    /** A click, a scroll, a key: sent in order, with no pop-up unless something goes wrong. */
    private fun sendQuick(path: String) {
        val h = host
        val p = port
        val tk = token
        cmd.execute {
            val r: Net.Reply? = Net.call(h, p, "POST", path, tk, "")
            if (r == null) {
                handler.post { lostConnection() }
            } else if (r.code == 401) {
                handler.post { needsPairing() }
            }
        }
    }

    private fun needsPairing() {
        toast("Please connect again")
        forget()
        showConnect()
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(root.windowToken, 0)
        } catch (e: Exception) {
        }
    }

    private fun showTyping() {
        screen = 5
        stopPolling()
        val ctx = this
        val mark = "​" // an invisible character, so the Backspace key still works in an empty box

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 20))
        col.addView(backHeader("Typing") {
            hideKeyboard()
            showTouchpad()
        }, linp(MATCH, WRAP))

        col.addView(
            label(ctx, "Type with your phone's keyboard. What you type appears on your PC, wherever the cursor is.", 15f, C.MUTED, false),
            linp(MATCH, WRAP, 0f, 0, dp(ctx, 14), 0, 0)
        )

        val box = EditText(ctx)
        box.setText(mark)
        box.setTextColor(C.TEXT)
        box.textSize = 18f
        box.gravity = Gravity.TOP or Gravity.START
        box.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        box.background = rounded(ctx, C.CARD, 20)
        box.setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16))
        box.minLines = 3
        box.maxLines = 6
        col.addView(box, linp(MATCH, WRAP, 0f, 0, dp(ctx, 14), 0, 0))

        val keys = LinearLayout(ctx)
        keys.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("Esc", "Tab", "Enter", "Delete")
        val keyNames = arrayOf("esc", "tab", "enter", "delete")
        for (i in 0 until 4) {
            val b = pill(ctx, names[i], C.CARD, C.TEXT, 15f, 16)
            val keyName: String = keyNames[i]
            b.setOnClickListener { sendQuick("/key?name=" + keyName + "&count=1") }
            keys.addView(b, linp(0, dp(ctx, 52), 1f, if (i > 0) dp(ctx, 10) else 0, 0, 0, 0))
        }
        col.addView(keys, linp(MATCH, WRAP, 0f, 0, dp(ctx, 14), 0, 0))
        col.addView(View(ctx), linp(MATCH, 0, 1f, 0, 0, 0, 0))

        var typed = "" // what the PC has been sent so far (without the invisible character)
        var guard = false
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (guard) return
                val raw: String = s?.toString() ?: ""
                val now: String = if (raw.startsWith(mark)) raw.substring(1) else raw

                if (raw.isEmpty() && typed.isEmpty()) {
                    // Backspace in an empty box: the PC may still have text to delete
                    sendQuick("/key?name=backspace&count=1")
                } else {
                    var common = 0
                    val limit: Int = Math.min(typed.length, now.length)
                    while (common < limit && typed[common] == now[common]) common++
                    // count whole characters, not UTF-16 halves, so emoji delete properly
                    val tail: String = typed.substring(common)
                    var deleted: Int = tail.codePointCount(0, tail.length)
                    while (deleted > 0) {
                        val n: Int = Math.min(deleted, 60)
                        sendQuick("/key?name=backspace&count=" + n)
                        deleted -= n
                    }
                    val added: String = now.substring(common)
                    if (added.isNotEmpty()) {
                        sendText(added)
                    }
                    typed = now
                }

                if (!raw.startsWith(mark) || typed.length > 200) {
                    guard = true
                    if (typed.length > 200) typed = ""
                    box.setText(mark + typed)
                    box.setSelection(box.text.length)
                    guard = false
                }
            }
        })

        setScreen(col)
        box.requestFocus()
        box.postDelayed({
            box.setSelection(box.text.length)
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) {
            }
        }, 250L)
    }

    private fun sendText(text: String) {
        val h = host
        val p = port
        val tk = token
        cmd.execute {
            val form: String = "text=" + URLEncoder.encode(text, "UTF-8")
            val r: Net.Reply? = Net.call(h, p, "POST", "/type", tk, form)
            if (r == null) {
                handler.post { lostConnection() }
            } else if (r.code == 401) {
                handler.post { needsPairing() }
            }
        }
    }

    // ------------------------------------------------------------------ talking to the PC

    private fun post(path: String, after: ((Net.Reply?) -> Unit)?) {
        val h = host
        val p = port
        val tk = token
        cmd.execute {
            val r: Net.Reply? = Net.call(h, p, "POST", path, tk, "")
            handler.post {
                if (r != null && r.code == 401) {
                    toast("Please connect again")
                    forget()
                    showConnect()
                } else if (after != null) {
                    after(r)
                }
            }
        }
    }

    private fun sendMedia(action: String) {
        post("/media?action=" + action) { r ->
            if (r == null) toast("Couldn't reach your PC")
        }
    }

    private fun queueVolume(v: Int) {
        pendingVol = v
        if (!volScheduled) {
            volScheduled = true
            handler.postDelayed({
                volScheduled = false
                sendVolumeNow(pendingVol)
            }, 120L)
        }
    }

    private fun sendVolumeNow(v: Int) {
        showMuted(false)
        post("/volume?level=" + v, null)
    }

    // ------------------------------------------------------------------ keeping the screen up to date

    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!polling) return
            pollOnce()
            handler.postDelayed(this, 3000L)
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun pollOnce() {
        val h = host
        val p = port
        val tk = token
        io.execute {
            val r: Net.Reply? = Net.call(h, p, "GET", "/state", tk, null)
            handler.post { onState(r) }
        }
    }

    private fun onState(r: Net.Reply?) {
        if (screen != 2) return
        if (r == null || r.code >= 500) {
            failures++
            if (failures >= 2) {
                stopPolling()
                reconnect(true)
            }
            return
        }
        if (r.code == 401) {
            toast("Please connect again")
            forget()
            showConnect()
            return
        }
        if (r.code != 200) return
        failures = 0
        try {
            val j = JSONObject(r.body)
            val nowText: String = j.optString("now", "")
            if (nowText.isNotEmpty()) {
                nowTitleV?.text = nowText
                nowSubV?.text = "Playing on your PC"
            } else {
                nowTitleV?.text = "Nothing playing"
                nowSubV?.text = "Spotify, YouTube and VLC show here"
            }
            val muted: Boolean = j.optBoolean("muted", false)
            val v: Int = j.optInt("volume", 0)
            val busy: Boolean = userDragging || (System.currentTimeMillis() - lastLocalChange < 1500L)
            if (!busy) {
                volBarV?.progress = v
                volTextV?.text = if (muted) "Muted" else "" + v + "%"
                showMuted(muted)
            }
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ screen 3: can't find the PC

    private fun showOffline() {
        screen = 3
        stopPolling()
        val ctx = this

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(ctx, 24), dp(ctx, 16), dp(ctx, 24), dp(ctx, 24))
        col.addView(buildHeader("Remote for", false), linp(MATCH, WRAP))

        val mid = LinearLayout(ctx)
        mid.orientation = LinearLayout.VERTICAL
        mid.gravity = Gravity.CENTER
        mid.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        val circle = FrameLayout(ctx)
        val circleBg = GradientDrawable()
        circleBg.shape = GradientDrawable.OVAL
        circleBg.setColor(C.CARD)
        circle.background = circleBg
        circle.addView(plainIcon(ctx, R.drawable.ic_wifi_off, C.MUTED), FrameLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44), Gravity.CENTER))
        mid.addView(circle, linp(dp(ctx, 96), dp(ctx, 96)))
        val t = label(ctx, "Can't find your PC", 28f, C.TEXT, true)
        t.gravity = Gravity.CENTER
        mid.addView(t, linp(MATCH, WRAP, 0f, 0, dp(ctx, 24), 0, 0))
        val m = label(ctx, "Check that your PC is switched on and that your phone is on your home Wi-Fi, then try again.", 16f, C.MUTED, false)
        m.gravity = Gravity.CENTER
        mid.addView(m, linp(MATCH, WRAP, 0f, 0, dp(ctx, 10), 0, 0))
        col.addView(mid, linp(MATCH, 0, 1f, 0, 0, 0, 0))

        val retry = pill(ctx, "Try again", C.ACCENT, C.ON_ACCENT, 18f, 20)
        col.addView(retry, linp(MATCH, dp(ctx, 64)))
        val other = pill(ctx, "Connect to a different PC", C.CARD, C.TEXT, 16f, 18)
        col.addView(other, linp(MATCH, dp(ctx, 56), 0f, 0, dp(ctx, 12), 0, 0))
        retryBtnV = retry

        retry.setOnClickListener {
            retry.text = "Looking for your PC..."
            reconnect(false)
        }
        other.setOnClickListener {
            forget()
            showConnect()
        }

        setScreen(col)
    }

    /** Checks the saved PC, and if it has moved on the Wi-Fi, looks for it again. */
    private fun reconnect(auto: Boolean) {
        if (reconnecting) return
        reconnecting = true
        val h0 = host
        val p0 = port
        val tk = token
        io.execute {
            var result = 0 // 0 not found, 1 found, 2 needs pairing again
            var newHost = ""
            var newPort = 0
            val r1: Net.Reply? = Net.call(h0, p0, "GET", "/ping", tk, null)
            if (r1 != null && r1.code == 200) {
                result = 1
            } else if (r1 != null && r1.code == 401) {
                result = 2
            } else {
                val pc: PcInfo? = Net.discover(4000)
                if (pc != null) {
                    val r2: Net.Reply? = Net.call(pc.host, pc.port, "GET", "/ping", tk, null)
                    if (r2 != null && r2.code == 200) {
                        result = 1
                        newHost = pc.host
                        newPort = pc.port
                    } else if (r2 != null && r2.code == 401) {
                        result = 2
                    }
                }
            }
            handler.post {
                reconnecting = false
                if (result == 1) {
                    if (newHost.isNotEmpty()) {
                        host = newHost
                        port = newPort
                        savePc()
                    }
                    showMain()
                } else if (result == 2) {
                    toast("Please connect again")
                    forget()
                    showConnect()
                } else {
                    retryBtnV?.text = "Try again"
                    if (screen != 3) showOffline() else if (!auto) toast("Still can't find your PC")
                }
            }
        }
    }
}
