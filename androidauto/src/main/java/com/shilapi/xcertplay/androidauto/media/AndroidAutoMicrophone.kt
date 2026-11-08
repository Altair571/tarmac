// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.shilapi.xcertplay.androidauto.AndroidAutoLog

/**
 * The head unit's microphone for the Assistant and voice replies: 16 kHz mono 16-bit PCM in
 * 20 ms blocks, only while the phone has asked for it.
 */
internal class AndroidAutoMicrophone(
    private val context: Context,
    private val send: (pcm: ByteArray, length: Int, timestampMicros: Long) -> Unit,
) {
    @Volatile private var thread: Thread? = null

    fun available(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start() {
        if (thread != null || !available()) return
        thread = Thread(::recordLoop, "AndroidAuto-microphone").apply { start() }
    }

    @Synchronized
    fun stop() {
        val running = thread ?: return
        thread = null
        running.interrupt()
        runCatching { running.join(500) }
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, BLOCK_BYTES * 4))
        } catch (error: Exception) {
            AndroidAutoLog.w("microphone unavailable", error)
            return
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return
            recorder.startRecording()
            val block = ByteArray(BLOCK_BYTES)
            while (!Thread.currentThread().isInterrupted) {
                val read = recorder.read(block, 0, block.size)
                if (read < 0) break
                if (read > 0) send(block, read, SystemClock.elapsedRealtimeNanos() / 1_000)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        /** 20 ms of 16 kHz mono 16-bit audio. */
        const val BLOCK_BYTES = 640
    }
}
