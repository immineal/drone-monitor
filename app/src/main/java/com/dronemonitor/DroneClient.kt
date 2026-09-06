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
        fun onTelemetry(t: VisonTelemetry.Telemetry)
    }

    companion object {
        const val VIDEO_PORT = 8888
        const val CMD_UDP_PORT = 8080
        val HB_VIDEO_CAM0 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 40, 40)
        val HB_VIDEO_CAM1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 48, 48)
        val HB_COMMAND = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 37, 37)
        val UDP_DISCOVER = byteArrayOf(0x0F)
        val UDP_IFRAME = byteArrayOf(0x27)

        // Camera gimbal, VISON "camera-adjust" path (opcode FF FD 09 — NOT the motor frame FF FD 0C).
        // A streamed rate command: resend ~every 20ms while tilting, then stop on release.
        val GIMBAL_UP = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x09, 0x02, 0x01, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00, 0x03)
        val GIMBAL_DOWN = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x09, 0x02, 0x01, 0x01, 0x09, 0x00, 0x00, 0x00, 0x00, 0x02)
        val GIMBAL_STOP = byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0x09, 0x02, 0x01, 0x03, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00)
    }

    @Volatile private var gimbalActive = false
    @Volatile private var gimbalFrame: ByteArray = GIMBAL_STOP

    @Volatile private var videoHb: ByteArray = HB_VIDEO_CAM0
    @Volatile private var secondCamera = false
    @Volatile private var switchPending = false

    /** Switch between the main and second camera (twin-lens units). */
    fun switchCamera(): Boolean {
        secondCamera = !secondCamera
        videoHb = if (secondCamera) HB_VIDEO_CAM1 else HB_VIDEO_CAM0
        switchPending = true // the video loop resets the pipeline for the new camera's stream
        return secondCamera
    }

    /** Flip/mirror the image (UDP 0x02 on, 0x01 off). */
    fun setMirror(on: Boolean) {
        sendUdpCommand(byteArrayOf(if (on) 0x02 else 0x01))
    }

    /** Begin tilting; up=true tilts toward the horizon, up=false toward the ground. Hold to keep moving. */
    fun gimbalPress(up: Boolean) {
        gimbalFrame = if (up) GIMBAL_UP else GIMBAL_DOWN
        gimbalActive = true
    }

    /** Stop tilting and hold the current angle. */
    fun gimbalRelease() {
        gimbalActive = false
        repeat(3) { sendUdpCommand(GIMBAL_STOP) }
    }

    private fun gimbalLoop() {
        while (running.get()) {
            if (gimbalActive) sendUdpCommand(gimbalFrame)
            Thread.sleep(20)
        }
    }

    private val running = AtomicBoolean(false)
    private val firstFrameSignalled = AtomicBoolean(false)
    private var sawKeyFrame = false
    @Volatile private var lastVideoByteMs = 0L

    @Volatile private var statBytes = 0L
    @Volatile private var statFed = 0L
    @Volatile private var statKey = 0L
    @Volatile private var statSkipped = 0L

    private val TAG = "DroneMon"

    private var logWriter: java.io.Writer? = null
    private val logFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    private fun openLog() {
        try {
            val dir = context.getExternalFilesDir(null)
            val f = java.io.File(dir, "flight.log")
            logWriter = java.io.BufferedWriter(java.io.FileWriter(f, true))
            logEvent("---- session start ----")
        } catch (_: Exception) {
        }
    }

    private fun logEvent(msg: String) = writeLog("${logFmt.format(java.util.Date())}  $msg")

    @Synchronized
    private fun writeLog(line: String) {
        try {
            logWriter?.let { it.write(line); it.write("\n"); it.flush() }
        } catch (_: Exception) {
        }
    }

    private fun closeLog() {
        try { logEvent("---- session stop ----"); logWriter?.close() } catch (_: Exception) {}
        logWriter = null
    }

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

    private val telemetry = VisonTelemetry { t -> listener.onTelemetry(t) }

    fun start() {
        if (running.getAndSet(true)) return
        openLog()
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
        startThread("gimbal") { gimbalLoop() }
    }

    // ---- SD-card capture (TCP 8888 command channel) -----------------------

    /** Take a full-resolution photo onto the drone's SD card. */
    fun takePhoto() {
        sendTcpCommand(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x11, 0x11, 0x00, 0x00))
    }

    /** Start recording video onto the drone's SD card. */
    fun startRecord() {
        // The 8 date bytes are ASCII chars from a base-32 table; the drone uses them as the filename.
        val base32 = "0123456789ABCDEFGHIJKLMNOPQRSTUV"
        val ascii = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val c = java.util.Calendar.getInstance()
        val yy = c.get(java.util.Calendar.YEAR) % 100
        val mo = c.get(java.util.Calendar.MONTH) + 1
        val dd = c.get(java.util.Calendar.DAY_OF_MONTH)
        val hh = c.get(java.util.Calendar.HOUR_OF_DAY)
        val mm = c.get(java.util.Calendar.MINUTE)
        val ss = c.get(java.util.Calendar.SECOND)
        val date = byteArrayOf(
            base32[yy / 10].code.toByte(), base32[yy % 10].code.toByte(),
            base32[mo].code.toByte(), base32[dd].code.toByte(),
            base32[hh].code.toByte(), base32[mm / 10].code.toByte(), base32[mm % 10].code.toByte(),
            ascii[ss / 2].code.toByte()
        )
        val cmd = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x12, 0x12) + date + byteArrayOf(0x41, 0x56, 0x49)
        sendTcpCommand(cmd)
    }

    /** Stop recording. Payload is a FAT date/time stamp, big-endian 16-bit each. */
    fun stopRecord() {
        val c = java.util.Calendar.getInstance()
        val year = c.get(java.util.Calendar.YEAR)
        val mon = c.get(java.util.Calendar.MONTH) + 1
        val day = c.get(java.util.Calendar.DAY_OF_MONTH)
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val m = c.get(java.util.Calendar.MINUTE)
        val s = c.get(java.util.Calendar.SECOND)
        val fatDate = ((year - 1980) shl 9) or (mon shl 5) or day
        val fatTime = (h shl 11) or (m shl 5) or (s / 2)
        val cmd = byteArrayOf(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x13, 0x13,
            ((fatDate shr 8) and 0xff).toByte(), (fatDate and 0xff).toByte(),
            ((fatTime shr 8) and 0xff).toByte(), (fatTime and 0xff).toByte()
        )
        sendTcpCommand(cmd)
    }

    private fun statsLoop() {
        var lastBytes = 0L; var lastFed = 0L; var lastRendered = 0L; var lastKey = 0L; var lastSkip = 0L
        while (running.get()) {
            sleep(1000)
            var rssi = 0
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                rssi = wifi.connectionInfo.rssi
                telemetry.setRssi(rssi)
            } catch (_: Exception) {
            }
            val b = statBytes; val f = statFed; val r = decoder.framesRendered(); val k = statKey; val s = statSkipped
            val readKB = (b - lastBytes) / 1024
            val fps = r - lastRendered
            Log.d(TAG, "1s read=${b - lastBytes}B fed=${f - lastFed} rendered=$fps iframe=${k - lastKey} skipped=${s - lastSkip}")
            writeLog("${logFmt.format(java.util.Date())}  rssi=${rssi}dBm  read=${readKB}KB/s  fps=$fps  iframe=${k - lastKey}" + if (readKB == 0L) "  <STALL>" else "")
            lastBytes = b; lastFed = f; lastRendered = r; lastKey = k; lastSkip = s
        }
    }

    private fun startThread(name: String, body: () -> Unit) {
        val t = Thread({
            while (running.get()) {
                try {
                    Log.d(TAG, "thread $name (re)start")
                    if (name == "video") logEvent("video (re)connect")
                    body()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    Log.d(TAG, "thread $name exception: ${e.javaClass.simpleName} ${e.message}")
                    if (name == "video") logEvent("video error: ${e.javaClass.simpleName}")
                }
                if (running.get()) sleep(400)
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
            out.write(videoHb); out.flush()
            var lastHb = System.currentTimeMillis()
            requestIFrame()
            lastVideoByteMs = System.currentTimeMillis()
            val parser = FrameParser { payload, type -> onVideoFrame(payload, type) }
            sawKeyFrame = false
            val inp = s.getInputStream()
            val buffer = ByteArray(65536)
            listener.onStatus("Waiting for video…")
            while (running.get()) {
                if (switchPending) {
                    switchPending = false
                    parser.reset()
                    sawKeyFrame = false
                    decoder.reset()
                    try { out.write(videoHb); out.flush() } catch (_: Exception) {}
                    lastHb = System.currentTimeMillis()
                    requestIFrame()
                }
                // The drone keeps streaming only while it receives this heartbeat ~every second.
                if (System.currentTimeMillis() - lastHb >= 1000) {
                    try { out.write(videoHb); out.flush() } catch (e: Exception) { break }
                    lastHb = System.currentTimeMillis()
                }
                val n = try {
                    inp.read(buffer)
                } catch (te: SocketTimeoutException) {
                    // Reconnect fast: a >2s gap at any signal is a real stall, and the
                    // stream does not resume on its own — a quick reconnect makes a brief
                    // drone-side hiccup a ~2-3s blip instead of a long freeze.
                    if (System.currentTimeMillis() - lastVideoByteMs > 2000) {
                        logEvent("video stall >2s, reconnecting")
                        break
                    }
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
                if (n > 0) telemetry.feed(buffer, n) // VISON FF FE… telemetry
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
        closeLog()
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { throw InterruptedException() }
    }

    private fun intToIp(v: Int): String =
        "${v and 0xff}.${(v shr 8) and 0xff}.${(v shr 16) and 0xff}.${(v shr 24) and 0xff}"

    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())
}
