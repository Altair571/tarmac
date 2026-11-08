// SPDX-License-Identifier: AGPL-3.0-or-later
// Channel numbers, message types and field numbers from open-headunit's proto files
// (control, media, input, sensors, playback), cross-checked against aasdk_proto.
package com.shilapi.xcertplay.androidauto.aap

import com.shilapi.xcertplay.androidauto.proto.ProtoMessage
import com.shilapi.xcertplay.androidauto.proto.ProtoWriter
import com.shilapi.xcertplay.androidauto.proto.proto

/** The channel the phone opens for each service this head unit announces. */
internal object AapChannel {
    const val CONTROL = 0
    const val SENSOR = 1
    const val VIDEO = 2
    const val INPUT = 3
    const val AUDIO_SPEECH = 4
    const val AUDIO_SYSTEM = 5
    const val AUDIO_MEDIA = 6
    const val MICROPHONE = 7
    const val MEDIA_STATUS = 9

    val AUDIO_SINKS = setOf(AUDIO_SPEECH, AUDIO_SYSTEM, AUDIO_MEDIA)
}

internal object ControlType {
    const val VERSION_REQUEST = 1
    const val VERSION_RESPONSE = 2
    const val ENCAPSULATED_SSL = 3
    const val AUTH_COMPLETE = 4
    const val SERVICE_DISCOVERY_REQUEST = 5
    const val SERVICE_DISCOVERY_RESPONSE = 6
    const val CHANNEL_OPEN_REQUEST = 7
    const val CHANNEL_OPEN_RESPONSE = 8
    const val CHANNEL_CLOSE_NOTIFICATION = 9
    const val PING_REQUEST = 11
    const val PING_RESPONSE = 12
    const val NAV_FOCUS_REQUEST = 13
    const val NAV_FOCUS_NOTIFICATION = 14
    const val BYEBYE_REQUEST = 15
    const val BYEBYE_RESPONSE = 16
    const val VOICE_SESSION_NOTIFICATION = 17
    const val AUDIO_FOCUS_REQUEST = 18
    const val AUDIO_FOCUS_NOTIFICATION = 19

    /** Control types travel on service channels too, flagged CONTROL; media types start at 0x8000. */
    fun isControl(type: Int): Boolean = type in 1..26
}

internal object MediaType {
    const val DATA = 0
    const val CODEC_CONFIG = 1
    const val SETUP = 0x8000
    const val START = 0x8001
    const val STOP = 0x8002
    const val CONFIG = 0x8003
    const val ACK = 0x8004
    const val MICROPHONE_REQUEST = 0x8005
    const val MICROPHONE_RESPONSE = 0x8006
    const val VIDEO_FOCUS_REQUEST = 0x8007
    const val VIDEO_FOCUS_NOTIFICATION = 0x8008
}

internal object SensorType {
    const val START_REQUEST = 0x8001
    const val START_RESPONSE = 0x8002
    const val EVENT = 0x8003

    const val LOCATION = 1
    const val NIGHT = 10
    const val DRIVING_STATUS = 13
}

internal object InputType {
    const val EVENT = 0x8001
    const val BINDING_REQUEST = 0x8002
    const val BINDING_RESPONSE = 0x8003
}

internal object MediaStatusType {
    const val STATUS = 0x8001
    const val METADATA = 0x8003
}

/** The video size Android Auto renders: one of the fixed sizes it offers, with its enum value. */
enum class AndroidAutoResolution(internal val code: Int, val width: Int, val height: Int) {
    P480(1, 800, 480),
    P720(2, 1280, 720),
    P1080(3, 1920, 1080),
}

/**
 * What the head unit announces in its service-discovery response.
 * [marginWidth] and [marginHeight] are the video pixels outside the screen's shape: the phone
 * keeps its interface inside `width - marginWidth` by `height - marginHeight`.
 */
internal class HeadUnitDescription(
    val resolution: AndroidAutoResolution,
    val sixtyFps: Boolean,
    val marginWidth: Int,
    val marginHeight: Int,
    val density: Int,
    val microphone: Boolean,
    /** Announce the location sensor: the driver chose the head unit's GPS and Android allows it. */
    val location: Boolean,
    val rightHandDrive: Boolean,
    val make: String,
    val model: String,
    val year: String,
    val vehicleId: String,
    val headUnitMake: String,
    val headUnitModel: String,
    val softwareVersion: String,
)

internal object AapMessages {
    const val VERSION_MAJOR = 1
    const val VERSION_MINOR = 1
    const val STATUS_OK = 0
    const val STATUS_NO_COMPATIBLE_VERSION = -1

    private const val CODEC_AUDIO_PCM = 1
    private const val CODEC_VIDEO_H264_BP = 3
    private const val STREAM_SPEECH = 1
    private const val STREAM_SYSTEM = 2
    private const val STREAM_MEDIA = 3
    private const val FRAME_RATE_60 = 1
    private const val FRAME_RATE_30 = 2
    private const val DRIVER_LEFT = 0
    private const val DRIVER_RIGHT = 1
    private const val NAV_FOCUS_PROJECTED = 2
    private const val CONFIG_READY = 2

