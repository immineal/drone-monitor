package com.dronemonitor

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.util.ArrayDeque

/**
 * Low-latency hardware H.264 decode to a Surface, MediaCodec in async mode.
 *
 * Async mode keeps decoding off the network-read thread: feed() only enqueues,
 * so a burst of packets never blocks the socket. To stay live rather than fall
 * behind, the pending queue is capped and the oldest access units are dropped
 * when it overflows (the picture resyncs on the next in-band I-frame).
 *
 * The drone puts SPS/PPS in-band on every I-frame, so no separate codec-config
 * step is needed. The codec is never torn down on a transient glitch — that was
 * the cause of multi-second freezes — it just rides through until the next I-frame.
 */
class VideoDecoder(
    private val surface: Surface,
    private val mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    private val onFormat: (width: Int, height: Int) -> Unit = { _, _ -> }
) {
    private val lock = Object()
    private val pending = ArrayDeque<ByteArray>()
    private val freeInputs = ArrayDeque<Int>()
    private var codec: MediaCodec? = null
    private var pts = 0L
    @Volatile private var started = false
    @Volatile private var rendered = 0L

    private val maxPending = 6

    fun start() {
        if (started) return
        val c = MediaCodec.createDecoderByType(mime)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                synchronized(lock) {
                    val au = pending.pollFirst()
                    if (au != null) submit(mc, index, au) else freeInputs.addLast(index)
                }
            }

            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    mc.releaseOutputBuffer(index, true)
                    rendered++
                } catch (_: IllegalStateException) {
                }
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                try {
                    val w = format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                    onFormat(w, h)
                } catch (_: Exception) {
                }
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                android.util.Log.d("DroneMon", "codec onError transient=${e.isTransient} recoverable=${e.isRecoverable} msg=${e.message}")
            }
        })
        val format = MediaFormat.createVideoFormat(mime, 1280, 720)
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, 4096)
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, 2160)
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        started = true
    }

    private fun submit(mc: MediaCodec, index: Int, au: ByteArray) {
        try {
            val ib = mc.getInputBuffer(index) ?: return
            ib.clear()
            ib.put(au)
            mc.queueInputBuffer(index, 0, au.size, pts, 0)
            pts += 40000
        } catch (_: IllegalStateException) {
        }
    }

    /** Enqueue one complete Annex-B access unit. Never blocks. */
    fun feed(accessUnit: ByteArray) {
        val c = codec ?: return
        synchronized(lock) {
            val idx = freeInputs.pollFirst()
            if (idx != null) {
                submit(c, idx, accessUnit)
            } else {
                pending.addLast(accessUnit)
                while (pending.size > maxPending) pending.pollFirst()
            }
        }
    }

    fun framesRendered(): Long = rendered

    fun release() {
        started = false
        synchronized(lock) {
            pending.clear()
            freeInputs.clear()
        }
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }
}
