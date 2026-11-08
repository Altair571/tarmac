// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto.proto

import java.io.ByteArrayOutputStream

/**
 * The part of protobuf (proto2 wire format) that Android Auto's head-unit messages use: varints,
 * zigzag, length-delimited strings, bytes and nested messages. Writing it by hand keeps the
 * module free of protobuf-java and generated code for about twenty small messages.
 */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    /** int32, int64, uint32, uint64 and enum. A negative int32 is sign-extended to ten bytes, as protobuf does. */
    fun int(field: Int, value: Long): ProtoWriter = apply {
        tag(field, WIRE_VARINT)
        rawVarint(value)
    }

    fun int(field: Int, value: Int): ProtoWriter = int(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1L else 0L)

    fun sint(field: Int, value: Int): ProtoWriter = int(field, ((value shl 1) xor (value shr 31)).toLong() and 0xFFFF_FFFFL)

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, WIRE_LENGTH)
        rawVarint(value.size.toLong())
        out.write(value)
    }

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    private fun rawVarint(value: Long) {
        var remaining = value
        while (remaining and 0x7FL.inv() != 0L) {
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        out.write(remaining.toInt())
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5
    }
}

/** One decoded field: [value] holds a varint or fixed number, [bytes] a length-delimited payload. */
internal class ProtoField(val number: Int, val wire: Int, val value: Long, val bytes: ByteArray?)

/** A parsed message. Missing fields read as the given default, like proto2's optional fields. */
internal class ProtoMessage private constructor(private val fields: List<ProtoField>) {
    fun has(number: Int): Boolean = fields.any { it.number == number }

    fun long(number: Int, default: Long = 0): Long = fields.lastOrNull { it.number == number && it.bytes == null }?.value ?: default

    fun int(number: Int, default: Int = 0): Int = long(number, default.toLong()).toInt()

    fun bool(number: Int, default: Boolean = false): Boolean = long(number, if (default) 1 else 0) != 0L

    fun sint(number: Int, default: Int = 0): Int {
        if (!has(number)) return default
        val raw = long(number).toInt()
        return (raw ushr 1) xor -(raw and 1)
    }

    fun bytes(number: Int): ByteArray? = fields.lastOrNull { it.number == number && it.bytes != null }?.bytes

    fun string(number: Int): String? = bytes(number)?.toString(Charsets.UTF_8)

    fun message(number: Int): ProtoMessage? = bytes(number)?.let(::parse)

    fun messages(number: Int): List<ProtoMessage> = fields.filter { it.number == number && it.bytes != null }.map { parse(it.bytes!!) }

    companion object {
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ProtoMessage {
            val end = offset + length
            require(offset >= 0 && length >= 0 && end <= data.size) { "protobuf range outside the buffer" }
            var position = offset
            val fields = ArrayList<ProtoField>()

            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (position >= end) throw ProtoException("truncated varint")
                    val byte = data[position++].toInt()
                    result = result or ((byte and 0x7F).toLong() shl shift)
                    if (byte and 0x80 == 0) return result
                    shift += 7
                    if (shift >= 64) throw ProtoException("varint longer than ten bytes")
                }
            }

            fun fixed(size: Int): Long {
                if (end - position < size) throw ProtoException("truncated fixed field")
                var result = 0L
                for (i in 0 until size) result = result or ((data[position + i].toLong() and 0xFF) shl (8 * i))
                position += size
                return result
            }

            while (position < end) {
                val tag = varint()
                val number = (tag ushr 3).toInt()
                if (number <= 0) throw ProtoException("field number $number")
                when (val wire = (tag and 7).toInt()) {
                    ProtoWriter.WIRE_VARINT -> fields += ProtoField(number, wire, varint(), null)
                    ProtoWriter.WIRE_FIXED64 -> fields += ProtoField(number, wire, fixed(8), null)
                    ProtoWriter.WIRE_FIXED32 -> fields += ProtoField(number, wire, fixed(4), null)
                    ProtoWriter.WIRE_LENGTH -> {
                        val size = varint()
                        if (size < 0 || size > end - position) throw ProtoException("length $size past the end")
                        fields += ProtoField(number, wire, 0, data.copyOfRange(position, position + size.toInt()))
                        position += size.toInt()
                    }
                    else -> throw ProtoException("unsupported wire type $wire")
                }
            }
            return ProtoMessage(fields)
        }
    }
}

internal class ProtoException(message: String) : IllegalArgumentException(message)

internal fun proto(build: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(build).toByteArray()
