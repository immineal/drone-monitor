package com.dronemonitor

import android.content.Context
import android.util.Log
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Talks to the drone over its own Wi-Fi AP and drives the decoder.
 *
 * Channels (all reverse-engineered and confirmed against the live drone):
 *   TCP gateway:8888  video    -> preamble 01 02 03 04 05 06 07 08 09 28 28, resent every 1s
 *   TCP gateway:8888  command  -> preamble 00 01 02 03 04 05 06 07 08 09 25 25, resent every 1s
 *   UDP gateway:8080  control  -> 0F discover, 09+localip keepalive, 27 request-I-frame
 *
 * The phone drifts off the (internet-less) drone AP unless traffic is pinned to it,
 * so we request the Wi-Fi network explicitly and bind the process to it.
 */
class DroneClient(
    private val context: Context,
    private val surface: Surface,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(text: String)
        fun onFirstFrame()
        fun onResolution(width: Int, height: Int)
    }

    companion object {
        const val VIDEO_PORT = 8888
        const val CMD_UDP_PORT = 8080
        val HB_VIDEO_CAM0 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 40, 40)
        val HB_VIDEO_CAM1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 48, 48)
        val HB_COMMAND = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 37, 37)
        val UDP_DISCOVER = byteArrayOf(0x0F)
        val UDP_IFRAME = byteArrayOf(0x27)
        // Camera gimbal (VISON camera module, UDP 8080, no checksum). angle 0..90 deg, 0=forward, 90=down.
        val GIMBAL_QUERY = byteArrayOf(0xFF.toByte(), 0x53, 0x54, 0x15, 0x01)
        const val GIMBAL_MIN = 0
        const val GIMBAL_MAX = 90
    }

    @Volatile private var gimbalAngle = 0

    /** Move the camera to an absolute tilt angle (0 = forward, 90 = straight down). */
    fun setGimbalAngle(angle: Int) {
        val a = angle.coerceIn(GIMBAL_MIN, GIMBAL_MAX)
        gimbalAngle = a
        sendUdpCommand(byteArrayOf(0xFF.toByte(), 0x53, 0x54, 0x20, 0x01, a.toByte()))
    }

    /** Nudge the camera tilt by a relative amount and return the new angle. */
    fun nudgeGimbal(delta: Int): Int {
        setGimbalAngle(gimbalAngle + delta)
        return gimbalAngle
    }

    fun gimbalAngle(): Int = gimbalAngle

    private val running = AtomicBoolean(false)
    private val firstFrameSignalled = AtomicBoolean(false)
    private var sawKeyFrame = false
    @Volatile private var lastVideoByteMs = 0L

    @Volatile private var statBytes = 0L
    @Volatile private var statFed = 0L
    @Volatile private var statKey = 0L
    @Volatile private var statSkipped = 0L

    private val TAG = "DroneMon"

    private var gateway = "172.16.10.1"
    private var localIpBytes = byteArrayOf(172.toByte(), 16, 10, 2)

    private var cm: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var boundNetwork: Network? = null

    private val threads = mutableListOf<Thread>()
    @Volatile private var videoOut: java.io.OutputStream? = null
    @Volatile private var cmdOut: java.io.OutputStream? = null
    @Volatile private var udpSocket: DatagramSocket? = null

    private val decoder: VideoDecoder = VideoDecoder(surface) { w, h ->
        listener.onResolution(w, h)
    }

    fun start() {
        if (running.getAndSet(true)) return
        try { decoder.start() } catch (_: Exception) {}
        listener.onStatus("Looking for drone Wi-Fi…")
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm = connectivity
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!running.get()) return
                if (boundNetwork != null) return
                boundNetwork = network
                connectivity.bindProcessToNetwork(network)
                resolveAddresses()
                listener.onStatus("Connecting to $gateway…")
                launchWorkers()
            }
            override fun onLost(network: Network) {
                if (network == boundNetwork) {
                    listener.onStatus("Wi-Fi lost — waiting…")
                }
            }
        }
        netCallback = cb
        connectivity.requestNetwork(request, cb)
    }

    private fun resolveAddresses() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val dhcp = wifi.dhcpInfo
            if (dhcp != null) {
                if (dhcp.gateway != 0) gateway = intToIp(dhcp.gateway)
                if (dhcp.ipAddress != 0) localIpBytes = intToBytes(dhcp.ipAddress)
            }
        } catch (_: Exception) {
        }
    }

    private fun launchWorkers() {
        startThread("video") { videoLoop() }
        startThread("command") { commandLoop() }
        startThread("udp") { udpLoop() }
        startThread("watchdog") { watchdogLoop() }
        startThread("stats") { statsLoop() }
    }

    private fun statsLoop() {
        var lastBytes = 0L; var lastFed = 0L; var lastRendered = 0L; var lastKey = 0L; var lastSkip = 0L
        while (running.get()) {
            sleep(1000)
            val b = statBytes; val f = statFed; val r = decoder.framesRendered(); val k = statKey; val s = statSkipped
            Log.d(TAG, "1s read=${b - lastBytes}B fed=${f - lastFed} rendered=${r - lastRendered} iframe=${k - lastKey} skipped=${s - lastSkip}")
            lastBytes = b; lastFed = f; lastRendered = r; lastKey = k; lastSkip = s
        }
    }

    private fun startThread(name: String, body: () -> Unit) {
        val t = Thread({
            while (running.get()) {
                try {
                    Log.d(TAG, "thread $name (re)start")
                    body()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    Log.d(TAG, "thread $name exception: ${e.javaClass.simpleName} ${e.message}")
                }
                if (running.get()) sleep(1200)
            }
        }, "drone-$name")
        t.isDaemon = true
        threads.add(t)
        t.start()
    }

    // ---- video channel ----------------------------------------------------

    private fun videoLoop() {
        val socket = openSocket(gateway, VIDEO_PORT) ?: return
        socket.use { s ->
            s.soTimeout = 1000
            val out = s.getOutputStream()
            videoOut = out
            out.write(HB_VIDEO_CAM0); out.flush()
            var lastHb = System.currentTimeMillis()
            requestIFrame()
            lastVideoByteMs = System.currentTimeMillis()
            val parser = FrameParser { payload, type -> onVideoFrame(payload, type) }
            sawKeyFrame = false
            val inp = s.getInputStream()
            val buffer = ByteArray(65536)
            listener.onStatus("Waiting for video…")
            while (running.get()) {
                // The drone keeps streaming only while it receives this heartbeat ~every second.
                if (System.currentTimeMillis() - lastHb >= 1000) {
                    try { out.write(HB_VIDEO_CAM0); out.flush() } catch (e: Exception) { break }
                    lastHb = System.currentTimeMillis()
                }
                val n = try {
                    inp.read(buffer)
                } catch (te: SocketTimeoutException) {
                    if (System.currentTimeMillis() - lastVideoByteMs > 12000) break
                    -2
                }
                if (n == -1) { Log.d(TAG, "video socket closed (read -1)"); break }
                if (n == -2) { continue }
                if (n > 0) {
                    lastVideoByteMs = System.currentTimeMillis()
                    statBytes += n
                    parser.feed(buffer, n)
                }
            }
            videoOut = null
        }
    }

    private fun onVideoFrame(payload: ByteArray, frameType: Int) {
        if (frameType == 0xA1) statKey++
        if (!sawKeyFrame) {
            if (frameType == 0xA1 || containsKeyNal(payload)) {
                sawKeyFrame = true
            } else {
                statSkipped++
                return // skip leading P-frames until the first I-frame for a clean start
            }
        }
        statFed++
        decoder.feed(payload)
        if (firstFrameSignalled.compareAndSet(false, true)) {
            listener.onFirstFrame()
        }
    }

    private fun containsKeyNal(p: ByteArray): Boolean {
        var i = 0
        while (i + 4 < p.size) {
            if (p[i].toInt() == 0 && p[i + 1].toInt() == 0) {
                val sc3 = p[i + 2].toInt() == 1
                val sc4 = p[i + 2].toInt() == 0 && p[i + 3].toInt() == 1
                if (sc3 || sc4) {
                    val h = p[i + (if (sc3) 3 else 4)].toInt() and 0x1f
                    if (h == 5 || h == 7) return true
                    i += if (sc3) 3 else 4
                    continue
                }
            }
            i++
        }
        return false
    }

    // ---- command channels -------------------------------------------------

    private fun commandLoop() {
        val socket = openSocket(gateway, VIDEO_PORT) ?: return
        socket.use { s ->
            s.soTimeout = 3000
            val out = s.getOutputStream()
            cmdOut = out
            out.write(HB_COMMAND); out.flush()
            val inp = s.getInputStream()
            val buffer = ByteArray(8192)
            var last = System.currentTimeMillis()
            while (running.get()) {
                if (System.currentTimeMillis() - last > 1000) {
                    try { out.write(HB_COMMAND); out.flush() } catch (_: Exception) { break }
                    last = System.currentTimeMillis()
                }
                val n = try { inp.read(buffer) } catch (te: SocketTimeoutException) { 0 }
                if (n == -1) break
                // Telemetry (VISON FF FE…) arrives here; not needed for the monitor yet.
            }
            cmdOut = null
        }
    }

    private fun udpLoop() {
        val sock = DatagramSocket()
        sock.soTimeout = 1500
        udpSocket = sock
        val dst = InetSocketAddress(InetAddress.getByName(gateway), CMD_UDP_PORT)
        sock.use { s ->
            var last = 0L
            val rx = ByteArray(512)
            send(s, dst, UDP_DISCOVER)
            send(s, dst, GIMBAL_QUERY)
            while (running.get()) {
                val now = System.currentTimeMillis()
                if (now - last > 1000) {
                    val keep = ByteArray(5)
                    keep[0] = 0x09
                    System.arraycopy(localIpBytes, 0, keep, 1, 4)
                    send(s, dst, keep)
                    last = now
                }
                try {
                    val p = DatagramPacket(rx, rx.size)
                    s.receive(p)
                    parseUdpReply(rx, p.length)
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        udpSocket = null
    }

    private fun watchdogLoop() {
        while (running.get()) {
            sleep(1000)
            val idle = System.currentTimeMillis() - lastVideoByteMs
            if (lastVideoByteMs != 0L && idle in 4000..11999) {
                requestIFrame()
            }
        }
    }

    private fun parseUdpReply(data: ByteArray, len: Int) {
        // Camera-module reply FF 53 54 <op> ... ; op 0x15 carries the current gimbal angle.
        if (len >= 6 &&
            (data[0].toInt() and 0xff) == 0xFF &&
            (data[1].toInt() and 0xff) == 0x53 &&
            (data[2].toInt() and 0xff) == 0x54 &&
            (data[3].toInt() and 0xff) == 0x15
        ) {
            gimbalAngle = (data[5].toInt() and 0xff).coerceIn(GIMBAL_MIN, GIMBAL_MAX)
        }
    }

    private fun requestIFrame() {
        val s = udpSocket ?: return
        try {
            val dst = InetSocketAddress(InetAddress.getByName(gateway), CMD_UDP_PORT)
            send(s, dst, UDP_IFRAME)
        } catch (_: Exception) {
        }
    }

    /** Send a control command on the TCP command channel (for gimbal etc.). */
    fun sendTcpCommand(bytes: ByteArray) {
        try { cmdOut?.let { it.write(bytes); it.flush() } } catch (_: Exception) {}
    }

    /** Send a control command on the UDP channel. */
    fun sendUdpCommand(bytes: ByteArray) {
        val s = udpSocket ?: return
        try {
            send(s, InetSocketAddress(InetAddress.getByName(gateway), CMD_UDP_PORT), bytes)
        } catch (_: Exception) {}
    }

    private fun send(s: DatagramSocket, dst: InetSocketAddress, data: ByteArray) {
        s.send(DatagramPacket(data, data.size, dst))
    }

    private fun openSocket(host: String, port: Int): Socket? {
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 8000)
            s
        } catch (_: Exception) {
            null
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        threads.forEach { it.interrupt() }
        threads.clear()
        try { udpSocket?.close() } catch (_: Exception) {}
        try { decoder.release() } catch (_: Exception) {}
        val connectivity = cm
        val cb = netCallback
        if (connectivity != null && cb != null) {
            try { connectivity.unregisterNetworkCallback(cb) } catch (_: Exception) {}
            try { connectivity.bindProcessToNetwork(null) } catch (_: Exception) {}
        }
        netCallback = null
        boundNetwork = null
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { throw InterruptedException() }
    }

    private fun intToIp(v: Int): String =
        "${v and 0xff}.${(v shr 8) and 0xff}.${(v shr 16) and 0xff}.${(v shr 24) and 0xff}"

    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())
}
