package com.shilapi.xcertplay.androidauto.wireless

import android.bluetooth.BluetoothDevice
import com.shilapi.xcertplay.androidauto.wireless.AndroidAutoBluetoothStart.Decision
import com.shilapi.xcertplay.androidauto.wireless.AndroidAutoBluetoothStart.Event
import org.junit.Assert.assertEquals
import org.junit.Test

/** When a Bluetooth connection starts wireless Android Auto, and how the screen comes up. */
class AndroidAutoBluetoothStartTest {
    private val phone = "12:34:56:78:9A:BC"

    private fun event(
        action: String? = BluetoothDevice.ACTION_ACL_CONNECTED,
        address: String? = phone,
        enabled: Boolean = true,
        remembered: Set<String> = setOf(phone),
        busy: Boolean = false,
        millisSinceLastStart: Long? = null,
        sdkInt: Int = 29,
        canDrawOverlays: Boolean = true,
    ) = AndroidAutoBluetoothStart.decide(Event(action, address, enabled, remembered, busy, millisSinceLastStart, sdkInt, canDrawOverlays))

    @Test fun aRememberedPhoneOpensAndroidAuto() {
        assertEquals(Decision.OPEN, event())
    }

    @Test fun offByDefaultMeansNothing() {
        assertEquals(Decision.IGNORE, event(enabled = false))
    }

    @Test fun onlyConnectionEventsCount() {
        assertEquals(Decision.IGNORE, event(action = BluetoothDevice.ACTION_ACL_DISCONNECTED))
        assertEquals(Decision.IGNORE, event(action = null))
    }

    /** An iPhone, a headset or a phone that never used wireless Android Auto here is left to CarPlay or alone. */
    @Test fun anUnknownDeviceIsIgnored() {
        assertEquals(Decision.IGNORE, event(address = "AA:BB:CC:DD:EE:FF"))
        assertEquals(Decision.IGNORE, event(remembered = emptySet()))
    }

    /** No address means the event had no device or the app may not read it (no Nearby devices permission). */
    @Test fun anUnreadableDeviceIsIgnored() {
        assertEquals(Decision.IGNORE, event(address = null))
    }

    @Test fun addressesMatchWhateverTheirCase() {
        assertEquals(Decision.OPEN, event(address = phone.lowercase()))
        assertEquals(Decision.OPEN, event(remembered = setOf(phone.lowercase())))
    }

    @Test fun aRunningSessionOrCarPlayIsLeftAlone() {
        assertEquals(Decision.IGNORE, event(busy = true))
    }

    /** One car wake-up can bring several connection events; only the first starts Android Auto. */
    @Test fun aBurstOfEventsStartsOnce() {
        assertEquals(Decision.IGNORE, event(millisSinceLastStart = 0))
        assertEquals(Decision.IGNORE, event(millisSinceLastStart = AndroidAutoBluetoothStart.REPEAT_MILLIS - 1))
        assertEquals(Decision.OPEN, event(millisSinceLastStart = AndroidAutoBluetoothStart.REPEAT_MILLIS))
    }

    /** Android 10+ blocks a background activity start unless the app may draw over other apps. */
    @Test fun android10WithoutOverlayPostsTheStartNotification() {
        assertEquals(Decision.NOTIFY, event(sdkInt = 29, canDrawOverlays = false))
        assertEquals(Decision.NOTIFY, event(sdkInt = 36, canDrawOverlays = false))
        assertEquals(Decision.OPEN, event(sdkInt = 29, canDrawOverlays = true))
        assertEquals(Decision.OPEN, event(sdkInt = 28, canDrawOverlays = false))
    }
}
