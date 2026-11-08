// SPDX-License-Identifier: AGPL-3.0-or-later
// Frame and fragment rules ported from open-headunit (AapMessageFraming, AapMessageReassembler).
package com.shilapi.xcertplay.androidauto.aap

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * One Android Auto transport frame:
 * `[channel u8][flags u8][length u16 BE]([total u32 BE])[payload]`.
 * The total length is present only on the first fragment of a fragmented message.
 */
internal class AapFrame(val channel: Int, val flags: Int, val payload: ByteArray) {
    val first: Boolean get() = flags and AapFlags.FIRST != 0
    val last: Boolean get() = flags and AapFlags.LAST != 0
    val encrypted: Boolean get() = flags and AapFlags.ENCRYPTED != 0
}

internal object AapFlags {
    const val FIRST = 0x01
    const val LAST = 0x02
    /** A control-type message (ping, focus, channel open) sent on a service channel. */
    const val CONTROL = 0x04
    const val ENCRYPTED = 0x08
    const val WHOLE = FIRST or LAST
}

/** A complete message: its type and the bytes after it, decrypted and reassembled. */
internal class AapMessage(val channel: Int, val flags: Int, val type: Int, val payload: ByteArray) {
    val control: Boolean get() = flags and AapFlags.CONTROL != 0
}

internal object AapFrameCodec {
    const val HEADER_BYTES = 4
    /** Phones and head units fragment at 16 KiB of plaintext. */
    const val MAX_FRAGMENT = 0x4000

    fun encode(channel: Int, flags: Int, payload: ByteArray, totalLength: Int? = null): ByteArray {
        require(payload.size <= 0xFFFF) { "frame payload ${payload.size} does not fit 16 bits" }
        val extra = if (totalLength != null) 4 else 0
        val frame = ByteArray(HEADER_BYTES + extra + payload.size)
        frame[0] = channel.toByte()
        frame[1] = flags.toByte()
        frame[2] = (payload.size shr 8).toByte()
        frame[3] = payload.size.toByte()
        if (totalLength != null) {
            frame[4] = (totalLength ushr 24).toByte()
            frame[5] = (totalLength shr 16).toByte()
            frame[6] = (totalLength shr 8).toByte()
            frame[7] = totalLength.toByte()
        }
        System.arraycopy(payload, 0, frame, HEADER_BYTES + extra, payload.size)
        return frame
    }

    /** `[type u16 BE][body]`, the plaintext of one message before it is split or encrypted. */
    fun plaintext(type: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(2 + body.size)
        bytes[0] = (type shr 8).toByte()
        bytes[1] = type.toByte()
        System.arraycopy(body, 0, bytes, 2, body.size)
        return bytes
    }

    /**
     * Splits [plain] into frames for [channel]; [seal] encrypts each fragment (identity before TLS).
     * [baseFlags] carries CONTROL and ENCRYPTED; FIRST and LAST are added here.
     */
    fun frames(channel: Int, baseFlags: Int, plain: ByteArray, seal: (ByteArray, Int, Int) -> ByteArray): List<ByteArray> {
        if (plain.size <= MAX_FRAGMENT) {
            return listOf(encode(channel, baseFlags or AapFlags.WHOLE, seal(plain, 0, plain.size)))
        }
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (offset < plain.size) {
            val size = minOf(MAX_FRAGMENT, plain.size - offset)
            val first = offset == 0
            val last = offset + size == plain.size
            val flags = baseFlags or (if (first) AapFlags.FIRST else 0) or (if (last) AapFlags.LAST else 0)
            frames += encode(channel, flags, seal(plain, offset, size), if (first) plain.size else null)
            offset += size
        }
        return frames
    }

    /** Reads one frame; [read] must return exactly the number of bytes asked for or throw. */
    fun read(read: (Int) -> ByteArray): AapFrame {
        val header = read(HEADER_BYTES)
        val channel = header[0].toInt() and 0xFF
        val flags = header[1].toInt() and 0xFF
        val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        // The total length of a fragmented message; reassembly does not need it.
        if (flags and AapFlags.FIRST != 0 && flags and AapFlags.LAST == 0) read(4)
        return AapFrame(channel, flags, read(length))
    }
}

/**
 * Joins fragments per channel. A fragment boundary may fall anywhere, even inside the two-byte
 * type, so the type is read only once the message is complete.
 */
internal class AapReassembler(private val maxMessageBytes: Int = 8 shl 20) {
    private val partial = HashMap<Int, ByteArrayOutputStream>()

    /** The complete message once [plain], the plaintext of [frame], ends one; otherwise null. */
    fun accept(frame: AapFrame, plain: ByteArray): AapMessage? {
        val bytes: ByteArray = when {
            frame.first && frame.last -> {
                partial.remove(frame.channel)
                plain
            }
            frame.first -> {
                partial[frame.channel] = ByteArrayOutputStream(plain.size * 4).apply { write(plain) }
                return null
            }
            else -> {
                val buffer = partial[frame.channel] ?: throw IOException("continuation on channel ${frame.channel} without a first fragment")
                buffer.write(plain)
                if (buffer.size() > maxMessageBytes) throw IOException("message on channel ${frame.channel} exceeds $maxMessageBytes bytes")
                if (!frame.last) return null
                partial.remove(frame.channel)
                buffer.toByteArray()
            }
        }
        if (bytes.size < 2) throw IOException("message on channel ${frame.channel} has no type")
        val type = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        return AapMessage(frame.channel, frame.flags, type, bytes.copyOfRange(2, bytes.size))
    }
}
