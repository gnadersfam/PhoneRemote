package com.phoneremote.app

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Watch and control the PC screen from the phone, with the PC's sound.
 * The PC only works on pictures and sound while this screen is open.
 */
class ScreenActivity : Activity(), ScreenView.Listener {

    companion object {
        /** Set once the screen PIN has been accepted. Lasts until the app is closed completely. */
        @Volatile
        var viewToken: String = ""
    }

    private val handler = Handler(Looper.getMainLooper())
    private val cmd: ExecutorService = Executors.newSingleThreadExecutor()
    private val work: ExecutorService = Executors.newCachedThreadPool()

    private lateinit var prefs: SharedPreferences
    private var host: String = ""
    private var port: Int = 47801
    private var token: String = ""

    private lateinit var screenView: ScreenView
    private lateinit var kbPanel: LinearLayout
    private lateinit var kbBox: EditText
    private lateinit var keysBtn: TextView
    private lateinit var scrollBtn: TextView
    private lateinit var soundBtn: TextView
    private lateinit var qualityBtn: TextView
    private lateinit var saveBtn: TextView

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var videoGen: Int = 0

    @Volatile
    private var audioGen: Int = 0

    @Volatile
    private var videoConn: HttpURLConnection? = null

    @Volatile
    private var audioConn: HttpURLConnection? = null

    @Volatile
    private var soundOn: Boolean = true

    private var pinDialog: Dialog? = null
    private var lastLostToast: Long = 0L

    // picture quality: how wide the picture is, how crisp, and how many a second
    private val qualityNames = arrayOf("Fast", "Normal", "Sharp")
    private val qualityWidth = intArrayOf(800, 1100, 1600)
    private val qualityJpeg = intArrayOf(45, 55, 70)
    private val qualityFps = intArrayOf(12, 10, 8)
    private var qualityIndex: Int = 1

    // pointer moves are collected and sent in batches, so the pointer stays smooth
    private val moveLock = Any()
    private var pendX: Int = 0
    private var pendY: Int = 0
    private var moveQueued: Boolean = false

    // ------------------------------------------------------------------ set-up

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("remote", MODE_PRIVATE)
        host = prefs.getString("host", "") ?: ""
        port = prefs.getInt("port", 47801)
        token = prefs.getString("token", "") ?: ""
        if (host.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "Connect to your PC first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        running = true
        if (viewToken.isEmpty()) askPin("") else startStreams()
    }

    override fun onStop() {
        super.onStop()
        running = false
        stopStreams()
        hideKeyboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        stopStreams()
        pinDialog?.dismiss()
        cmd.shutdown()
        work.shutdown()
    }