    /** Android key codes Android Auto acts on: back, D-pad, media, search (voice) and calls. */
    val KEYCODES = listOf(3, 4, 5, 6, 19, 20, 21, 22, 23, 84, 85, 87, 88, 126, 127)

    fun versionRequest(): ByteArray = byteArrayOf(0, VERSION_MAJOR.toByte(), 0, VERSION_MINOR.toByte())

    /** Major, minor and the signed 16-bit status of a version response. */
    fun versionResponse(payload: ByteArray): Triple<Int, Int, Int> {
        require(payload.size >= 6) { "version response of ${payload.size} bytes" }
        fun u16(index: Int) = ((payload[index].toInt() and 0xFF) shl 8) or (payload[index + 1].toInt() and 0xFF)
        return Triple(u16(0), u16(2), u16(4).toShort().toInt())
    }

    fun authComplete(): ByteArray = proto { int(1, STATUS_OK) }

    fun serviceDiscoveryResponse(head: HeadUnitDescription): ByteArray = proto {
        message(1) {
            int(1, AapChannel.SENSOR)
            message(2) {
                message(1) { int(1, SensorType.DRIVING_STATUS) }
                // Without it the phone keeps its own GPS, which is what "GPS source · Phone" means.
                if (head.location) message(1) { int(1, SensorType.LOCATION) }
                message(1) { int(1, SensorType.NIGHT) }
            }
        }
        message(1) {
            int(1, AapChannel.VIDEO)
            message(3) {
                int(1, CODEC_VIDEO_H264_BP)
                bool(5, true)
                message(4) {
                    int(1, head.resolution.code)
                    int(2, if (head.sixtyFps) FRAME_RATE_60 else FRAME_RATE_30)
                    int(3, head.marginWidth)
                    int(4, head.marginHeight)
                    int(5, head.density)
                }
            }
        }
        message(1) {
            int(1, AapChannel.INPUT)
            message(4) {
                KEYCODES.forEach { int(1, it) }
                message(2) {
                    int(1, head.resolution.width)
                    int(2, head.resolution.height)
                }
            }
        }
        audioSink(AapChannel.AUDIO_SPEECH, STREAM_SPEECH, 16_000, 1)
        audioSink(AapChannel.AUDIO_SYSTEM, STREAM_SYSTEM, 16_000, 1)
        audioSink(AapChannel.AUDIO_MEDIA, STREAM_MEDIA, 48_000, 2)
        if (head.microphone) message(1) {
            int(1, AapChannel.MICROPHONE)
            message(5) {
                int(1, CODEC_AUDIO_PCM)
                message(2) { audioConfig(16_000, 1) }
            }
        }
        message(1) {
            int(1, AapChannel.MEDIA_STATUS)
            message(9) {}
        }
        string(2, head.make)
        string(3, head.model)
        string(4, head.year)
        string(5, head.vehicleId)
        int(6, if (head.rightHandDrive) DRIVER_RIGHT else DRIVER_LEFT)
        string(7, head.headUnitMake)
        string(8, head.headUnitModel)
        string(9, "1")
        string(10, head.softwareVersion)
        bool(11, false)
        message(17) {
            string(1, head.make)
            string(2, head.model)
            string(3, head.year)
            string(4, head.vehicleId)
            string(5, head.headUnitMake)
            string(6, head.headUnitModel)
            string(7, "1")
            string(8, head.softwareVersion)
        }
    }

    private fun ProtoWriter.audioSink(channel: Int, stream: Int, sampleRate: Int, channels: Int) = message(1) {
        int(1, channel)
        message(3) {
            int(1, CODEC_AUDIO_PCM)
            int(2, stream)
            message(3) { audioConfig(sampleRate, channels) }
        }
    }

    private fun ProtoWriter.audioConfig(sampleRate: Int, channels: Int) {
        int(1, sampleRate)
        int(2, 16)
        int(3, channels)
    }

    fun channelOpenResponse(): ByteArray = proto { int(1, STATUS_OK) }

    fun pingRequest(timestampNanos: Long): ByteArray = proto { int(1, timestampNanos) }

    /** Echoes the phone's timestamp: it correlates the reply, the two clocks share no origin. */
    fun pingResponse(request: ProtoMessage): ByteArray = proto { int(1, request.long(1)) }

    fun navFocusNotification(): ByteArray = proto { int(1, NAV_FOCUS_PROJECTED) }

    fun byeByeRequest(): ByteArray = proto { int(1, 1) }

    fun byeByeResponse(): ByteArray = ByteArray(0)

