// SPDX-License-Identifier: AGPL-3.0-or-later
// Session flow ported from Headunit (Michael Reid), anod/headunit and open-headunit
// (AapTransport, AapControl, AapMessageHandlerType): version, TLS, auth complete, then
// service discovery, channel opens and the media, input and sensor services.
package com.shilapi.xcertplay.androidauto.aap

import com.shilapi.xcertplay.androidauto.AndroidAutoException
import com.shilapi.xcertplay.androidauto.AndroidAutoFailure
import com.shilapi.xcertplay.androidauto.AndroidAutoLog
import com.shilapi.xcertplay.androidauto.proto.ProtoMessage
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/**
 * One Android Auto session over [stream]. [start] runs it on its own reading thread; every
 * [Listener] call comes from that thread, so a listener hands slow work (decoding, playback) to
 * its own worker. The send methods are safe from any thread.
 */
internal class AapSession(
    private val stream: BlockingDuplexByteStream,
    private val tls: () -> AapTls,
    private val head: HeadUnitDescription,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        /** The phone answered the version request: the link works, TLS comes next. */
        fun onVersion() {}
        fun onConnected() {}
        fun onVideoConfig(codecConfig: ByteArray) {}
        fun onVideoFrame(frame: ByteArray, timestampMicros: Long) {}
        fun onVideoFocusRequest(projected: Boolean) {}
        fun onAudioStart(channel: Int) {}
        fun onAudio(channel: Int, pcm: ByteArray, offset: Int, length: Int) {}
        fun onAudioStop(channel: Int) {}
        fun onMicrophone(open: Boolean) {}
        /** The phone asked for the location sensor; send [sendLocation] events from now on. */
        fun onLocationRequested() {}
        fun onNowPlaying(nowPlaying: AndroidAutoNowPlaying) {}
        fun onPlayState(state: AndroidAutoPlayState) {}
        fun onVoiceSession(active: Boolean) {}
        /** Always called once, last. [error] is null when the phone or the driver ended it. */
        fun onEnded(reason: String, error: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private val writeLock = Any()
    private val reassembler = AapReassembler()
    private val reader = AapStreamReader(stream) { closed.get() }
    private val sessionIds = HashMap<Int, Int>()
    private val microphoneSession = AtomicInteger(0)
    private val nextMicrophoneSession = AtomicInteger(1)
    @Volatile private var cipher: AapCipher? = null
    @Volatile private var videoConfigured = false
    @Volatile private var surfaceReady = false
    @Volatile private var night = false
    private var pinger: ScheduledExecutorService? = null
    private var thread: Thread? = null

    fun start() {
        thread = Thread(::run, "AndroidAuto-AAP").apply { start() }
    }

    private fun run() {
        var reason = "phone disconnected"
        var failure: Throwable? = null
        try {
            handshake()
            listener.onConnected()
            startPings()
            while (!closed.get()) {
                val frame = AapFrameCodec.read(reader::readFully)
                val plain = if (frame.encrypted) {
                    (cipher ?: throw IOException("encrypted frame before TLS")).decrypt(frame.payload)
                } else frame.payload
                val message = reassembler.accept(frame, plain) ?: continue
                if (!dispatch(message)) {
                    reason = "phone ended Android Auto"
                    break
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) {
                failure = error
                reason = when (error) {
                    is AapIncompatibleVersion -> "phone has no compatible Android Auto version"
                    else -> error.message ?: error.javaClass.simpleName
                }
                AndroidAutoLog.w("session ended: $reason", error)
            } else reason = "closed"
        } finally {
            shutdown()
            if (ended.compareAndSet(false, true)) listener.onEnded(reason, failure)
        }
    }

    private fun handshake() {
        writePlain(AapChannel.CONTROL, ControlType.VERSION_REQUEST, AapMessages.versionRequest())
        val version = readPlain(ControlType.VERSION_RESPONSE)
        val (major, minor, status) = AapMessages.versionResponse(version)
        AndroidAutoLog.i("version $major.$minor status $status")
        if (status == AapMessages.STATUS_NO_COMPATIBLE_VERSION) throw AapIncompatibleVersion()

        listener.onVersion()
        val engine = tls()
        try {
            engine.handshake(
                send = { writePlain(AapChannel.CONTROL, ControlType.ENCAPSULATED_SSL, it) },
                receive = { readPlain(ControlType.ENCAPSULATED_SSL) },
            )
        } catch (error: SSLException) {
            throw AndroidAutoException(AndroidAutoFailure.IDENTITY_REJECTED, "TLS handshake failed: ${error.message}", error)
        } catch (error: AndroidAutoException) {
            // A phone that dislikes the certificate often just hangs up instead of sending an alert.
            if (error.failure != AndroidAutoFailure.DISCONNECTED && error.failure != AndroidAutoFailure.SILENT) throw error
            throw AndroidAutoException(AndroidAutoFailure.IDENTITY_REJECTED, "phone left during the TLS handshake", error)
        }
        writePlain(AapChannel.CONTROL, ControlType.AUTH_COMPLETE, AapMessages.authComplete())
        cipher = engine
        AndroidAutoLog.i("TLS established")
    }

    /** Reads plaintext frames until one of [type] arrives on the control channel. */
    private fun readPlain(type: Int): ByteArray {
        while (true) {
            val frame = AapFrameCodec.read(reader::readFully)
            if (frame.encrypted) throw IOException("encrypted frame during the handshake")
            val message = reassembler.accept(frame, frame.payload) ?: continue
            if (message.channel == AapChannel.CONTROL && message.type == type) return message.payload
            AndroidAutoLog.w("ignored type ${message.type} on channel ${message.channel} during the handshake")
        }
    }

    /** False when the session should end. */
    private fun dispatch(message: AapMessage): Boolean {
        if (message.type == ControlType.CHANNEL_OPEN_REQUEST) {
            channelOpened(message.channel)
            return true
        }
        if (message.channel == AapChannel.CONTROL || (message.control && ControlType.isControl(message.type))) {
            return control(message)
        }
        when (message.channel) {
            AapChannel.VIDEO -> video(message)
            in AapChannel.AUDIO_SINKS -> audio(message)
            AapChannel.MICROPHONE -> microphone(message)
            AapChannel.INPUT -> if (message.type == InputType.BINDING_REQUEST) {
                send(AapChannel.INPUT, InputType.BINDING_RESPONSE, AapMessages.bindingResponse())
            }
            AapChannel.SENSOR -> if (message.type == SensorType.START_REQUEST) sensorStarted(ProtoMessage.parse(message.payload))
            AapChannel.MEDIA_STATUS -> mediaStatus(message)
            else -> AndroidAutoLog.w("unhandled type ${message.type} on channel ${message.channel}")
        }
        return true
    }

    private fun control(message: AapMessage): Boolean {
        val body = { ProtoMessage.parse(message.payload) }
        when (message.type) {
            ControlType.SERVICE_DISCOVERY_REQUEST -> {
                val request = body()
                AndroidAutoLog.i("service discovery from ${request.string(5)} ${request.string(4)}")
                send(AapChannel.CONTROL, ControlType.SERVICE_DISCOVERY_RESPONSE, AapMessages.serviceDiscoveryResponse(head))
            }
            ControlType.PING_REQUEST -> send(message.channel, ControlType.PING_RESPONSE, AapMessages.pingResponse(body()))
            ControlType.PING_RESPONSE -> Unit
            ControlType.NAV_FOCUS_REQUEST ->
                send(message.channel, ControlType.NAV_FOCUS_NOTIFICATION, AapMessages.navFocusNotification())
            ControlType.AUDIO_FOCUS_REQUEST ->
                send(message.channel, ControlType.AUDIO_FOCUS_NOTIFICATION, AapMessages.audioFocusNotification(body()))
            ControlType.VOICE_SESSION_NOTIFICATION -> listener.onVoiceSession(body().int(1) == 1)
            ControlType.BYEBYE_REQUEST -> {
                AndroidAutoLog.i("phone said goodbye, reason ${body().int(1)}")
                send(message.channel, ControlType.BYEBYE_RESPONSE, AapMessages.byeByeResponse())
                return false
            }
            ControlType.BYEBYE_RESPONSE -> return false
            ControlType.CHANNEL_CLOSE_NOTIFICATION -> AndroidAutoLog.i("phone closed channel ${message.channel}")
            else -> AndroidAutoLog.w("unhandled control type ${message.type} on channel ${message.channel}")
        }
        return true
    }

    private fun channelOpened(channel: Int) {
        send(channel, ControlType.CHANNEL_OPEN_RESPONSE, AapMessages.channelOpenResponse())
        if (channel == AapChannel.SENSOR) {
            // Android Auto needs a driving status before it shows anything; there is no speed source yet.
            send(AapChannel.SENSOR, SensorType.EVENT, AapMessages.drivingStatusUnrestricted())
            send(AapChannel.SENSOR, SensorType.EVENT, AapMessages.nightMode(night))
        }
    }

    private fun sensorStarted(request: ProtoMessage) {
        send(AapChannel.SENSOR, SensorType.START_RESPONSE, AapMessages.sensorStartResponse())
        when (request.int(1)) {
            SensorType.DRIVING_STATUS -> send(AapChannel.SENSOR, SensorType.EVENT, AapMessages.drivingStatusUnrestricted())
            SensorType.NIGHT -> send(AapChannel.SENSOR, SensorType.EVENT, AapMessages.nightMode(night))
            SensorType.LOCATION -> listener.onLocationRequested()
        }
    }

    private fun video(message: AapMessage) {
        when (message.type) {
            MediaType.DATA -> {
                if (message.payload.size >= 8) {
                    listener.onVideoFrame(message.payload.copyOfRange(8, message.payload.size), timestamp(message.payload))
                }
                ack(message.channel)
            }
            MediaType.CODEC_CONFIG -> listener.onVideoConfig(message.payload)
            MediaType.SETUP -> {
                send(message.channel, MediaType.CONFIG, AapMessages.mediaConfig(VIDEO_MAX_UNACKED))
                videoConfigured = true
                if (surfaceReady) sendVideoFocus(projected = true, unsolicited = false)
            }
            MediaType.START -> sessionIds[message.channel] = ProtoMessage.parse(message.payload).int(1)
            MediaType.STOP -> Unit
            MediaType.VIDEO_FOCUS_REQUEST -> {
                val projected = ProtoMessage.parse(message.payload).int(2) == 1
                // The driver chose "exit" in Android Auto: answer, then let the screen go home.
                if (!projected) sendVideoFocus(projected = false, unsolicited = false)
                else if (surfaceReady) sendVideoFocus(projected = true, unsolicited = false)
                listener.onVideoFocusRequest(projected)
            }
            else -> AndroidAutoLog.w("unhandled video type ${message.type}")
        }
    }

    private fun audio(message: AapMessage) {
        when (message.type) {
            MediaType.DATA -> {
                if (message.payload.size > 8) listener.onAudio(message.channel, message.payload, 8, message.payload.size - 8)
                // One credit per DATA message; Android Auto counts no credit for codec config.
                ack(message.channel)
            }
            MediaType.CODEC_CONFIG -> Unit
            MediaType.SETUP -> send(message.channel, MediaType.CONFIG, AapMessages.mediaConfig(AUDIO_MAX_UNACKED))
            MediaType.START -> {
                sessionIds[message.channel] = ProtoMessage.parse(message.payload).int(1)
                listener.onAudioStart(message.channel)
            }
            MediaType.STOP -> listener.onAudioStop(message.channel)
            else -> AndroidAutoLog.w("unhandled audio type ${message.type} on channel ${message.channel}")
        }
    }

    private fun microphone(message: AapMessage) {
        when (message.type) {
            MediaType.MICROPHONE_REQUEST -> {
                val open = ProtoMessage.parse(message.payload).bool(1)
                if (open) {
                    val id = nextMicrophoneSession.getAndIncrement()
                    microphoneSession.set(id)
                    send(AapChannel.MICROPHONE, MediaType.MICROPHONE_RESPONSE, AapMessages.microphoneResponse(id))
                } else microphoneSession.set(0)
                listener.onMicrophone(open)
            }
            MediaType.ACK -> Unit
            MediaType.STOP -> {
                microphoneSession.set(0)
                listener.onMicrophone(false)
            }
            else -> AndroidAutoLog.w("unhandled microphone type ${message.type}")
        }
    }

    private fun mediaStatus(message: AapMessage) {
        val body = ProtoMessage.parse(message.payload)
        when (message.type) {
            MediaStatusType.STATUS -> listener.onPlayState(AndroidAutoPlayState.parse(body))
            MediaStatusType.METADATA -> listener.onNowPlaying(AndroidAutoNowPlaying.parse(body))
        }
    }

    private fun ack(channel: Int) = send(channel, MediaType.ACK, AapMessages.mediaAck(sessionIds[channel] ?: 0))

    private fun timestamp(payload: ByteArray): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (payload[i].toLong() and 0xFF)
        return value
    }

    private fun startPings() {
        pinger = Executors.newSingleThreadScheduledExecutor { Thread(it, "AndroidAuto-ping").apply { isDaemon = true } }.apply {
            scheduleWithFixedDelay({
                runCatching { send(AapChannel.CONTROL, ControlType.PING_REQUEST, AapMessages.pingRequest(System.nanoTime())) }
            }, PING_INTERVAL_MILLIS, PING_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    /** The projection screen gained (true) or lost (false) its surface. */
    fun setSurfaceReady(ready: Boolean) {
        if (surfaceReady == ready) return
        surfaceReady = ready
        if (videoConfigured && cipher != null) sendVideoFocus(projected = ready, unsolicited = true)
    }

    private fun sendVideoFocus(projected: Boolean, unsolicited: Boolean) =
        send(AapChannel.VIDEO, MediaType.VIDEO_FOCUS_NOTIFICATION, AapMessages.videoFocusNotification(projected, unsolicited))

    fun sendTouch(action: Int, actionIndex: Int, pointers: List<TouchPointer>) {
        if (cipher == null) return
        send(AapChannel.INPUT, InputType.EVENT, AapMessages.touch(System.nanoTime(), action, actionIndex, pointers))
    }

    fun sendKey(keycode: Int, down: Boolean) {
        if (cipher == null) return
        send(AapChannel.INPUT, InputType.EVENT, AapMessages.key(System.nanoTime(), keycode, down))
    }

    fun setNight(night: Boolean) {
        if (this.night == night) return
        this.night = night
        if (cipher != null) send(AapChannel.SENSOR, SensorType.EVENT, AapMessages.nightMode(night))
    }

    /** A location event from [AapMessages.location], once the phone has asked for the sensor. */
    fun sendLocation(event: ByteArray) {
        if (cipher == null) return
        send(AapChannel.SENSOR, SensorType.EVENT, event)
    }

    /** 16 kHz mono 16-bit PCM from the head unit's microphone while the phone listens. */
    fun sendMicrophone(pcm: ByteArray, length: Int, timestampMicros: Long) {
        if (cipher == null || microphoneSession.get() == 0) return
        send(AapChannel.MICROPHONE, MediaType.DATA, AapMessages.timestamped(timestampMicros, pcm, 0, length))
    }

    /** Encrypts and writes one message; control types on a service channel carry the CONTROL flag. */
    private fun send(channel: Int, type: Int, body: ByteArray) {
        if (closed.get()) return
        val active = cipher ?: throw IOException("send before TLS")
        val flags = AapFlags.ENCRYPTED or (if (channel != AapChannel.CONTROL && ControlType.isControl(type)) AapFlags.CONTROL else 0)
        val plain = AapFrameCodec.plaintext(type, body)
        synchronized(writeLock) {
            // Encrypting under the write lock keeps TLS record order and frame order the same.
            AapFrameCodec.frames(channel, flags, plain) { bytes, offset, length -> active.encrypt(bytes, offset, length) }
                .forEach(stream::send)
        }
    }

    private fun writePlain(channel: Int, type: Int, body: ByteArray) = synchronized(writeLock) {
        AapFrameCodec.frames(channel, 0, AapFrameCodec.plaintext(type, body)) { bytes, offset, length ->
            bytes.copyOfRange(offset, offset + length)
        }.forEach(stream::send)
    }

    /** Says goodbye to the phone, then ends the session. */
    override fun close() {
        if (cipher != null && !closed.get()) {
            runCatching { send(AapChannel.CONTROL, ControlType.BYEBYE_REQUEST, AapMessages.byeByeRequest()) }
        }
        if (!closed.compareAndSet(false, true)) return
        shutdown()
        thread?.takeIf { it !== Thread.currentThread() }?.join(JOIN_MILLIS)
        if (ended.compareAndSet(false, true)) listener.onEnded("closed", null)
    }

    private fun shutdown() {
        closed.set(true)
        pinger?.shutdownNow()
        (cipher as? AapTls)?.close()
        runCatching { stream.close() }
    }

    private companion object {
        const val VIDEO_MAX_UNACKED = 4
        const val AUDIO_MAX_UNACKED = 16
        const val PING_INTERVAL_MILLIS = 2_000L
        const val JOIN_MILLIS = 2_000L
    }
}

internal class AapIncompatibleVersion : IOException("no compatible Android Auto protocol version")

/**
 * Exact-size reads from a [BlockingDuplexByteStream], which may split or join writes. It waits
 * through quiet periods until [stopped], and fails after [silenceMillis] with no bytes at all: a
 * live phone answers the head unit's pings every two seconds.
 */
internal class AapStreamReader(
    private val stream: BlockingDuplexByteStream,
    private val silenceMillis: Long = 20_000,
    private val stopped: () -> Boolean,
) {
    private var buffer = ByteArray(0)
    private var position = 0

    fun readFully(size: Int): ByteArray {
        val out = ByteArray(size)
        var filled = 0
        var lastData = System.nanoTime()
        while (filled < size) {
            if (position < buffer.size) {
                val count = minOf(size - filled, buffer.size - position)
                System.arraycopy(buffer, position, out, filled, count)
                position += count
                filled += count
                continue
            }
            if (stopped()) throw IOException("session closed")
            val chunk = stream.recv(READ_CHUNK, POLL_MILLIS)
            when {
                chunk == null -> if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastData) > silenceMillis) {
                    throw AndroidAutoException(AndroidAutoFailure.SILENT, "phone silent for ${silenceMillis / 1000} s")
                }
                chunk.isEmpty() -> throw AndroidAutoException(AndroidAutoFailure.DISCONNECTED, "phone disconnected")
                else -> {
                    buffer = chunk
                    position = 0
                    lastData = System.nanoTime()
                }
            }
        }
        return out
    }

    private companion object {
        const val READ_CHUNK = 16 * 1024
        const val POLL_MILLIS = 500L
    }
}
