// SPDX-License-Identifier: AGPL-3.0-or-later
// The AOA request sequence and accessory strings follow Headunit (Michael Reid) and open-headunit
// (UsbAccessoryMode): manufacturer "Android" and model "Android Auto" hand the accessory to
// Android Auto on the phone.
package com.shilapi.xcertplay.androidauto.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.androidauto.AndroidAutoException
import com.shilapi.xcertplay.androidauto.AndroidAutoFailure
import com.shilapi.xcertplay.androidauto.AndroidAutoLog

/** What a USB device is to Android Auto. */
enum class AndroidAutoUsbKind {
    /** A phone already switched to accessory mode, ready for the session. */
    ACCESSORY,
    /** An Android phone in its ordinary mode; the head unit switches it first. */
    PHONE,
    /** Anything else: an iPhone, a hub, a storage stick, a Bluetooth dongle. */
    OTHER,
}

/** A USB interface as (class, subclass, protocol). */
internal data class UsbInterfaceClass(val usbClass: Int, val subclass: Int, val protocol: Int)

object AndroidAutoUsb {
    internal const val GOOGLE_VENDOR = 0x18D1
    internal const val ACCESSORY_PRODUCT = 0x2D00
    internal const val ACCESSORY_ADB_PRODUCT = 0x2D01
    internal const val APPLE_VENDOR = 0x05AC
    private const val CLASS_HUB = 9
    private const val CLASS_STILL_IMAGE = 6
    private const val CLASS_VENDOR = 0xFF
    private const val SUBCLASS_ADB = 0x42

    private const val ACC_REQ_GET_PROTOCOL = 51
    private const val ACC_REQ_SEND_STRING = 52
    private const val ACC_REQ_START = 53
    private const val CONTROL_TIMEOUT_MILLIS = 1_000
    private val ACCESSORY_STRINGS = listOf(
        "Android",
        "Android Auto",
        "Android Auto",
        "2.0.1",
        "https://developer.android.com/auto/index.html",
        "HU-AAAAAA001",
    )

    fun classify(device: UsbDevice): AndroidAutoUsbKind = classify(
        device.vendorId,
        device.productId,
        device.deviceClass,
        (0 until device.interfaceCount).map { index ->
            device.getInterface(index).let { UsbInterfaceClass(it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
        },
    )

    internal fun classify(vendorId: Int, productId: Int, deviceClass: Int, interfaces: List<UsbInterfaceClass>): AndroidAutoUsbKind = when {
        vendorId == GOOGLE_VENDOR && (productId == ACCESSORY_PRODUCT || productId == ACCESSORY_ADB_PRODUCT) -> AndroidAutoUsbKind.ACCESSORY
        // An iPhone belongs to CarPlay, whatever interfaces it shows.
        vendorId == APPLE_VENDOR -> AndroidAutoUsbKind.OTHER
        deviceClass == CLASS_HUB || interfaces.any { it.usbClass == CLASS_HUB } -> AndroidAutoUsbKind.OTHER
        // Android shows MTP and the accessory as vendor-class interfaces, ADB as vendor class 0x42,
        // and PTP as still-image class. A plain storage stick or audio dongle shows none of these.
        interfaces.any { it.usbClass == CLASS_VENDOR && (it.subclass == CLASS_VENDOR || it.subclass == SUBCLASS_ADB) } ||
            interfaces.any { it.usbClass == CLASS_STILL_IMAGE } -> AndroidAutoUsbKind.PHONE
        else -> AndroidAutoUsbKind.OTHER
    }

    /** The phone to use: one in accessory mode first, then any Android phone. */
    fun find(manager: UsbManager): UsbDevice? {
        val devices = manager.deviceList.values
        return devices.firstOrNull { classify(it) == AndroidAutoUsbKind.ACCESSORY }
            ?: devices.firstOrNull { classify(it) == AndroidAutoUsbKind.PHONE }
    }

    /**
     * Asks [device] to switch to accessory mode. It then leaves the bus and returns as a new
     * accessory device, which needs its own USB permission. Throws when the phone does not speak AOA.
     */
    fun switchToAccessory(manager: UsbManager, device: UsbDevice) {
        val connection = manager.openDevice(device) ?: throw AndroidAutoException(AndroidAutoFailure.USB, "Android could not open the phone")
        try {
            val version = ByteArray(2)
            val read = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR, ACC_REQ_GET_PROTOCOL, 0, 0, version, 2, CONTROL_TIMEOUT_MILLIS,
            )
            val protocol = (version[0].toInt() and 0xFF) or ((version[1].toInt() and 0xFF) shl 8)
            if (read != 2 || protocol < 1) {
                throw AndroidAutoException(AndroidAutoFailure.NOT_ANDROID_AUTO, "the phone does not offer Android Auto over USB (AOA $read/$protocol)")
            }
            AndroidAutoLog.i("AOA protocol $protocol")
            ACCESSORY_STRINGS.forEachIndexed { index, value -> sendString(connection, index, value) }
            // The phone may leave the bus before it acknowledges START; it still received it.
            connection.controlTransfer(UsbConstants.USB_TYPE_VENDOR, ACC_REQ_START, 0, 0, null, 0, CONTROL_TIMEOUT_MILLIS)
        } finally {
            connection.close()
        }
    }

    private fun sendString(connection: UsbDeviceConnection, index: Int, value: String) {
        val bytes = (value + '\u0000').toByteArray(Charsets.UTF_8)
        val written = connection.controlTransfer(
            UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, index, bytes, bytes.size, CONTROL_TIMEOUT_MILLIS,
        )
        if (written < 0) throw AndroidAutoException(AndroidAutoFailure.NOT_ANDROID_AUTO, "the phone refused accessory string $index")
    }

    /** Opens the accessory interface of a phone in accessory mode. */
    fun openAccessory(manager: UsbManager, device: UsbDevice): UsbAccessoryStream {
        val connection = manager.openDevice(device) ?: throw AndroidAutoException(AndroidAutoFailure.USB, "Android could not open the accessory")
        try {
            val accessory = (0 until device.interfaceCount).map(device::getInterface).firstOrNull { candidate ->
                candidate.interfaceClass == CLASS_VENDOR && candidate.interfaceSubclass == CLASS_VENDOR
            } ?: throw AndroidAutoException(AndroidAutoFailure.USB, "the accessory has no Android Auto interface")
            val endpoints = (0 until accessory.endpointCount).map(accessory::getEndpoint)
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            if (input == null || output == null) throw AndroidAutoException(AndroidAutoFailure.USB, "the accessory interface has no bulk endpoint pair")
            if (!connection.claimInterface(accessory, true)) throw AndroidAutoException(AndroidAutoFailure.USB, "Android could not claim the accessory interface")
            return UsbAccessoryStream(manager, device, connection, accessory, input, output)
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }
}
