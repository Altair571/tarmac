package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import com.shilapi.xcertplay.glance.CarPlayGlance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarGuidanceBroadcastTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sent = mutableListOf<Intent>()
    private var snapshot = CarPlayGlance.Snapshot()
    private val leftIn300 = CarPlayGlance.Snapshot(connected = true, maneuverType = 1, distanceMeters = 300,
        road = "Main Street", remainingMeters = 5_000, remainingSeconds = 600)

    @Before fun setUp() {
        app.getSharedPreferences("diplay_car_guidance", Context.MODE_PRIVATE).edit().clear().commit()
        CarGuidanceBroadcast.glance = { snapshot }
        CarGuidanceBroadcast.send = { _, intent -> sent.add(intent) }
        CarGuidanceBroadcast.start(app)
    }

    @After fun tearDown() {
        CarGuidanceBroadcast.stop()
        CarGuidanceBroadcast.glance = CarPlayGlance::snapshot
    }

    @Test fun nothingIsSentUntilTheDriverTurnsItOn() {
        assertFalse(CarGuidanceSettings.enabled(app))
        snapshot = leftIn300
        repeat(6) { CarGuidanceBroadcast.tick() }
        assertTrue(sent.isEmpty())
    }

    @Test fun aTurnGoesOutAsTheStandardAmapBroadcastWithoutBydFlags() {
        CarGuidanceSettings.setEnabled(app, true)
        snapshot = leftIn300
        CarGuidanceBroadcast.tick()
        val intent = sent.single()
        assertEquals(CarGuidanceBroadcast.ACTION, intent.action)
        assertNull("any head unit's receiver may take it", intent.`package`)
        assertEquals(CarGuidanceBroadcast.KEY_GUIDANCE, intent.getIntExtra("KEY_TYPE", -1))
        assertEquals(2, intent.getIntExtra("NEW_ICON", -1)) // left
        assertEquals(300, intent.getIntExtra("SEG_REMAIN_DIS", -1))
        assertEquals("Main Street", intent.getStringExtra("NEXT_ROAD_NAME"))
        assertEquals(5_000, intent.getIntExtra("ROUTE_REMAIN_DIS", -1))
        assertEquals(600, intent.getIntExtra("ROUTE_REMAIN_TIME", -1))
        assertFalse(intent.hasExtra("IS_BYD_MAP"))
        assertTrue(intent.flags and 0x01000000 != 0)
    }

    @Test fun theSameTurnIsRepeatedAsAKeepaliveAndAChangeGoesOutAtOnce() {
        CarGuidanceSettings.setEnabled(app, true)
        snapshot = leftIn300
        repeat(5) { CarGuidanceBroadcast.tick() }
        assertEquals(1, sent.size)
        CarGuidanceBroadcast.tick()
        assertEquals("the keepalive, five seconds after the last send", 2, sent.size)
        snapshot = leftIn300.copy(distanceMeters = 200)
        CarGuidanceBroadcast.tick()
        assertEquals(3, sent.size)
        assertEquals(200, sent.last().getIntExtra("SEG_REMAIN_DIS", -1))
    }

    @Test fun theEndOfTheRouteOrTheSwitchTurnedOffEndsGuidanceOnTheCar() {
        CarGuidanceSettings.setEnabled(app, true)
        snapshot = leftIn300
        CarGuidanceBroadcast.tick()
        snapshot = leftIn300.copy(maneuverType = null)
        CarGuidanceBroadcast.tick()
        assertEquals(CarGuidanceBroadcast.KEY_STATE, sent.last().getIntExtra("KEY_TYPE", -1))
        assertEquals(CarGuidanceBroadcast.STATE_ENDED, sent.last().getIntExtra("EXTRA_STATE", -1))
        CarGuidanceBroadcast.tick()
        assertEquals("ended once", 2, sent.size)
        snapshot = leftIn300
        CarGuidanceBroadcast.tick()
        CarGuidanceSettings.setEnabled(app, false)
        CarGuidanceBroadcast.tick()
        assertEquals(CarGuidanceBroadcast.STATE_ENDED, sent.last().getIntExtra("EXTRA_STATE", -1))
    }

    @Test fun aSessionThatEndsMidRouteEndsGuidanceOnTheCar() {
        CarGuidanceSettings.setEnabled(app, true)
        snapshot = leftIn300
        CarGuidanceBroadcast.tick()
        CarGuidanceBroadcast.stop()
        assertEquals(CarGuidanceBroadcast.KEY_STATE, sent.last().getIntExtra("KEY_TYPE", -1))
    }

    @Test fun noRouteOrNoSessionIsNoFrame() {
        assertNull(CarGuidanceBroadcast.frame(CarPlayGlance.Snapshot(connected = true)))
        assertNull(CarGuidanceBroadcast.frame(leftIn300.copy(connected = false)))
        assertEquals(2, CarGuidanceBroadcast.frame(leftIn300)?.icon)
    }

    @Test fun bydKeepsItsOwnAdapterPath() {
        assertTrue(CarGuidanceSettings.available(app))
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = BydAmapAdapter.BYD.packageName })
        assertFalse(CarGuidanceSettings.available(app))
        CarGuidanceSettings.setEnabled(app, true)
        snapshot = leftIn300
        CarGuidanceBroadcast.tick()
        assertTrue(sent.isEmpty())
    }
}
