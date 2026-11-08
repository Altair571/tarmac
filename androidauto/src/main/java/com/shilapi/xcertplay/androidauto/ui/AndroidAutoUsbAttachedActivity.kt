// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto.ui

import android.app.Activity
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import com.shilapi.xcertplay.androidauto.AndroidAutoSettings
import com.shilapi.xcertplay.androidauto.ui.AndroidAutoActivity.Companion.usbDevice
import com.shilapi.xcertplay.androidauto.usb.AndroidAutoUsb
import com.shilapi.xcertplay.androidauto.usb.AndroidAutoUsbKind

/**
 * Receives Android's "device attached" intent for Android phones and opens Android Auto when the
 * driver wants that. It shows nothing itself: the USB filter is broad, so the decision is here.
 */
class AndroidAutoUsbAttachedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = intent.usbDevice()
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && device != null &&
            AndroidAutoUsb.classify(device) != AndroidAutoUsbKind.OTHER && AndroidAutoSettings.openWhenPluggedIn(this)
        ) {
            // A phone plugged in starts the connection with no tap, whether the app was open or not;
            // the time to the first picture counts from here.
            startActivity(AndroidAutoActivity.intent(this).putExtra(UsbManager.EXTRA_DEVICE, device)
                .putExtra(AndroidAutoActivity.EXTRA_SINCE, SystemClock.elapsedRealtime()))
        }
        finish()
    }
}
