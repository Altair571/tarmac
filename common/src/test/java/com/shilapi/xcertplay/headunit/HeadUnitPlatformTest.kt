package com.shilapi.xcertplay.headunit

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitPlatformTest {
    private fun probe(vararg packages: String) = HeadUnitProbe("QUALCOMM", "qcom", packages.toSet())

    @Test fun teyesIsRecognisedByItsSystemServices() {
        assertEquals(SpdTeyesPlatform, HeadUnitPlatforms.detect(probe("com.spd.carinfo", "com.spd.setting")))
        assertEquals(SpdTeyesPlatform, HeadUnitPlatforms.detect(probe("com.spd.carinfo", "com.spd.setting", "com.alink.carplay")))
    }

    @Test fun aGenericQualcommDeviceIsNoHeadUnitPlatform() {
        assertNull(HeadUnitPlatforms.detect(probe()))
        // A side-loaded copy of one TEYES app is not the platform.
        assertNull(HeadUnitPlatforms.detect(probe("com.spd.carinfo")))
        assertNull(HeadUnitPlatforms.detect(probe("com.alink.carplay")))
    }

    @Test fun fytIsRecognisedByItsModuleService() {
        assertEquals(FytSyuPlatform, HeadUnitPlatforms.detect(probe("com.syu.ms", "com.syu.canbus")))
        assertNull(HeadUnitPlatforms.detect(probe("com.syu.ms")))
        assertNull(HeadUnitPlatforms.detect(probe("com.syu.canbus", "com.syu.carlink")))
    }

    @Test fun fytMainModuleUpdatesBecomeCarSignals() {
        assertEquals(HeadUnitSignal.ACC to true, FytSyuPlatform.signalFor(50, intArrayOf(1)))
        assertEquals(HeadUnitSignal.ACC to false, FytSyuPlatform.signalFor(50, intArrayOf(0)))
        assertEquals(HeadUnitSignal.REVERSE to true, FytSyuPlatform.signalFor(12, intArrayOf(1, 0)))
        assertEquals(HeadUnitSignal.NIGHT to false, FytSyuPlatform.signalFor(4, intArrayOf(0)))
        assertNull("radar is no signal here", FytSyuPlatform.signalFor(13, intArrayOf(1)))
        assertNull(FytSyuPlatform.signalFor(50, intArrayOf()))
        assertNull(FytSyuPlatform.signalFor(50, null))
    }

    @Test fun fytAccBroadcastUsesOneAndZero() {
        assertEquals(true, FytSyuPlatform.accFromBroadcast(1))
        assertEquals(false, FytSyuPlatform.accFromBroadcast(0))
        assertNull("missing extra", FytSyuPlatform.accFromBroadcast(-1))
    }

    @Test fun onlyTeyesMapsWheelKeysSoFar() {
        assertTrue(SpdTeyesPlatform.mapsWheelKeys)
        assertFalse(FytSyuPlatform.mapsWheelKeys)
        assertNull(FytSyuPlatform.keyAction(KeyEvent.KEYCODE_CALL, 8224))
        assertEquals("/dev/i2c-2", FytSyuPlatform.mfiI2cPath())
    }

    @Test fun everyPlatformAsksOnlyForPackagesItDeclares() {
        for (platform in HeadUnitPlatforms.all) {
            assertTrue(platform.id, platform.matches(probe(*platform.detectionPackages.toTypedArray())))
        }
    }

    @Test fun voiceAndCallKeysFromTheTeyesWheelGoToCarPlay() {
        val swc1 = 8224
        val canSwc2 = 8273
        assertEquals(HeadUnitKeyAction.SIRI, SpdTeyesPlatform.keyAction(362, swc1))
        assertEquals(HeadUnitKeyAction.SIRI, SpdTeyesPlatform.keyAction(KeyEvent.KEYCODE_VOICE_ASSIST, canSwc2))
        assertEquals(HeadUnitKeyAction.ANSWER_CALL, SpdTeyesPlatform.keyAction(KeyEvent.KEYCODE_CALL, swc1))
        assertEquals(HeadUnitKeyAction.ANSWER_CALL, SpdTeyesPlatform.keyAction(424, swc1))
        assertEquals(HeadUnitKeyAction.END_CALL, SpdTeyesPlatform.keyAction(KeyEvent.KEYCODE_ENDCALL, canSwc2))
        assertEquals(HeadUnitKeyAction.END_CALL, SpdTeyesPlatform.keyAction(425, canSwc2))
    }

    @Test fun mediaKeysAndOtherDevicesAreLeftAlone() {
        assertNull(SpdTeyesPlatform.keyAction(KeyEvent.KEYCODE_MEDIA_NEXT, 8224))
        assertNull(SpdTeyesPlatform.keyAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 8224))
        // A USB keyboard or a phone's own buttons keep their meaning.
        assertNull(SpdTeyesPlatform.keyAction(KeyEvent.KEYCODE_CALL, 3))
        assertNull(SpdTeyesPlatform.keyAction(362, -1))
    }

    @Test fun keysGoToCarPlayOnlyWhileASessionRuns() {
        assertNull(HeadUnitWheelKeys.route(SpdTeyesPlatform, 362, 8224, sessionRunning = false))
        assertEquals(HeadUnitKeyAction.SIRI, HeadUnitWheelKeys.route(SpdTeyesPlatform, 362, 8224, sessionRunning = true))
        assertNull(HeadUnitWheelKeys.route(null, 362, 8224, sessionRunning = true))
    }

    @Test fun theChipBusComesFromTheAlinkConfig() {
        val shipped = """
            {
            	"accName": "SPD AUDIO",
            	"apSsid": "SPD_AP",
            	"hicarDelay": 20,
            	"i2cPort": "2",
            	"wifiNode": "wlan2"
            }
        """.trimIndent()
        assertEquals("/dev/i2c-2", SpdTeyesPlatform.i2cPathFromAlinkConfig(shipped))
        assertEquals("/dev/i2c-11", SpdTeyesPlatform.i2cPathFromAlinkConfig("{\"i2cPort\":11}"))
        assertNull(SpdTeyesPlatform.i2cPathFromAlinkConfig("{\"apSsid\": \"SPD_AP\"}"))
        assertNull(SpdTeyesPlatform.i2cPathFromAlinkConfig("{\"i2cPort\": \"../../x\"}"))
        assertNull(SpdTeyesPlatform.i2cPathFromAlinkConfig(null))
    }

    @Test fun anUnsetStartingAppIsNoApp() {
        assertNull(SpdTeyesPlatform.startingAppFrom(null))
        assertNull(SpdTeyesPlatform.startingAppFrom(""))
        assertNull(SpdTeyesPlatform.startingAppFrom("NULL"))
        assertEquals("com.altair571.tarmac", SpdTeyesPlatform.startingAppFrom(" com.altair571.tarmac "))
    }

    @Test fun implausibleCanBoxValuesAreUnknown() {
        val values = mapOf(
            SpdCarInfoKeys.SPEED to 52,
            SpdCarInfoKeys.ENGINE_RPM to 0,
            SpdCarInfoKeys.OUTSIDE_TEMPERATURE to -3,
            SpdCarInfoKeys.STATE_OF_CHARGE to 0,
            SpdCarInfoKeys.RANGE to 310,
            SpdCarInfoKeys.GEAR to 2,
        )
        val reading = SpdCarInfoKeys.reading { values[it] }
        assertEquals(52.0, reading.speedKmh!!, 0.0)
        assertNull(reading.engineRpm)
        assertEquals(-3.0, reading.outsideTemperatureC!!, 0.0)
        assertNull(reading.stateOfChargePercent)
        assertEquals(310, reading.rangeKm)
        assertEquals(2, reading.raw["gear"])
        assertFalse("unread keys are not reported", "fuel" in reading.raw)

        val empty = SpdCarInfoKeys.reading { null }
        assertTrue(empty.raw.isEmpty())
        assertNull(empty.speedKmh)
        assertNull(SpdCarInfoKeys.reading { if (it == SpdCarInfoKeys.SPEED) 900 else null }.speedKmh)
    }
}

