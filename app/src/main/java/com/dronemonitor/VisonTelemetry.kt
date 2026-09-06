package com.dronemonitor

/**
 * Parses the drone's VISON telemetry off the TCP command stream.
 *
 * Frame: FF FE | LEN | MSGID | payload[LEN-2] | CK
 *   LEN counts MSGID + payload + CK (not the two header bytes); total = LEN + 3.
 *   CK = XOR(LEN, MSGID, payload…), seed 0x00.
 * Multi-byte telemetry fields are big-endian.
 */
class VisonTelemetry(private val onUpdate: (Telemetry) -> Unit) {

    data class Telemetry(
        var batteryVolts: Float = 0f,
        var satellites: Int = 0,
        var heightM: Float = 0f,
        var distanceM: Float = 0f,
        var hSpeed: Float = 0f,
        var vSpeed: Float = 0f,
        var armed: Boolean = false,
        var flying: Boolean = false,
        var gpsReady: Boolean = false,
        var lowBattery: Boolean = false,
        var visonCode: Int = -1,
        var rssiDbm: Int = 0,
        var hasData: Boolean = false
    )

    private val t = Telemetry()

    private var state = 0
    private var len = 0
    private var msgid = 0
    private val payload = ByteArray(260)
    private var pIdx = 0
    private var ck = 0

    fun setRssi(dbm: Int) {
        t.rssiDbm = dbm
        onUpdate(t.copy())
    }

    fun feed(data: ByteArray, n: Int) {
        for (i in 0 until n) {
            val b = data[i].toInt() and 0xff
            when (state) {
                0 -> if (b == 0xFF) state = 1
                1 -> state = if (b == 0xFE) 2 else if (b == 0xFF) 1 else 0
                2 -> { len = b; ck = b; state = 3 }
                3 -> { msgid = b; ck = ck xor b; pIdx = 0; state = if (len - 2 <= 0) 5 else 4 }
                4 -> {
                    if (pIdx < payload.size) payload[pIdx] = b.toByte()
                    pIdx++
                    ck = ck xor b
                    if (pIdx >= len - 2) state = 5
                }
                5 -> {
                    if ((ck and 0xff) == b) parse(msgid, payload, pIdx)
                    state = 0
                }
            }
        }
    }

    private fun be16(p: ByteArray, o: Int): Int = ((p[o].toInt() and 0xff) shl 8) or (p[o + 1].toInt() and 0xff)
    private fun beS16(p: ByteArray, o: Int): Int { val v = be16(p, o); return if (v >= 0x8000) v - 0x10000 else v }

    private fun parse(id: Int, p: ByteArray, plen: Int) {
        when (id) {
            0 -> if (plen >= 23) {
                t.batteryVolts = be16(p, 0) / 100f
                t.satellites = p[12].toInt() and 0xff
                t.distanceM = be16(p, 15) / 10f
                t.heightM = beS16(p, 17) / 10f
                t.hSpeed = beS16(p, 19) / 10f
                t.vSpeed = beS16(p, 21) / 10f
                t.hasData = true
                onUpdate(t.copy())
            }
            2 -> if (plen >= 3) {
                val s0 = p[0].toInt() and 0xff
                t.armed = (s0 and 0x01) != 0
                t.flying = (s0 and 0x02) != 0
                t.gpsReady = (s0 and 0x04) != 0
                t.lowBattery = (p[2].toInt() and 0x02) != 0
                onUpdate(t.copy())
            }
            6 -> if (plen >= 1) {
                t.visonCode = p[0].toInt() and 0xff
                onUpdate(t.copy())
            }
        }
    }
}
