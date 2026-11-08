package com.shilapi.xcertplay.vehicle

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/** A byte link to an adapter: Bluetooth serial (SPP) or a USB CDC-ACM serial port. */
interface SerialLink : Closeable {
    fun write(bytes: ByteArray)

    /** Up to [buffer]'s size bytes; 0 when nothing came within [timeoutMillis], -1 when the link is gone. */
    fun read(buffer: ByteArray, timeoutMillis: Int): Int
}

/** An OBD-II adapter's serial port profile (SPP) over Bluetooth Classic. */
class BluetoothSerialLink private constructor(private val socket: BluetoothSocket) : SerialLink {
    private val input = socket.inputStream
    private val output = socket.outputStream

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    // BluetoothSocket has no read timeout, so the call waits for data that is already there.
    override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (input.available() == 0) {
            if (System.currentTimeMillis() >= deadline) return 0
            Thread.sleep(POLL_MILLIS)
        }
        return input.read(buffer, 0, minOf(buffer.size, input.available()))
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** The serial port profile every ELM327/STN Bluetooth Classic adapter offers. */
        val SERIAL_PORT_PROFILE: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val POLL_MILLIS = 10L

        /** Connects to the paired adapter at [address]; the caller has checked BLUETOOTH_CONNECT. Blocking. */
        @SuppressLint("MissingPermission")
        fun open(adapter: BluetoothAdapter, address: String): BluetoothSerialLink {
            val device = adapter.getRemoteDevice(address)
            // Many cheap adapters advertise SPP badly; the insecure socket is the usual fallback.
            val socket = try {
                device.createRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE).also { it.connect() }
            } catch (first: IOException) {
                try {
                    device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE).also { it.connect() }
                } catch (second: IOException) {
                    second.addSuppressed(first)
                    throw second
                }
            }
            return BluetoothSerialLink(socket)
        }
    }
}

/**
 * A USB serial port of the CDC-ACM class: STN-based OBD-II adapters (OBDLink SX/EX), and CANable or other
 * slcan firmware. Adapters with an FTDI, CH340 or CP210x bridge chip need their own drivers, which DiPlay
 * does not have.
 */
class CdcAcmSerialLink private constructor(
    private val connection: UsbDeviceConnection,
    private val interfaces: List<UsbInterface>,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
) : SerialLink {
    override fun write(bytes: ByteArray) {
        val sent = connection.bulkTransfer(output, bytes, bytes.size, WRITE_TIMEOUT_MILLIS)
        if (sent < 0) throw IOException("USB write failed")
    }

    // bulkTransfer gives -1 for a timeout and for a lost device alike; the caller notices a lost device
    // when the adapter stops answering.
    override fun read(buffer: ByteArray, timeoutMillis: Int): Int =
        connection.bulkTransfer(input, buffer, buffer.size, timeoutMillis).coerceAtLeast(0)

    override fun close() {
        interfaces.forEach { runCatching { connection.releaseInterface(it) } }
        connection.close()
    }

    companion object {
        private const val CLASS_CDC_DATA = 0x0A
        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21
        private const val DTR_AND_RTS = 0x03
        private const val WRITE_TIMEOUT_MILLIS = 1_000

        /** The data interface with a bulk endpoint each way, or null for a device that is not CDC-ACM. */
        fun dataInterface(device: UsbDevice): UsbInterface? = (0 until device.interfaceCount)
            .map(device::getInterface)
            .firstOrNull { it.interfaceClass == CLASS_CDC_DATA && bulk(it, UsbConstants.USB_DIR_IN) != null &&
                bulk(it, UsbConstants.USB_DIR_OUT) != null }

        fun serialDevices(manager: UsbManager): List<UsbDevice> =
            manager.deviceList.values.filter { dataInterface(it) != null }.sortedBy { it.deviceName }

        /** Opens [device] at [baud] 8N1; null when it cannot be opened (no permission, gone, not CDC-ACM). */
        fun open(manager: UsbManager, device: UsbDevice, baud: Int): CdcAcmSerialLink? {
            val data = dataInterface(device) ?: return null
            val control = (0 until device.interfaceCount).map(device::getInterface)
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            val connection = manager.openDevice(device) ?: return null
            val claimed = listOfNotNull(control, data).filter { connection.claimInterface(it, true) }
            if (data !in claimed) {
                claimed.forEach { connection.releaseInterface(it) }
                connection.close()
                return null
            }
            val controlIndex = (control ?: data).id
            val coding = byteArrayOf(
                (baud and 0xFF).toByte(), (baud shr 8 and 0xFF).toByte(), (baud shr 16 and 0xFF).toByte(),
                (baud shr 24 and 0xFF).toByte(), 0, 0, 8, // 1 stop bit, no parity, 8 data bits
            )
            connection.controlTransfer(REQUEST_TYPE_CLASS_INTERFACE_OUT, SET_LINE_CODING, 0, controlIndex, coding, coding.size, 1_000)
            connection.controlTransfer(REQUEST_TYPE_CLASS_INTERFACE_OUT, SET_CONTROL_LINE_STATE, DTR_AND_RTS, controlIndex, null, 0, 1_000)
            return CdcAcmSerialLink(connection, claimed, bulk(data, UsbConstants.USB_DIR_IN)!!, bulk(data, UsbConstants.USB_DIR_OUT)!!)
        }

        private fun bulk(face: UsbInterface, direction: Int): UsbEndpoint? = (0 until face.endpointCount)
            .map(face::getEndpoint)
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == direction }
    }
}