class AccWakePolicyTest {
    @Test fun theFirstAccValueIsNoWake() {
        val policy = AccWakePolicy().apply { initial(acc = null, reversing = null) }
        assertFalse(policy.onAcc(on = true, autoStart = true))
    }

    @Test fun aWakeFromSleepOpensCarPlayWhenTheDriverAskedForIt() {
        val policy = AccWakePolicy().apply { initial(acc = true, reversing = false) }
        assertFalse(policy.onAcc(on = false, autoStart = true))
        assertTrue(policy.onAcc(on = true, autoStart = true))
        // The same ACC value again is no new wake.
        assertFalse(policy.onAcc(on = true, autoStart = true))
    }

    @Test fun nothingOpensWithoutTheStartupOption() {
        val policy = AccWakePolicy().apply { initial(acc = false, reversing = false) }
        assertFalse(policy.onAcc(on = true, autoStart = false))
    }

    @Test fun aWakeDuringReverseWaitsForTheCameraToClose() {
        val policy = AccWakePolicy().apply { initial(acc = false, reversing = true) }
        assertFalse(policy.onAcc(on = true, autoStart = true))
        assertTrue(policy.onReverse(on = false))
        assertFalse("opens once", policy.onReverse(on = false))
    }

    @Test fun sleepingAgainCancelsAWaitingWake() {
        val policy = AccWakePolicy().apply { initial(acc = false, reversing = true) }
        assertFalse(policy.onAcc(on = true, autoStart = true))
        assertFalse(policy.onAcc(on = false, autoStart = true))
        assertFalse(policy.onReverse(on = false))
    }

    @Test fun reverseAloneNeverOpensCarPlay() {
        val policy = AccWakePolicy().apply { initial(acc = true, reversing = false) }
        assertFalse(policy.onReverse(on = true))
        assertFalse(policy.onReverse(on = false))
    }
}
