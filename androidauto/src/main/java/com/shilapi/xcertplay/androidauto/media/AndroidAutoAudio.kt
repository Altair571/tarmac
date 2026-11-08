// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.shilapi.xcertplay.androidauto.AndroidAutoLog
import com.shilapi.xcertplay.androidauto.aap.AapChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays Android Auto's three PCM sinks: media (48 kHz stereo), guidance and assistant speech
 * (16 kHz mono) and system sounds (16 kHz mono). Each sink writes on its own thread, so a full
 * AudioTrack buffer waits there and not in the session's reader.
 */
internal class AndroidAutoAudio(
    context: Context,
    /** False puts guidance and system sounds on the media stream too; see AndroidAutoSettings.separateStreams. */
    private val separateStreams: Boolean = true,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val sinks = HashMap<Int, Sink>()
    private var focus: Any? = null

    @Synchronized
    fun start(channel: Int) {
        if (sinks[channel] == null) sinks[channel] = Sink(channel, separateStreams)
        if (channel == AapChannel.AUDIO_MEDIA) requestFocus()
    }

    @Synchronized
    fun write(channel: Int, pcm: ByteArray, offset: Int, length: Int) {
        (sinks[channel] ?: Sink(channel, separateStreams).also { sinks[channel] = it }).write(pcm.copyOfRange(offset, offset + length))
    }

    @Synchronized
    fun stop(channel: Int) {
        sinks.remove(channel)?.release()
        if (channel == AapChannel.AUDIO_MEDIA) abandonFocus()
    }

    @Synchronized
    fun release() {
        sinks.values.forEach(Sink::release)
        sinks.clear()
        abandonFocus()
    }

    private fun requestFocus() {
        if (focus != null || audioManager == null) return
        focus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes(AapChannel.AUDIO_MEDIA, separateStreams)).build()
                .also(audioManager::requestAudioFocus)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            Unit
        }
    }

    private fun abandonFocus() {
        val held = focus ?: return
        focus = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && held is AudioFocusRequest) {
            audioManager?.abandonAudioFocusRequest(held)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    private class Sink(private val channel: Int, separateStreams: Boolean) {
        private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
        @Volatile private var running = true
        private val sampleRate = if (channel == AapChannel.AUDIO_MEDIA) 48_000 else 16_000
        private val channelMask = if (channel == AapChannel.AUDIO_MEDIA) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        private val track: AudioTrack? = try {
            val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack(
                attributes(channel, separateStreams),
                AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                maxOf(minimum, MIN_TRACK_BYTES) * 2,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            ).apply { play() }
        } catch (error: Exception) {
            AndroidAutoLog.w("no audio track for channel $channel", error)
            null
        }
        private val thread = Thread(::playLoop, "AndroidAuto-audio-$channel").apply { start() }

        fun write(pcm: ByteArray) {
            if (!queue.offer(pcm)) {
                queue.poll()
                queue.offer(pcm)
            }
        }

        private fun playLoop() {
            try {
                while (running) {
                    val pcm = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                    track?.write(pcm, 0, pcm.size)
                }
            } catch (_: InterruptedException) {
            }
        }

        fun release() {
            running = false
            thread.interrupt()
            runCatching { thread.join(500) }
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    private companion object {
        const val QUEUE_CAPACITY = 64
        const val MIN_TRACK_BYTES = 8 * 1024
        const val POLL_MILLIS = 50L

        fun attributes(channel: Int, separateStreams: Boolean): AudioAttributes = AudioAttributes.Builder().apply {
            when (if (separateStreams) channel else AapChannel.AUDIO_MEDIA) {
                AapChannel.AUDIO_SPEECH -> setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                AapChannel.AUDIO_SYSTEM -> setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                else -> setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            }
        }.build()
    }
}