    override fun onBackPressed() {
        if (kbPanel.visibility == View.VISIBLE) {
            hideKeyboard()
        } else {
            finish()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun lostToast() {
        val now: Long = System.currentTimeMillis()
        if (now - lastLostToast > 4000L) {
            lastLostToast = now
            toast("Lost the connection to your PC")
        }
    }

    private fun badPairing() {
        toast("Please connect again")
        finish()
    }

    // ------------------------------------------------------------------ the layout

    private fun toolButton(text: String): TextView {
        val b = pill(this, text, C.CARD, C.TEXT, 12f, 12)
        b.maxLines = 1
        return b
    }

    private fun styleToggle(b: TextView, on: Boolean) {
        b.background = withRipple(this, if (on) C.ACCENT else C.CARD, 12)
        b.setTextColor(if (on) C.ON_ACCENT else C.TEXT)
    }

    private fun buildUi() {
        val ctx = this
        val root = FrameLayout(ctx)
        root.setBackgroundColor(C.BG)
        root.fitsSystemWindows = true

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL

        screenView = ScreenView(ctx, this)
        screenView.message = "Connecting..."
        col.addView(screenView, linp(MATCH, 0, 1f, 0, 0, 0, 0))

        kbPanel = buildKeyboardPanel()
        kbPanel.visibility = View.GONE
        col.addView(kbPanel, linp(MATCH, WRAP))

        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
        keysBtn = toolButton("Keys")
        scrollBtn = toolButton("Scroll")
        soundBtn = toolButton("Sound")
        qualityBtn = toolButton(qualityNames[qualityIndex])
        saveBtn = toolButton("Save")
        val closeBtn = toolButton("Close")
        val all = arrayOf(keysBtn, scrollBtn, soundBtn, qualityBtn, saveBtn, closeBtn)
        for (b in all) {
            bar.addView(b, linp(0, dp(ctx, 44), 1f, dp(ctx, 3), 0, dp(ctx, 3), 0))
        }
        col.addView(bar, linp(MATCH, WRAP))
        styleToggle(soundBtn, soundOn)

        keysBtn.setOnClickListener {
            if (kbPanel.visibility == View.VISIBLE) hideKeyboard() else showKeyboard()
        }
        scrollBtn.setOnClickListener {
            screenView.scrollMode = !screenView.scrollMode
            styleToggle(scrollBtn, screenView.scrollMode)
            toast(if (screenView.scrollMode) "Scroll on: slide one finger to scroll" else "Scroll off")
        }
        soundBtn.setOnClickListener {
            soundOn = !soundOn
            styleToggle(soundBtn, soundOn)
            if (soundOn) {
                if (viewToken.isNotEmpty()) startAudio()
            } else {
                stopAudio()
            }
        }
        qualityBtn.setOnClickListener {
            qualityIndex = (qualityIndex + 1) % qualityNames.size
            qualityBtn.text = qualityNames[qualityIndex]
            if (viewToken.isNotEmpty()) startVideo()
        }
        saveBtn.setOnClickListener { saveScreenshot() }
        closeBtn.setOnClickListener { finish() }

        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    // ------------------------------------------------------------------ the keyboard

    private fun buildKeyboardPanel(): LinearLayout {
        val ctx = this
        val mark = "​" // an invisible character, so the Backspace key still works in an empty box
        val panel = LinearLayout(ctx)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), 0)

        val box = EditText(ctx)
        box.setText(mark)
        box.setTextColor(C.TEXT)
        box.textSize = 16f
        box.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        box.background = rounded(ctx, C.CARD, 14)
        box.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        box.maxLines = 2
        panel.addView(box, linp(MATCH, WRAP))
        kbBox = box

        val keys = LinearLayout(ctx)
        keys.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("Esc", "Tab", "Enter", "Del", "←", "↑", "↓", "→")
        val keyNames = arrayOf("esc", "tab", "enter", "delete", "left", "up", "down", "right")
        for (i in names.indices) {
            val b = pill(ctx, names[i], C.CARD, C.TEXT, 13f, 10)
            val keyName: String = keyNames[i]
            b.setOnClickListener { send("/key?name=" + keyName + "&count=1") }
            keys.addView(b, linp(0, dp(ctx, 40), 1f, if (i > 0) dp(ctx, 4) else 0, 0, 0, 0))
        }
        panel.addView(keys, linp(MATCH, WRAP, 0f, 0, dp(ctx, 6), 0, 0))

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
                    send("/key?name=backspace&count=1")
                } else {
                    var common = 0
                    val limit: Int = Math.min(typed.length, now.length)
                    while (common < limit && typed[common] == now[common]) common++
                    // count whole characters, not UTF-16 halves, so emoji delete properly
                    val tail: String = typed.substring(common)
                    var deleted: Int = tail.codePointCount(0, tail.length)
                    while (deleted > 0) {
                        val n: Int = Math.min(deleted, 60)
                        send("/key?name=backspace&count=" + n)
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
        return panel
    }

    private fun showKeyboard() {
        kbPanel.visibility = View.VISIBLE
        styleToggle(keysBtn, true)
        kbBox.requestFocus()
        kbBox.postDelayed({
            kbBox.setSelection(kbBox.text.length)
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(kbBox, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) {
            }
        }, 200L)
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(kbBox.windowToken, 0)
        } catch (e: Exception) {
        }
        kbPanel.visibility = View.GONE
        styleToggle(keysBtn, false)
    }

    // ------------------------------------------------------------------ the screen PIN

    private fun askPin(error: String) {
        if (pinDialog != null) return
        val ctx = this
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = rounded(ctx, C.CARD, 28)
        card.setPadding(dp(ctx, 24), dp(ctx, 28), dp(ctx, 24), dp(ctx, 24))

        val t = label(ctx, "Screen PIN", 24f, C.TEXT, true)
        t.gravity = Gravity.CENTER
        card.addView(t, linp(MATCH, WRAP))
        val m = label(ctx, "Type the 4-digit PIN shown in the Phone Remote window on your PC. Click its icon near the PC's clock to open it.", 15f, C.MUTED, false)
        m.gravity = Gravity.CENTER
        card.addView(m, linp(MATCH, WRAP, 0f, 0, dp(ctx, 10), 0, dp(ctx, 16)))

        val input = EditText(ctx)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(4))
        input.setTextColor(C.TEXT)
        input.textSize = 28f
        input.gravity = Gravity.CENTER
        input.letterSpacing = 0.3f
        input.background = rounded(ctx, C.CARD2, 16)
        input.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        card.addView(input, linp(MATCH, WRAP))

        val status = label(ctx, error, 14f, C.DANGER, false)
        status.gravity = Gravity.CENTER
        card.addView(status, linp(MATCH, WRAP, 0f, 0, dp(ctx, 10), 0, 0))

        val ok = pill(ctx, "Unlock", C.ACCENT, C.ON_ACCENT, 17f, 18)
        card.addView(ok, linp(MATCH, dp(ctx, 56), 0f, 0, dp(ctx, 16), 0, 0))
        val no = pill(ctx, "Cancel", C.CARD2, C.TEXT, 17f, 18)
        card.addView(no, linp(MATCH, dp(ctx, 56), 0f, 0, dp(ctx, 10), 0, 0))

        dlg.setContentView(card)
        val w = dlg.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(resources.displayMetrics.widthPixels - dp(ctx, 48), WRAP)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dlg.setOnCancelListener { finish() }
        dlg.setOnDismissListener { pinDialog = null }
        no.setOnClickListener {
            dlg.dismiss()
            finish()
        }
        ok.setOnClickListener {
            val pin: String = input.text.toString().trim()
            if (pin.length != 4) {
                status.text = "Type the 4 digits shown on your PC."
                return@setOnClickListener
            }
            ok.isEnabled = false
            ok.alpha = 0.6f
            status.setTextColor(C.MUTED)
            status.text = "Checking..."
            val h = host
            val p = port
            val tk = token
            work.execute {
                val r: Net.Reply? = Net.call(h, p, "POST", "/screen/unlock", tk, "pin=" + pin)
                handler.post {
                    ok.isEnabled = true
                    ok.alpha = 1f
                    status.setTextColor(C.DANGER)
                    if (r == null) {
                        status.text = "Couldn't reach your PC."
                    } else if (r.code == 200) {
                        var tokenOk = false
                        try {
                            viewToken = JSONObject(r.body).getString("view")
                            tokenOk = true
                        } catch (e: Exception) {
                        }
                        if (tokenOk) {
                            dlg.dismiss()
                            if (running) startStreams()
                        } else {
                            status.text = "Something went wrong. Please try again."
                        }
                    } else if (r.code == 403) {
                        status.text = "That PIN isn't right. Check the PIN on your PC."
                        input.setText("")
                    } else if (r.code == 429) {
                        status.text = "Too many tries. Wait a minute and try again."
                    } else if (r.code == 401) {
                        dlg.dismiss()
                        badPairing()
                    } else {
                        status.text = "Something went wrong. Please try again."
                    }
                }
            }
        }
        pinDialog = dlg
        dlg.show()
    }

