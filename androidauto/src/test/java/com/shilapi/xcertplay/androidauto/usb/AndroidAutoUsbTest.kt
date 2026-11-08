package com.shilapi.xcertplay.androidauto.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAutoUsbTest {
    private val mtp = UsbInterfaceClass(0xFF, 0xFF, 0)
    private val adb = UsbInterfaceClass(0xFF, 0x42, 1)
    private val ptp = UsbInterfaceClass(6, 1, 1)
    private val usbMux = UsbInterfaceClass(0xFF, 0xFE, 2)
    private val storage = UsbInterfaceClass(8, 6, 0x50)
    private val audio = UsbInterfaceClass(1, 1, 0)

    @Test fun accessoryModeIsReady() {
        assertEquals(AndroidAutoUsbKind.ACCESSORY, AndroidAutoUsb.classify(0x18D1, 0x2D00, 0, listOf(mtp)))
        assertEquals(AndroidAutoUsbKind.ACCESSORY, AndroidAutoUsb.classify(0x18D1, 0x2D01, 0, listOf(mtp, adb)))
    }

    @Test fun androidPhonesInTheirOrdinaryModes() {
        assertEquals(AndroidAutoUsbKind.PHONE, AndroidAutoUsb.classify(0x04E8, 0x6860, 0, listOf(mtp)))
        assertEquals(AndroidAutoUsbKind.PHONE, AndroidAutoUsb.classify(0x18D1, 0x4EE7, 0, listOf(mtp, adb)))
        assertEquals(AndroidAutoUsbKind.PHONE, AndroidAutoUsb.classify(0x2717, 0xFF48, 0, listOf(ptp)))
    }

    /** An iPhone shows PTP and usbmux; it belongs to CarPlay. */
    @Test fun iPhoneIsNotAnAndroidPhone() {
        assertEquals(AndroidAutoUsbKind.OTHER, AndroidAutoUsb.classify(0x05AC, 0x12A8, 0, listOf(ptp, usbMux)))
    }

    @Test fun otherDevicesAreRefused() {
        assertEquals(AndroidAutoUsbKind.OTHER, AndroidAutoUsb.classify(0x0781, 0x5581, 0, listOf(storage)))
        assertEquals(AndroidAutoUsbKind.OTHER, AndroidAutoUsb.classify(0x0D8C, 0x0014, 0, listOf(audio)))
        assertEquals(AndroidAutoUsbKind.OTHER, AndroidAutoUsb.classify(0x05E3, 0x0610, 9, emptyList()))
    }
}
