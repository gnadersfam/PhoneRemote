package com.phoneremote.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs quietly in the background (with a small notification).
 * It tells the PC where this phone is, and when the PC asks, it makes the phone ring loudly.
 */
class RingService : Service() {

    companion object {
        const val PORT: Int = 47802
        const val ACTION_STOP: String = "com.phoneremote.app.STOP_RING"
        const val CH_LISTEN: String = "listen"
        const val CH_RING: String = "ring"
        const val ID_LISTEN: Int = 1
        const val ID_RING: Int = 2

        @Volatile
        var ringing: Boolean = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val reg: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var alive: Boolean = false
    private var started: Boolean = false
    private var server: ServerSocket? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var savedAlarmVol: Int = -1
    private var wifiLock: WifiManager.WifiLock? = null

    private val autoStop: Runnable = Runnable { stopRinging() }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val listen = NotificationChannel(CH_LISTEN, "Phone Remote is on", NotificationManager.IMPORTANCE_LOW)
        listen.description = "Lets your PC find this phone if you lose it"
        listen.setShowBadge(false)
        nm.createNotificationChannel(listen)

        val ring = NotificationChannel(CH_RING, "Find my phone", NotificationManager.IMPORTANCE_HIGH)
        ring.description = "Shown while your PC is ringing this phone"
        ring.setSound(null, null)
        ring.enableVibration(false)
        ring.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ring)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showListeningNotification()
        if (intent != null && ACTION_STOP == intent.action) {
            stopRinging()
        }
        if (!started) {
            started = true
            alive = true
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhoneRemote")
                lock.setReferenceCounted(false)
                lock.acquire()
                wifiLock = lock
            } catch (e: Exception) {
            }
            Thread { serverLoop() }.start()
        }
        registerNow()
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        try {
            server?.close()
        } catch (e: Exception) {
        }
        stopRinging()
        try {
            wifiLock?.release()
        } catch (e: Exception) {
        }
        reg.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ the small permanent notification

    private fun showListeningNotification() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CH_LISTEN)
            .setSmallIcon(R.drawable.ic_ring)
            .setContentTitle("Phone Remote is on")
            .setContentText("Your PC can find this phone if you lose it")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_LISTEN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_LISTEN, n)
        }
    }

    // ------------------------------------------------------------------ talking with the PC

    /** Tells the PC program where this phone is. */
    private fun registerNow() {
        try {
            reg.execute {
                val p = getSharedPreferences("remote", Context.MODE_PRIVATE)
                val h: String = p.getString("host", "") ?: ""
                val pt: Int = p.getInt("port", 47801)
                val tk: String = p.getString("token", "") ?: ""
                if (h.isNotEmpty() && tk.isNotEmpty()) {
                    Net.call(h, pt, "POST", "/register?port=" + PORT, tk, "")
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun serverLoop() {
        var lastReg: Long = System.currentTimeMillis()
        while (alive) {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                ss.soTimeout = 30000
                server = ss
                while (alive) {
                    try {
                        val s: Socket = ss.accept()
                        handleClient(s)
                    } catch (e: SocketTimeoutException) {
                    }
                    val now: Long = System.currentTimeMillis()
                    if (now - lastReg > 60000L) {
                        lastReg = now
                        registerNow()
                    }
                }
            } catch (e: Exception) {
            } finally {
                try {
                    ss?.close()
                } catch (e: Exception) {
                }
            }
            if (alive) {
                try {
                    Thread.sleep(3000L)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun handleClient(s: Socket) {
        try {
            s.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val first: String = reader.readLine() ?: return
            var tok = ""
            var len = 0
            while (true) {
                val line: String = reader.readLine() ?: break
                if (line.isEmpty()) break
                val i: Int = line.indexOf(':')
                if (i > 0) {
                    val k: String = line.substring(0, i).trim().lowercase()
                    val v: String = line.substring(i + 1).trim()
                    if (k == "x-token") tok = v
                    if (k == "content-length") len = v.toIntOrNull() ?: 0
                }
            }
            var left: Int = len
            while (left > 0) {
                if (reader.read() < 0) break
                left--
            }

            val saved: String = getSharedPreferences("remote", Context.MODE_PRIVATE).getString("token", "") ?: ""
            val parts: List<String> = first.split(" ")
            var path: String = if (parts.size > 1) parts[1] else ""
            val q: Int = path.indexOf('?')
            if (q >= 0) path = path.substring(0, q)

            var status = "200 OK"
            if (saved.isEmpty() || tok != saved) {
                status = "401 Unauthorized"
            } else if (path == "/ring") {
                handler.post { startRinging() }
            } else if (path == "/stop") {
                handler.post { stopRinging() }
            } else if (path != "/ping") {
                status = "404 Not Found"
            }
            val reply = "HTTP/1.1 " + status + "\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
            val out = s.getOutputStream()
            out.write(reply.toByteArray())
            out.flush()
        } catch (e: Exception) {
        } finally {
            try {
                s.close()
            } catch (e: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ ringing

    private fun startRinging() {
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, 180000L)
        if (ringing) return
        ringing = true

        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: Exception) {
        }

        try {
            var uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri!!)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
        }

        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 800L, 600L), 0))
            vibrator = v
        } catch (e: Exception) {
        }

        try {
            val screen = PendingIntent.getActivity(
                this, 1,
                Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val stop = PendingIntent.getService(
                this, 2,
                Intent(this, RingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val action = Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_ring), "Stop ringing", stop).build()
            val n: Notification = Notification.Builder(this, CH_RING)
                .setSmallIcon(R.drawable.ic_ring)
                .setContentTitle("Your PC is ringing this phone")
                .setContentText("Tap to stop")
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setFullScreenIntent(screen, true)
                .setContentIntent(screen)
                .addAction(action)
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ID_RING, n)
        } catch (e: Exception) {
        }

        try {
            startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }

    private fun stopRinging() {
        handler.removeCallbacks(autoStop)
        ringing = false
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
        }
        vibrator = null
        if (savedAlarmVol >= 0) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVol, 0)
            } catch (e: Exception) {
            }
            savedAlarmVol = -1
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ID_RING)
        } catch (e: Exception) {
        }
    }
}
