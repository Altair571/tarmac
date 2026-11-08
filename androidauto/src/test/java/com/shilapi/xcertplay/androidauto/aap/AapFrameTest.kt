package com.shilapi.xcertplay.androidauto.aap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException

class AapFrameTest {
    @Test fun versionRequestFrameMatchesTheReceivers() {
        // open-headunit's raw version request: channel 0, flags 3, length 6, type 1, version 1.1.
        val frame = AapFrameCodec.frames(AapChannel.CONTROL, 0, AapFrameCodec.plaintext(ControlType.VERSION_REQUEST,
            AapMessages.versionRequest())) { bytes, offset, length -> bytes.copyOfRange(offset, offset + length) }.single()

        assertArrayEquals(byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 1), frame)
    }

    @Test fun largeMessagesSplitWithTotalLengthOnTheFirstFragment() {
        val plain = ByteArray(AapFrameCodec.MAX_FRAGMENT * 2 + 10) { it.toByte() }
        val frames = AapFrameCodec.frames(AapChannel.VIDEO, AapFlags.ENCRYPTED, plain) { bytes, offset, length ->
            bytes.copyOfRange(offset, offset + length)
        }

        assertEquals(3, frames.size)
        assertEquals(AapFlags.ENCRYPTED or AapFlags.FIRST, frames[0][1].toInt())
        assertEquals(AapFlags.ENCRYPTED, frames[1][1].toInt())
        assertEquals(AapFlags.ENCRYPTED or AapFlags.LAST, frames[2][1].toInt())
        val total = ((frames[0][4].toInt() and 0xFF) shl 24) or ((frames[0][5].toInt() and 0xFF) shl 16) or
            ((frames[0][6].toInt() and 0xFF) shl 8) or (frames[0][7].toInt() and 0xFF)
        assertEquals(plain.size, total)

        val input = ByteArrayInputStream(frames.reduce(ByteArray::plus))
        val reassembler = AapReassembler()
        val messages = List(3) {
            val frame = AapFrameCodec.read { size -> input.readNBytesCompat(size) }
            reassembler.accept(frame, frame.payload)
        }
        assertNull(messages[0])
        assertNull(messages[1])
        val message = messages[2]!!
        assertEquals((plain[0].toInt() shl 8) or (plain[1].toInt() and 0xFF), message.type)
        assertArrayEquals(plain.copyOfRange(2, plain.size), message.payload)
    }

    /** A fragment boundary may fall inside the two-byte type; the type is read once the message is whole. */
    @Test fun typeSplitAcrossFragments() {
        val reassembler = AapReassembler()
        assertNull(reassembler.accept(AapFrame(2, AapFlags.FIRST, byteArrayOf(0x80.toByte())), byteArrayOf(0x80.toByte())))
        val message = reassembler.accept(AapFrame(2, AapFlags.LAST, byteArrayOf(0x03, 7)), byteArrayOf(0x03, 7))!!

        assertEquals(MediaType.CONFIG, message.type)
        assertArrayEquals(byteArrayOf(7), message.payload)
    }

    @Test fun channelsReassembleIndependently() {
        val reassembler = AapReassembler()
        assertNull(reassembler.accept(AapFrame(2, AapFlags.FIRST, ByteArray(0)), byteArrayOf(0, 0, 1)))
        val audio = reassembler.accept(AapFrame(6, AapFlags.WHOLE, ByteArray(0)), byteArrayOf(0, 0, 9))!!
        val video = reassembler.accept(AapFrame(2, AapFlags.LAST, ByteArray(0)), byteArrayOf(2))!!

        assertEquals(6, audio.channel)
        assertArrayEquals(byteArrayOf(9), audio.payload)
        assertArrayEquals(byteArrayOf(1, 2), video.payload)
    }

    @Test(expected = IOException::class)
    fun continuationWithoutStartFails() {
        AapReassembler().accept(AapFrame(2, AapFlags.LAST, ByteArray(0)), byteArrayOf(1, 2))
    }

    @Test fun controlFlagMarksControlTypesOnServiceChannels() {
        val message = AapMessage(AapChannel.VIDEO, AapFlags.WHOLE or AapFlags.CONTROL or AapFlags.ENCRYPTED, ControlType.CHANNEL_OPEN_REQUEST, ByteArray(0))

        assertTrue(message.control)
        assertTrue(ControlType.isControl(ControlType.AUDIO_FOCUS_REQUEST))
        assertTrue(!ControlType.isControl(MediaType.SETUP))
    }

    private fun ByteArrayInputStream.readNBytesCompat(size: Int): ByteArray {
        val out = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val read = read(out, filled, size - filled)
            if (read < 0) throw EOFException()
            filled += read
        }
        return out
    }
}
