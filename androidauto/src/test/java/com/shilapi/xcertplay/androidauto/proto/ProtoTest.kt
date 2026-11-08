package com.shilapi.xcertplay.androidauto.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoTest {
    @Test fun encodesLikeProtobuf() {
        // Known encodings from the protobuf documentation.
        assertArrayEquals(bytes(0x08, 0x96, 0x01), proto { int(1, 150) })
        assertArrayEquals(bytes(0x12, 0x07, 0x74, 0x65, 0x73, 0x74, 0x69, 0x6E, 0x67), proto { string(2, "testing") })
        assertArrayEquals(bytes(0x1A, 0x03, 0x08, 0x96, 0x01), proto { message(3) { int(1, 150) } })
        // AUTH_COMPLETE's body as every head unit sends it.
        assertArrayEquals(bytes(0x08, 0x00), proto { int(1, 0) })
    }

    @Test fun negativeInt32IsTenBytes() {
        val encoded = proto { int(1, -1) }

        assertEquals(11, encoded.size)
        assertEquals(-1, ProtoMessage.parse(encoded).int(1))
    }

    @Test fun zigzagRoundTrips() {
        listOf(0, 1, -1, 2, -2, Int.MAX_VALUE, Int.MIN_VALUE).forEach { value ->
            assertEquals(value, ProtoMessage.parse(proto { sint(1, value) }).sint(1))
        }
    }

    @Test fun readsNestedRepeatedAndMissingFields() {
        val message = ProtoMessage.parse(proto {
            message(1) { int(1, 2) }
            message(1) { int(1, 3) }
            bool(2, true)
            bytes(4, byteArrayOf(9, 8))
            string(5, "Pixel")
        })

        assertEquals(listOf(2, 3), message.messages(1).map { it.int(1) })
        assertTrue(message.bool(2))
        assertArrayEquals(byteArrayOf(9, 8), message.bytes(4))
        assertEquals("Pixel", message.string(5))
        assertNull(message.string(6))
        assertFalse(message.has(7))
        assertEquals(42, message.int(7, 42))
    }

    @Test fun skipsFixedWidthFields() {
        val data = bytes(0x09, 1, 0, 0, 0, 0, 0, 0, 0, 0x15, 2, 0, 0, 0, 0x18, 7)
        val message = ProtoMessage.parse(data)

        assertEquals(1L, message.long(1))
        assertEquals(2, message.int(2))
        assertEquals(7, message.int(3))
    }

    @Test(expected = ProtoException::class)
    fun rejectsTruncatedLength() {
        ProtoMessage.parse(bytes(0x12, 0x05, 0x61))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
