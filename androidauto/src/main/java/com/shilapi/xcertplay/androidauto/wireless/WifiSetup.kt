// SPDX-License-Identifier: AGPL-3.0-or-later
// Framing, message shapes and exchange order ported from open-headunit (wireless.proto, WppFraming,
// WppMessages, WppHandshakeSession), which cross-checks them against aa-proxy-rs and
// WirelessAndroidAutoDongle.
package com.shilapi.xcertplay.androidauto.wireless

import com.shilapi.xcertplay.androidauto.AndroidAutoException
import com.shilapi.xcertplay.androidauto.AndroidAutoFailure
import com.shilapi.xcertplay.androidauto.AndroidAutoLog
import com.shilapi.xcertplay.androidauto.proto.ProtoMessage
import com.shilapi.xcertplay.androidauto.proto.proto
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Message types of the Bluetooth setup exchange, from the `[type u16]` header field. */
internal object WifiSetupType {
    /** Head unit → phone: the address and port to open Android Auto on once it has joined. */
    const val START_REQUEST = 1
    /** Phone → head unit, empty: asks for the network credentials. */
    const val INFO_REQUEST = 2
    /** Head unit → phone: SSID, passphrase, BSSID, security and access-point type. */
    const val INFO_RESPONSE = 3
    /** Phone → head unit: whether it got onto the network; 0 is success. */
    const val CONNECT_STATUS = 6
    /** Phone → head unit: acknowledges [START_REQUEST]; a non-zero status refuses it. */
    const val START_RESPONSE = 7
    /** A keepalive either side may send at any time, answered by echoing its payload. */
    const val PING_REQUEST = 8
    const val PING_RESPONSE = 9
}

/** `WifiInfoResponse.security_mode`. */
internal object WifiSecurityMode {
    const val OPEN = 1
    const val WPA2_PERSONAL = 8
    const val WPA3_PERSONAL = 32
    const val WPA2_WPA3_PERSONAL = 40

    fun of(security: Iap2WirelessSecurity): Int = when (security) {
        Iap2WirelessSecurity.NONE -> OPEN
        Iap2WirelessSecurity.WPA3_TRANSITION -> WPA2_WPA3_PERSONAL
        Iap2WirelessSecurity.WPA3_ONLY -> WPA3_PERSONAL
        // WEP is never offered by the hotspot managers; WPA2 is what every phone joins.
        Iap2WirelessSecurity.WEP, Iap2WirelessSecurity.WPA_WPA2 -> WPA2_PERSONAL
    }
}

/** `WifiInfoResponse.access_point_type`. */
internal object WifiAccessPointType {
    /** The phone may keep the network. */
    const val STATIC = 0
    /**
     * The network is for this connection only. Used for every network here: a BSSID the phone keeps
     * and that later moves is one Android pins below Android Auto, and only the driver can clear it.
     */
    const val DYNAMIC = 1
}

internal class WifiSetupMessage(val type: Int, val payload: ByteArray)

/** The network the phone joins and where it then opens Android Auto. */
internal class WifiCredentials(
    val ssid: String,
    val key: String,
    /** `aa:bb:cc:dd:ee:ff`, or empty when the access point's address is not known. */
    val bssid: String,
    val securityMode: Int,
    val hostAddress: String,
    val port: Int,
    val accessPointType: Int = WifiAccessPointType.DYNAMIC,
) {
    override fun toString() = "WifiCredentials(ssid='$ssid', key=<redacted>, bssid='$bssid', " +
        "securityMode=$securityMode, host=$hostAddress:$port, accessPointType=$accessPointType)"
}

/** `[length u16 BE][type u16 BE][proto]`, where the length counts the proto payload only. */
internal object WifiSetupCodec {
    const val HEADER_SIZE = 4
    private const val MAX_PAYLOAD = 0xFFFF

    fun frame(type: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "setup payload of ${payload.size} bytes does not fit its length field" }
        require(type in 0..0xFFFF) { "setup type $type does not fit its type field" }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = (payload.size shr 8).toByte()
        out[1] = payload.size.toByte()
        out[2] = (type shr 8).toByte()
        out[3] = type.toByte()
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    /** One whole message from the front of [buffer] (its first [length] bytes), or null when it is not all there yet. */
    fun parse(buffer: ByteArray, length: Int): Pair<WifiSetupMessage, Int>? {
        if (length < HEADER_SIZE) return null
        val size = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        val type = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        if (length < HEADER_SIZE + size) return null
        return WifiSetupMessage(type, buffer.copyOfRange(HEADER_SIZE, HEADER_SIZE + size)) to HEADER_SIZE + size
    }

    /** `WifiStartRequest`: ip_address 1, port 2, start_request_reason 3 (0, a user request). */
    fun startRequest(hostAddress: String, port: Int): ByteArray = proto {
        string(1, hostAddress)
        int(2, port)
        int(3, 0)
    }

    /**
     * `WifiInfoResponse`. All five fields go out every time, an empty BSSID included: the schema the
     * other receivers use marks them required, and a strict parser on the phone drops the whole
     * message over a missing one, which looks like silence rather than a wrong password.
     */
    fun infoResponse(credentials: WifiCredentials): ByteArray = proto {
        string(1, credentials.ssid)
        string(2, credentials.key)
        string(3, credentials.bssid)
        int(4, credentials.securityMode)
        int(5, credentials.accessPointType)
    }

    /**
     * The status the phone reports on a [WifiSetupType.CONNECT_STATUS] (field 1) or
     * [WifiSetupType.START_RESPONSE] (field 3); null for other types, a missing field or a payload
     * that does not parse, which never counts as a refusal.
     */
    fun status(message: WifiSetupMessage): Int? {
        val field = when (message.type) {
            WifiSetupType.CONNECT_STATUS -> 1
            WifiSetupType.START_RESPONSE -> 3
            else -> return null
        }
        val body = runCatching { ProtoMessage.parse(message.payload) }.getOrNull() ?: return null
        return if (body.has(field)) body.int(field) else null
    }

