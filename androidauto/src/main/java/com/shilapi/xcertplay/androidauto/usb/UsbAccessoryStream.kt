// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.androidauto.AndroidAutoException
import com.shilapi.xcertplay.androidauto.AndroidAutoFailure
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The accessory's two bulk endpoints as a [BlockingDuplexByteStream]. A bulk transfer reports a
 * timeout and an unplugged phone alike, so a failed read checks whether the phone is still on the
 * bus and ends the stream once it is gone.
 */
class UsbAccessoryStream internal constructor(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val accessory: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
) : BlockingDuplexByteStream {
    @Volatile private var closed = false
    private val released = AtomicBoolean(false)
    private val readBuffer = ByteArray(MAX_TRANSFER)
    private val writeLock = Any()

    override fun send(data: ByteArray) = synchronized(writeLock) {
        var offset = 0
        while (offset < data.size) {
            if (closed) throw AndroidAutoException(AndroidAutoFailure.DISCONNECTED, "USB accessory closed")
            val size = minOf(MAX_TRANSFER, data.size - offset)
            val written = connection.bulkTransfer(output, data, offset, size, WRITE_TIMEOUT_MILLIS)
            if (written < 0) {
                if (attached()) throw AndroidAutoException(AndroidAutoFailure.USB, "USB write failed")
                closed = true
                throw AndroidAutoException(AndroidAutoFailure.DISCONNECTED, "phone left the bus")
            }
            offset += written
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        if (closed) return ByteArray(0)
        val size = minOf(maxBytes, readBuffer.size)
        val read = connection.bulkTransfer(input, readBuffer, size, timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
        return when {
            read > 0 -> readBuffer.copyOf(read)
            closed || !attached() -> ByteArray(0).also { closed = true }
            else -> null
        }
    }

    private fun attached(): Boolean = manager.deviceList.values.any { it.deviceName == device.deviceName }

    override fun close() {
        closed = true
        if (!released.compareAndSet(false, true)) return
        runCatching { connection.releaseInterface(accessory) }
        runCatching { connection.close() }
    }

    private companion object {
        /** Android before 9 rejects a bulk transfer larger than 16 KiB. */
        const val MAX_TRANSFER = 16 * 1024
        const val WRITE_TIMEOUT_MILLIS = 1_000
    }
}