    /** Handles the PC saying no. Returns true if there is no point trying again. */
    private fun badCode(code: Int): Boolean {
        if (code == 401) {
            handler.post { badPairing() }
            return true
        }
        if (code == 403) {
            viewToken = ""
            handler.post { if (running) askPin("") }
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ the live picture and the sound

    private fun setMessage(text: String) {
        handler.post {
            screenView.message = text
            screenView.invalidate()
        }
    }

    private fun closeLater(c: HttpURLConnection?) {
        if (c == null) return
        work.execute {
            try {
                c.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    private fun startStreams() {
        startVideo()
        if (soundOn) startAudio()
    }

    private fun stopStreams() {
        videoGen++
        closeLater(videoConn)
        stopAudio()
    }

    private fun stopAudio() {
        audioGen++
        closeLater(audioConn)
    }

    private fun startVideo() {
        videoGen++
        closeLater(videoConn)
        val g: Int = videoGen
        val h = host
        val p = port
        val tk = token
        work.execute {
            while (g == videoGen && running) {
                val q: Int = qualityIndex
                val path = "/screen/stream?w=" + qualityWidth[q] + "&q=" + qualityJpeg[q] + "&fps=" + qualityFps[q]
                var conn: HttpURLConnection? = null
                try {
                    conn = Net.open(h, p, path, tk, viewToken, 8000)
                    videoConn = conn
                    val code: Int = conn.responseCode
                    if (code != 200) {
                        if (badCode(code)) return@execute
                        throw IOException("code " + code)
                    }
                    val ins = DataInputStream(BufferedInputStream(conn.inputStream, 65536))
                    val hdr = ByteArray(5)
                    while (g == videoGen) {
                        ins.readFully(hdr)
                        val kind: Int = hdr[0].toInt()
                        val len: Int = ((hdr[1].toInt() and 0xFF) shl 24) or
                            ((hdr[2].toInt() and 0xFF) shl 16) or
                            ((hdr[3].toInt() and 0xFF) shl 8) or
                            (hdr[4].toInt() and 0xFF)
                        if (len < 0 || len > 20000000) throw IOException("bad picture size")
                        if (len == 0) continue
                        val buf = ByteArray(len)
                        ins.readFully(buf)
                        if (kind == 1) {
                            val bmp = BitmapFactory.decodeByteArray(buf, 0, len)
                            if (bmp != null) {
                                screenView.frame = bmp
                                if (screenView.message.isNotEmpty()) setMessage("")
                                screenView.postInvalidateOnAnimation()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (g != videoGen || !running) return@execute
                    setMessage("Lost the connection. Trying again...")
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (e: Exception) {
                    }
                }
                if (g == videoGen) {
                    try {
                        Thread.sleep(1500L)
                    } catch (e: InterruptedException) {
                        return@execute
                    }
                }
            }
        }
    }

    private fun startAudio() {
        audioGen++
        closeLater(audioConn)
        val g: Int = audioGen
        val h = host
        val p = port
        val tk = token
        work.execute {
            while (g == audioGen && running && soundOn) {
                var conn: HttpURLConnection? = null
                var track: AudioTrack? = null
                try {
                    conn = Net.open(h, p, "/audio/stream", tk, viewToken, 8000)
                    audioConn = conn
                    val code: Int = conn.responseCode
                    if (code == 500) {
                        // the PC has no sound to share
                        soundOn = false
                        handler.post {
                            styleToggle(soundBtn, false)
                            toast("Couldn't get sound from your PC")
                        }
                        return@execute
                    }
                    if (code != 200) {
                        if (badCode(code)) return@execute
                        throw IOException("code " + code)
                    }
                    val ins = DataInputStream(BufferedInputStream(conn.inputStream, 32768))
                    val hdr = ByteArray(12)
                    ins.readFully(hdr)
                    if (hdr[0].toInt() != 'P'.code || hdr[1].toInt() != 'R'.code || hdr[2].toInt() != 'A'.code || hdr[3].toInt() != '1'.code) {
                        throw IOException("not sound")
                    }
                    val rate: Int = (hdr[4].toInt() and 0xFF) or
                        ((hdr[5].toInt() and 0xFF) shl 8) or
                        ((hdr[6].toInt() and 0xFF) shl 16) or
                        ((hdr[7].toInt() and 0xFF) shl 24)
                    if (rate < 8000 || rate > 192000) throw IOException("odd sound rate")

                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    val fmt = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                    val minBuf: Int = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    // about an eighth of a second: small, so the sound stays close to the picture
                    val bufBytes: Int = Math.max(minBuf, rate / 8 * 4)
                    val t = AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bufBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    track = t

                    val buf = ByteArray(16384)
                    var have = 0
                    var playing = false
                    while (g == audioGen) {
                        val n: Int = ins.read(buf, have, buf.size - have)
                        if (n < 0) break
                        have += n
                        val usable: Int = have - (have % 4) // whole left+right pairs only
                        if (usable > 0) {
                            t.write(buf, 0, usable)
                            if (!playing) {
                                t.play()
                                playing = true
                            }
                            System.arraycopy(buf, usable, buf, 0, have - usable)
                            have -= usable
                        }
                    }
                } catch (e: Exception) {
                    if (g != audioGen || !running) return@execute
                } finally {
                    try {
                        track?.stop()
                    } catch (e: Exception) {
                    }
                    try {
                        track?.release()
                    } catch (e: Exception) {
                    }
                    try {
                        conn?.disconnect()
                    } catch (e: Exception) {
                    }
                }
                if (g == audioGen) {
                    try {
                        Thread.sleep(1500L)
                    } catch (e: InterruptedException) {
                        return@execute
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ touches from the picture

    private fun send(path: String) {
        val h = host
        val p = port
        val tk = token
        cmd.execute {
            val r: Net.Reply? = Net.call(h, p, "POST", path, tk, "")
            if (r == null) {
                handler.post { lostToast() }
            } else if (r.code == 401) {
                handler.post { badPairing() }
            }
        }
    }

    private fun sendText(text: String) {
        val h = host
        val p = port
        val tk = token
        cmd.execute {
            val form: String = "text=" + URLEncoder.encode(text, "UTF-8")
            val r: Net.Reply? = Net.call(h, p, "POST", "/type", tk, form)
            if (r == null) {
                handler.post { lostToast() }
            } else if (r.code == 401) {
                handler.post { badPairing() }
            }
        }
    }

    private fun spot(fx: Float, fy: Float): String {
        val x: Int = Math.round(fx * 10000f).coerceIn(0, 10000)
        val y: Int = Math.round(fy * 10000f).coerceIn(0, 10000)
        return "x=" + x + "&y=" + y
    }

    override fun onPointer(fx: Float, fy: Float) {
        val x: Int = Math.round(fx * 10000f).coerceIn(0, 10000)
        val y: Int = Math.round(fy * 10000f).coerceIn(0, 10000)
        synchronized(moveLock) {
            pendX = x
            pendY = y
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
                mx = pendX
                my = pendY
                moveQueued = false
            }
            val r: Net.Reply? = Net.call(h, p, "POST", "/mouse/abs?x=" + mx + "&y=" + my, tk, "")
            if (r == null) {
                handler.post { lostToast() }
            } else if (r.code == 401) {
                handler.post { badPairing() }
            }
        }
    }

    override fun onTap(fx: Float, fy: Float, right: Boolean) {
        send("/mouse/abs?" + spot(fx, fy) + "&click=" + (if (right) "right" else "left"))
    }

    override fun onHoldStart(fx: Float, fy: Float) {
        send("/mouse/abs?" + spot(fx, fy))
        send("/mouse/button?button=left&action=down")
    }

    override fun onHoldEnd(fx: Float, fy: Float) {
        send("/mouse/abs?" + spot(fx, fy))
        send("/mouse/button?button=left&action=up")
    }

    override fun onWheel(wheel: Int) {
        send("/mouse/scroll?dy=" + wheel)
    }

    // ------------------------------------------------------------------ saving a screenshot

    private fun saveScreenshot() {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Saving screenshots needs Android 10 or newer")
            return
        }
        toast("Saving a screenshot...")
        val h = host
        val p = port
        val tk = token
        val vt = viewToken
        work.execute {
            val r: Net.BytesReply? = Net.callBytes(h, p, "/screen/shot?w=3840&q=90", tk, vt)
            if (r == null) {
                handler.post { toast("Couldn't reach your PC") }
            } else if (r.code == 403 || r.code == 401) {
                badCode(r.code)
            } else if (r.code != 200 || r.body.isEmpty()) {
                handler.post { toast("Couldn't take the screenshot") }
            } else {
                val saved: Boolean = saveJpeg(r.body)
                handler.post { toast(if (saved) "Saved to Pictures/PhoneRemote" else "Couldn't save the screenshot") }
            }
        }
    }

    private fun saveJpeg(data: ByteArray): Boolean {
        try {
            val name: String = "PC-screen-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhoneRemote")
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            val out = contentResolver.openOutputStream(uri) ?: return false
            out.use { it.write(data) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