    /** `WifiConnectStatus.error_message_hint`: the phone's own words, where it sends any. */
    fun hint(message: WifiSetupMessage): String? =
        runCatching { ProtoMessage.parse(message.payload).string(2) }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * The head unit's half of the Bluetooth setup exchange, on an RFCOMM [stream]:
 *
 * ```
 * HU → 1 WifiStartRequest     where to open Android Auto
 * HU ← 2 WifiInfoRequest
 * HU → 3 WifiInfoResponse     the credentials
 * HU ← 7 WifiStartResponse    acknowledgement
 * HU ← 6 WifiConnectStatus    did the phone get onto the network
 * ```
 *
 * A phone already on the network skips 2 and 3. [run] returns once the phone reports that it
 * joined or [tcpConnected] says it has already opened Android Auto; [hold] then keeps the channel
 * answered until the stream is closed, because Android Auto starts the exchange again when the
 * channel closes while the head unit stays connected over Bluetooth.
 */
internal class WifiSetupExchange(
    private val stream: BlockingDuplexByteStream,
    private val credentials: WifiCredentials,
    private val tcpConnected: () -> Boolean = { false },
    private val firstReplyMillis: Long = FIRST_REPLY_MILLIS,
    private val joinMillis: Long = JOIN_MILLIS,
) {
    private var buffer = ByteArray(1024)
    private var buffered = 0

    fun run() {
        send(WifiSetupType.START_REQUEST, WifiSetupCodec.startRequest(credentials.hostAddress, credentials.port))
        var heard = false
        var deadline = deadlineAfter(firstReplyMillis)
        while (true) {
            val message = try {
                read(deadline, tcpConnected)
            } catch (error: IOException) {
                // A phone that has already opened Android Auto over Wi-Fi may close this channel.
                if (tcpConnected()) return
                throw error
            } ?: run {
                if (tcpConnected()) return
                if (!heard) throw AndroidAutoException(AndroidAutoFailure.SILENT, "the phone did not answer the Bluetooth setup")
                throw AndroidAutoException(AndroidAutoFailure.PHONE_WIFI, "the phone did not join the Wi-Fi within ${joinMillis / 1000} s")
            }
            heard = true
            when (message.type) {
                WifiSetupType.INFO_REQUEST -> {
                    // Asked twice means it has not given up yet: send the same credentials again.
                    send(WifiSetupType.INFO_RESPONSE, WifiSetupCodec.infoResponse(credentials))
                    deadline = deadlineAfter(joinMillis)
                }
                WifiSetupType.START_RESPONSE -> WifiSetupCodec.status(message)?.takeIf { it != 0 }?.let {
                    throw AndroidAutoException(AndroidAutoFailure.PHONE_WIFI, "the phone refused the start request (status $it)")
                }
                WifiSetupType.CONNECT_STATUS -> {
                    val status = WifiSetupCodec.status(message)
                    if (status != null && status != 0) {
                        val hint = WifiSetupCodec.hint(message)?.let { ": $it" }.orEmpty()
                        throw AndroidAutoException(AndroidAutoFailure.PHONE_WIFI, "the phone could not join the Wi-Fi (status $status)$hint")
                    }
                    AndroidAutoLog.i("phone joined the Wi-Fi")
                    return
                }
                WifiSetupType.PING_REQUEST -> send(WifiSetupType.PING_RESPONSE, message.payload)
                else -> AndroidAutoLog.i("ignored setup message type ${message.type}")
            }
        }
    }

    /** Answers pings until the channel closes; credentials sent onto a live session would drop it. */
    fun hold() {
        try {
            while (true) {
                val message = read(deadlineAfter(HOLD_POLL_MILLIS)) ?: continue
                if (message.type == WifiSetupType.PING_REQUEST) send(WifiSetupType.PING_RESPONSE, message.payload)
            }
        } catch (_: IOException) {
            // The phone closed the channel, or the session ended and closed it.
        }
    }

    private fun send(type: Int, payload: ByteArray) = stream.send(WifiSetupCodec.frame(type, payload))

    /** The next whole message, or null at [deadline] or once [stop] holds; throws when the phone closes the channel. */
    private fun read(deadline: Long, stop: () -> Boolean = { false }): WifiSetupMessage? {
        while (true) {
            WifiSetupCodec.parse(buffer, buffered)?.let { (message, used) ->
                System.arraycopy(buffer, used, buffer, 0, buffered - used)
                buffered -= used
                return message
            }
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0 || stop()) return null
            val chunk = stream.recv(READ_CHUNK, minOf(left, POLL_MILLIS)) ?: continue
            if (chunk.isEmpty()) throw AndroidAutoException(AndroidAutoFailure.DISCONNECTED, "the phone closed the Bluetooth setup")
            if (buffered + chunk.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, buffered + chunk.size))
            System.arraycopy(chunk, 0, buffer, buffered, chunk.size)
            buffered += chunk.size
        }
    }

    private fun deadlineAfter(millis: Long) = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)

    internal companion object {
        /** The phone asks for credentials within a few seconds of the start request. */
        const val FIRST_REPLY_MILLIS = 15_000L
        /** Associating, WPA handshake and DHCP, counted from the last credentials sent. */
        const val JOIN_MILLIS = 30_000L
        private const val HOLD_POLL_MILLIS = 1_000L
        private const val POLL_MILLIS = 250L
        private const val READ_CHUNK = 4096
    }
}