    /**
     * Always grants what the phone asks for, otherwise it keeps playing on its own speaker.
     * GAIN → GAIN, GAIN_TRANSIENT → GAIN_TRANSIENT, MAY_DUCK → GUIDANCE_ONLY, RELEASE → LOSS.
     */
    fun audioFocusNotification(request: ProtoMessage): ByteArray {
        val state = when (request.int(1)) {
            1 -> 1
            2 -> 2
            3 -> 7
            4 -> 3
            else -> 3
        }
        return proto {
            int(1, state)
            bool(2, false)
        }
    }

    fun mediaConfig(maxUnacked: Int): ByteArray = proto {
        int(1, CONFIG_READY)
        int(2, maxUnacked)
        int(3, 0)
    }

    fun mediaAck(sessionId: Int): ByteArray = proto {
        int(1, sessionId)
        int(2, 1)
    }

    fun videoFocusNotification(projected: Boolean, unsolicited: Boolean): ByteArray = proto {
        int(1, if (projected) 1 else 2)
        bool(2, unsolicited)
    }

    fun sensorStartResponse(): ByteArray = proto { int(1, STATUS_OK) }

    fun drivingStatusUnrestricted(): ByteArray = proto { message(SensorType.DRIVING_STATUS) { int(1, 0) } }

    fun nightMode(night: Boolean): ByteArray = proto { message(SensorType.NIGHT) { bool(1, night) } }

    /**
     * One location sensor event, in Android Auto's fixed-point units: degrees × 10⁷, accuracy in
     * millimetres, altitude in centimetres, speed in mm/s and bearing in degrees × 10⁶. Accuracy is
     * required, so a fix without one is sent as 100 m, as open-headunit does.
     */
    fun location(
        timestampMillis: Long,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        altitudeMeters: Double?,
        speedMetersPerSecond: Float?,
        bearingDegrees: Float?,
    ): ByteArray = proto {
        message(SensorType.LOCATION) {
            int(1, timestampMillis)
            int(2, Math.round(latitude * 1e7).toInt())
            int(3, Math.round(longitude * 1e7).toInt())
            int(4, accuracyMeters?.let { Math.round(it * 1e3).toInt().coerceAtLeast(0) } ?: UNKNOWN_ACCURACY_MILLIMETRES)
            altitudeMeters?.let { int(5, Math.round(it * 1e2).toInt()) }
            speedMetersPerSecond?.let { int(6, Math.round(it * 1e3).toInt()) }
            bearingDegrees?.let { int(7, Math.round(it * 1e6).toInt()) }
        }
    }

    private const val UNKNOWN_ACCURACY_MILLIMETRES = 100_000

    fun bindingResponse(): ByteArray = proto { int(1, STATUS_OK) }

    fun microphoneResponse(sessionId: Int): ByteArray = proto {
        int(1, STATUS_OK)
        int(2, sessionId)
    }

    /** One touch report. [pointers] are (id, x, y) in video pixels. */
    fun touch(timestampNanos: Long, action: Int, actionIndex: Int, pointers: List<TouchPointer>): ByteArray = proto {
        int(1, timestampNanos)
        message(3) {
            pointers.forEach { pointer ->
                message(1) {
                    int(1, pointer.x)
                    int(2, pointer.y)
                    int(3, pointer.id)
                }
            }
            int(2, actionIndex)
            int(3, action)
        }
    }

    fun key(timestampNanos: Long, keycode: Int, down: Boolean): ByteArray = proto {
        int(1, timestampNanos)
        message(4) {
            message(1) {
                int(1, keycode)
                bool(2, down)
                int(3, 0)
                bool(4, false)
            }
        }
    }

    /** `[timestamp u64 BE microseconds][media]`, the body of a media DATA message. */
    fun timestamped(timestampMicros: Long, media: ByteArray, offset: Int = 0, length: Int = media.size): ByteArray {
        val body = ByteArray(8 + length)
        for (i in 0 until 8) body[i] = (timestampMicros ushr (56 - 8 * i)).toByte()
        System.arraycopy(media, offset, body, 8, length)
        return body
    }
}

/** A touch point in video pixels. */
internal class TouchPointer(val id: Int, val x: Int, val y: Int)

/** Android Auto's touch actions; the numbers match Android's MotionEvent actions. */
internal object TouchAction {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    const val POINTER_DOWN = 5
    const val POINTER_UP = 6
}

/** What the phone says it is playing. */
class AndroidAutoNowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val artwork: ByteArray?,
    val durationSeconds: Int,
) {
    internal companion object {
        fun parse(message: ProtoMessage) = AndroidAutoNowPlaying(
            title = message.string(1),
            artist = message.string(2),
            album = message.string(3),
            artwork = message.bytes(4),
            durationSeconds = message.int(6),
        )
    }
}

/** Playing, paused or stopped, from the phone's media playback status. */
enum class AndroidAutoPlayState { STOPPED, PLAYING, PAUSED;
    internal companion object {
        fun parse(message: ProtoMessage): AndroidAutoPlayState = when (message.int(1)) {
            2 -> PLAYING
            3 -> PAUSED
            else -> STOPPED
        }
    }
}
