package com.phoneremote.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

class PcInfo(val host: String, val port: Int, val name: String)

/** Everything the phone says to the PC program goes through here. */
object Net {
    private const val DISCOVERY_PORT: Int = 47800

    class Reply(val code: Int, val body: String)

    /** Shouts across the Wi-Fi and waits for the PC program to answer. */
    fun discover(timeoutMs: Int): PcInfo? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 700
            val msg: ByteArray = "PHONEREMOTE?".toByteArray()
            val targets = ArrayList<InetAddress>()
            targets.add(InetAddress.getByName("255.255.255.255"))
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b: InetAddress? = ia.broadcast
                    if (b != null) targets.add(b)
                }
            }
            val end: Long = System.currentTimeMillis() + timeoutMs
            var nextSend: Long = 0L
            val buf = ByteArray(256)
            while (System.currentTimeMillis() < end) {
                val now: Long = System.currentTimeMillis()
                if (now >= nextSend) {
                    for (t in targets) {
                        try {
                            socket.send(DatagramPacket(msg, msg.size, t, DISCOVERY_PORT))
                        } catch (e: Exception) {
                        }
                    }
                    nextSend = now + 800L
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val text = String(p.data, 0, p.length)
                    if (text.startsWith("PHONEREMOTE!")) {
                        val rest: String = text.substring(12)
                        val bar: Int = rest.indexOf('|')
                        val portText: String = if (bar >= 0) rest.substring(0, bar) else rest
                        val port: Int? = portText.trim().toIntOrNull()
                        val name: String = if (bar >= 0) rest.substring(bar + 1) else "My PC"
                        val addr: String? = p.address?.hostAddress
                        if (port != null && addr != null) {
                            return PcInfo(addr, port, name)
                        }
                    }
                } catch (e: SocketTimeoutException) {
                }
            }
        } catch (e: Exception) {
        } finally {
            socket?.close()
        }
        return null
    }

    class BytesReply(val code: Int, val body: ByteArray)

    /**
     * Opens a connection that stays open (the live screen picture, or the sound).
     * The caller reads from it and closes it. Nothing is sent until the caller reads.
     */
    fun open(host: String, port: Int, path: String, token: String, view: String, readTimeoutMs: Int): HttpURLConnection {
        val conn = URL("http://" + host + ":" + port + path).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        conn.setRequestProperty("X-Token", token)
        conn.setRequestProperty("X-View", view)
        return conn
    }

    /** One request whose answer is a file (a picture), not text. Returns null if the PC could not be reached. */
    fun callBytes(host: String, port: Int, path: String, token: String, view: String): BytesReply? {
        var conn: HttpURLConnection? = null
        try {
            conn = open(host, port, path, token, view, 20000)
            val code: Int = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body: ByteArray = if (stream != null) stream.use { it.readBytes() } else ByteArray(0)
            return BytesReply(code, body)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    /** One request to the PC. Returns null if the PC could not be reached. */
    fun call(host: String, port: Int, method: String, path: String, token: String?, form: String?): Reply? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("http://" + host + ":" + port + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 2500
            conn.readTimeout = 4000
            if (token != null) conn.setRequestProperty("X-Token", token)
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val data: ByteArray = (form ?: "").toByteArray()
                conn.outputStream.use { it.write(data) }
            }
            val code: Int = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            var body = ""
            if (stream != null) {
                body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            }
            return Reply(code, body)
        } catch (e: Exception) {
            // Keep a working connection open between requests (it makes the mouse feel quicker),
            // but drop one that has gone wrong.
            conn?.disconnect()
            return null
        }
    }
}
