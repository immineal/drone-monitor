package com.dronemonitor

/**
 * Parses the drone's proprietary video container off a raw TCP byte stream.
 *
 * Wire format (reverse-engineered):
 *   per frame: 44-byte header then the H.264 elementary-stream payload.
 *   header[0..2] = 00 00 01 sync
 *   header[3]    = frame type: 0xA1 = I-frame, 0xA0/0xA2/0xA4 = other video
 *   header[16..19] = payload length, uint32 little-endian
 *   payload is Annex-B H.264 (in-band SPS/PPS on I-frames).
 *
 * TCP delivers the stream in arbitrary chunks, so this accumulates bytes and
 * emits one payload per complete frame, resynchronising if it ever loses the header.
 */
class FrameParser(private val onFrame: (payload: ByteArray, frameType: Int) -> Unit) {

    private var buf = ByteArray(1 shl 20)
    private var size = 0

    fun feed(data: ByteArray, len: Int) {
        ensure(size + len)
        System.arraycopy(data, 0, buf, size, len)
        size += len
        parse()
    }

    fun reset() {
        size = 0
    }

    private fun ensure(needed: Int) {
        if (needed <= buf.size) return
        var cap = buf.size
        while (cap < needed) cap = cap shl 1
        buf = buf.copyOf(cap)
    }

    private fun isType(b: Byte): Boolean {
        val t = b.toInt() and 0xff
        return t == 0xA0 || t == 0xA1 || t == 0xA2 || t == 0xA4 || t == 0xA5
    }

    private fun isSyncAt(i: Int): Boolean =
        buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1 && isType(buf[i + 3])

    private fun findSync(from: Int): Int {
        var i = from
        while (i + 4 <= size) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1 && isType(buf[i + 3])) return i
            i++
        }
        return -1
    }

    private fun leU32(i: Int): Long {
        return ((buf[i].toLong() and 0xff)) or
            ((buf[i + 1].toLong() and 0xff) shl 8) or
            ((buf[i + 2].toLong() and 0xff) shl 16) or
            ((buf[i + 3].toLong() and 0xff) shl 24)
    }

    private fun parse() {
        var off = 0
        while (off + 4 <= size) {
            if (!isSyncAt(off)) {
                val s = findSync(off + 1)
                if (s < 0) { off = maxOf(size - 3, 0); break }
                off = s
                continue
            }
            if (off + 44 > size) break
            val len = leU32(off + 16)
            if (len <= 0 || len > 4_000_000) {
                val s = findSync(off + 3)
                if (s < 0) { off = maxOf(size - 3, 0); break }
                off = s
                continue
            }
            val end = off + 44 + len.toInt()
            if (end > size) break
            onFrame(buf.copyOfRange(off + 44, end), buf[off + 3].toInt() and 0xff)
            off = end
        }
        if (off > 0) {
            if (off > size) off = size
            System.arraycopy(buf, off, buf, 0, size - off)
            size -= off
        }
    }
}
