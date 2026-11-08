package com.shilapi.xcertplay

import android.view.Surface
import com.shilapi.xcertplay.androidauto.AndroidAuto
import com.shilapi.xcertplay.androidauto.AndroidAutoSettings
import com.shilapi.xcertplay.androidauto.wireless.AndroidAutoWifiNetwork
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidAutoSettingsTest {
    /** One driving-side choice places both interfaces; Android Auto reads CarPlay's own setting. */
    @Test fun drivingSideFollowsTheVehicleSetting() {
        val context = RuntimeEnvironment.getApplication()

        AirPlayPersistence.saveRightHandDrive(context, true)
        assertTrue(AndroidAutoSettings.rightHandDrive(context))
        AirPlayPersistence.saveRightHandDrive(context, false)
        assertFalse(AndroidAutoSettings.rightHandDrive(context))
    }

    /** Wireless Android Auto uses the network wireless CarPlay is set up with, or Wi-Fi Direct when that was never filled in. */
    @Test fun wirelessUsesTheCarPlayNetwork() {
        val context = RuntimeEnvironment.getApplication()

        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        assertEquals(WirelessHotspotMode.WIFI_P2P, AndroidAutoWifiNetwork.mode(context))

        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(context, "")
        assertEquals(WirelessHotspotMode.WIFI_P2P, AndroidAutoWifiNetwork.mode(context))
        AirPlayPersistence.saveManualHotspotSsid(context, "Car hotspot")
        assertEquals(WirelessHotspotMode.MANUAL, AndroidAutoWifiNetwork.mode(context))

        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(context, "", "")
        assertEquals(WirelessHotspotMode.WIFI_P2P, AndroidAutoWifiNetwork.mode(context))
        AirPlayPersistence.saveExistingWifiCredentials(context, "Garage", "passphrase")
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AndroidAutoWifiNetwork.mode(context))
    }

    /** The same runtime permissions as a wireless CarPlay start, for every network and Android release. */
    @Test fun wirelessAsksForWhatWirelessCarPlayAsksFor() {
        for (mode in WirelessHotspotMode.entries) for (sdk in 24..37) {
            assertEquals("$mode on API $sdk", WirelessPermissions.required(mode, sdk), AndroidAutoWifiNetwork.permissions(mode, sdk))
        }
    }

    /** Wireless Android Auto stays away from the head unit's Wi-Fi while CarPlay holds a session. */
    @Test fun androidAutoSeesTheCarPlaySession() {
        CarPlayBackgroundSession.clear()
        assertFalse(AndroidAuto.carPlayActive())
        CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java), 800, 480, this,
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        try {
            assertTrue(AndroidAuto.carPlayActive())
        } finally {
            CarPlayBackgroundSession.clear()
        }
        assertFalse(AndroidAuto.carPlayActive())
    }
}
