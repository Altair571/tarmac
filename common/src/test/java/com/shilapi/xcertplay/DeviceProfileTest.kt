package com.shilapi.xcertplay

import android.util.DisplayMetrics
import com.shilapi.xcertplay.airplay.CarPlaySize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DeviceProfileTest {
    private fun classify(
        automotive: Boolean = false,
        television: Boolean = false,
        telephony: Boolean = false,
        battery: Boolean = false,
        smallestWidthDp: Int = 480,
    ) = DeviceProfile.classify(automotive, television, telephony, battery, smallestWidthDp)

    @Test fun aHeadUnitRunsWithoutABatteryEvenWithASimCard() {
        assertEquals(DeviceKind.HEAD_UNIT, classify(telephony = true, battery = false))
        assertEquals(DeviceKind.HEAD_UNIT, classify(telephony = false, battery = false, smallestWidthDp = 720))
    }

    @Test fun aPhoneHasABatteryAndTelephonyOnASmallScreen() {
        assertEquals(DeviceKind.PHONE, classify(telephony = true, battery = true, smallestWidthDp = 411))
    }

    @Test fun aLargeBatteryScreenIsATablet() {
        assertEquals(DeviceKind.TABLET, classify(telephony = false, battery = true, smallestWidthDp = 800))
    }

    @Test fun automotiveAndTelevisionWinOverEverythingElse() {
        assertEquals(DeviceKind.HEAD_UNIT, classify(automotive = true, telephony = true, battery = true))
        assertEquals(DeviceKind.TV, classify(television = true, battery = false))
    }

    @Test fun aSmallBatteryDeviceWithoutTelephonyStaysAHeadUnit() {
        assertEquals(DeviceKind.HEAD_UNIT, classify(telephony = false, battery = true, smallestWidthDp = 480))
    }

    @Test fun thePanelLongSideComesFromItsRealDensity() {
        // A 6.7-inch 20:9 phone: 2400 × 1080 at about 394 dpi.
        val phone = DisplayMetrics().apply { widthPixels = 1080; heightPixels = 2400; xdpi = 394f; ydpi = 394f }
        assertEquals(155, DeviceProfile.longSideMillimeters(phone))
    }

    @Test fun aPlaceholderDensityIsNotAMeasurement() {
        val headUnit = DisplayMetrics().apply { widthPixels = 1920; heightPixels = 720; xdpi = 0f; ydpi = 0f }
        assertNull(DeviceProfile.longSideMillimeters(headUnit))
    }

    @Test fun aHeadUnitKeepsTheCarPlaySizePreset() {
        // Robolectric reports no battery, so it is a head unit.
        val context = RuntimeEnvironment.getApplication()
        assertEquals(DeviceKind.HEAD_UNIT, DeviceProfile.kind(context))
        assertEquals(CarPlaySize.DEFAULT.widthMillimeters, DeviceProfile.defaultCarPlayWidthMillimeters(context))
        assertEquals(CarPlaySize.DEFAULT.widthMillimeters, AirPlayPersistence.loadWidthPhysicalMm(context))
    }
}
