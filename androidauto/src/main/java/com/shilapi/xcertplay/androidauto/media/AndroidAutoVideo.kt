// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto.media

import android.annotation.TargetApi
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import com.shilapi.xcertplay.androidauto.AndroidAutoLog
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Decodes Android Auto's H.264 stream into the projection surface with `MediaCodec` in
 * asynchronous mode, on its own display-priority thread: a frame goes into the decoder the moment
 * both it and an input buffer exist, and every decoded frame is shown at once. Nothing waits on a
 * poll interval.
 *
 * The decoder is created when the session starts, before the phone has switched to accessory mode,
 * so it is ready when the first frame arrives. The last codec config (SPS/PPS) is kept: a decoder
 * started for a new surface begins with it, then the phone sends a key frame for the regained focus.
 */
internal class AndroidAutoVideo(
    private val width: Int,
    private val height: Int,
    private val onFirstFrame: () -> Unit,
) {
    private class AccessUnit(val data: ByteArray, val timestampMicros: Long, val config: Boolean)

    private val thread = HandlerThread("AndroidAuto-video", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    // Everything below belongs to [thread].
    private var codec: MediaCodec? = null
    private var started = false
    private var surface: Surface? = null
    private var codecConfig: ByteArray? = null
    private val freeInputs = ArrayDeque<Int>()
    private val units = ArrayDeque<AccessUnit>()
    private var shownFirstFrame = false

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            freeInputs.addLast(index)
            pump()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            runCatching { codec.releaseOutputBuffer(index, true) }
            if (!shownFirstFrame) {
                shownFirstFrame = true
                onFirstFrame()
            }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            AndroidAutoLog.w("video decoder failed; starting it again", error)
            restart()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    init {
        handler.post { codec = createCodec() }
    }

    /**
     * Moves decoding to [target]. With null it returns once the decoder has let go of the old
     * surface, so the screen can release it without the decoder still drawing into it.
     */
    fun setSurface(target: Surface?) {
        val applied = CountDownLatch(1)
        handler.post {
            stopCodec()
            surface = target
            if (target != null) startCodec()
            applied.countDown()
        }
        if (target == null) applied.await(SURFACE_RELEASE_MILLIS, TimeUnit.MILLISECONDS)
    }

    fun onCodecConfig(config: ByteArray) {
        handler.post {
            codecConfig = config
            enqueue(AccessUnit(config, 0, config = true))
        }
    }

    fun onFrame(frame: ByteArray, timestampMicros: Long) {
        handler.post { enqueue(AccessUnit(frame, timestampMicros, config = false)) }
    }

    fun release() {
        handler.post {
            stopCodec()
            codec?.release()
            codec = null
        }
        thread.quitSafely()
        thread.join(RELEASE_MILLIS)
    }

    private fun enqueue(unit: AccessUnit) {
        // Without a surface the frames could not be shown; the phone sends a key frame on return.
        if (!started) return
        if (units.size >= MAX_QUEUED) {
            // The decoder fell behind; old frames are worth less than keeping up with the phone.
            AndroidAutoLog.w("video decoder behind by ${units.size} frames; dropping them")
            units.clear()
        }
        units.addLast(unit)
        pump()
    }

    private fun pump() {
        val active = codec ?: return
        while (started && freeInputs.isNotEmpty() && units.isNotEmpty()) {
            val index = freeInputs.removeFirst()
            val unit = units.removeFirst()
            try {
                val buffer = active.getInputBuffer(index) ?: continue
                buffer.clear()
                val size = minOf(unit.data.size, buffer.capacity())
                buffer.put(unit.data, 0, size)
                active.queueInputBuffer(index, 0, size, unit.timestampMicros,
                    if (unit.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
            } catch (error: RuntimeException) {
                // A buffer announced before the last stop; a dead decoder reports itself in onError.
                AndroidAutoLog.w("video decoder refused a frame", error)
            }
        }
    }

    private fun createCodec(): MediaCodec? = try {
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    } catch (error: Exception) {
        AndroidAutoLog.w("no H.264 decoder", error)
        null
    }

    private fun startCodec() {
        val target = surface ?: return
        val active = codec ?: createCodec()?.also { codec = it } ?: return
        try {
            active.setCallback(callback, handler)
            active.configure(format(active), target, null, 0)
            active.start()
            started = true
            freeInputs.clear()
            units.clear()
            codecConfig?.let { units.addLast(AccessUnit(it, 0, config = true)) }
        } catch (error: Exception) {
            AndroidAutoLog.w("video decoder could not start for ${width}x$height", error)
            runCatching { active.reset() }
            started = false
        }
    }

    private fun stopCodec() {
        if (!started) return
        started = false
        freeInputs.clear()
        units.clear()
        runCatching { codec?.stop() }
    }

    private fun restart() {
        started = false
        freeInputs.clear()
        units.clear()
        runCatching { codec?.reset() }
        startCodec()
    }

    private fun format(codec: MediaCodec): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            // Real-time priority: decode each frame as it comes rather than for throughput.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lowLatencySupported(codec)) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

    @TargetApi(Build.VERSION_CODES.R)
    private fun lowLatencySupported(codec: MediaCodec): Boolean = runCatching {
        codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
    }.getOrDefault(false)

    private companion object {
        const val MAX_QUEUED = 60
        const val MAX_INPUT_BYTES = 2 * 1024 * 1024
        const val SURFACE_RELEASE_MILLIS = 500L
        const val RELEASE_MILLIS = 1_000L
    }
}
