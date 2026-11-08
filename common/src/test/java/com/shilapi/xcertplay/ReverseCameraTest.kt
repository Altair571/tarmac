package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.ReverseCameraPolicy.Action as action
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.vehicle.VehicleAdapterSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class ReverseCameraTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @Before fun reset() = clear()

    @After fun tearDown() {
        activity?.finish()
        ReverseCameraSwitch.stop()
        clear()
    }

    private fun clear() {
        for (name in listOf("diplay_reverse_camera", "diplay_vehicle_adapter", "diplay", "xcertplay_airplay")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun reverseTakesTheScreenAtOnceAndGivesItBackAfterAShortWait() {
        val policy = ReverseCameraPolicy(leaveDelayMillis = 1_500, unknownGraceMillis = 5_000)
        assertEquals(action.NONE, policy.onReading(false, 0))
        assertEquals(action.ENTER, policy.onReading(true, 100))
        assertEquals(action.NONE, policy.onReading(true, 400))
        assertEquals(action.NONE, policy.onReading(false, 700))
        assertEquals(action.NONE, policy.onReading(false, 2_100))
        assertEquals(action.LEAVE, policy.onReading(false, 2_200))
        assertFalse(policy.reversing)
    }

    @Test fun aQuickShiftBackIntoReverseDoesNotFlicker() {
        val policy = ReverseCameraPolicy(leaveDelayMillis = 1_500, unknownGraceMillis = 5_000)
        policy.onReading(true, 0)
        assertEquals(action.NONE, policy.onReading(false, 300))
        assertEquals(action.NONE, policy.onReading(true, 900))
        assertEquals(action.NONE, policy.onReading(false, 1_200))
        assertEquals(action.NONE, policy.onReading(false, 2_600))
        assertEquals(action.LEAVE, policy.onReading(false, 2_700))
    }

    @Test fun aLostGearSourceNeverKeepsCarPlayAway() {
        val policy = ReverseCameraPolicy(leaveDelayMillis = 1_500, unknownGraceMillis = 5_000)
        policy.onReading(true, 0)
        assertEquals(action.NONE, policy.onReading(null, 300))
        assertEquals(action.NONE, policy.onReading(null, 5_200))
        assertEquals(action.LEAVE, policy.onReading(null, 5_300))
        // Unknown before reverse is not reverse.
        assertEquals(action.NONE, policy.onReading(null, 6_000))
    }

    @Test fun theSwitchIsOffAndHasNoSourceOnAPlainHeadUnit() {
        assertFalse(ReverseCameraSettings.enabled(app))
        assertEquals(ReverseCameraSettings.Source.AUTOMATIC, ReverseCameraSettings.source(app))
        assertNull(ReverseGearSources.resolve(app))
        assertNull(ReverseGearSources.reversing(app, null))
    }

    @Test fun automaticUsesAnAdapterWithAGearSignalOrTheConfiguredBroadcast() {
        ReverseCameraSettings.setBroadcast(app, ReverseCameraSettings.Broadcast("com.example.REVERSE", "state", "1"))
        assertEquals(ReverseCameraSettings.Source.BROADCAST, ReverseGearSources.resolve(app))
        VehicleAdapterSettings.setKind(app, VehicleAdapterSettings.Kind.CAN_USB)
        assertEquals("no gear in the map yet", ReverseCameraSettings.Source.BROADCAST, ReverseGearSources.resolve(app))
        VehicleAdapterSettings.setSignalMapText(app, """{ "can": [ { "signal": "gear", "id": "0x10", "start": 0,
            "length": 2, "values": { "0": "P", "1": "R" } } ] }""")
        assertEquals(ReverseCameraSettings.Source.ADAPTER, ReverseGearSources.resolve(app))
        assertEquals(ReverseCameraSettings.Source.BROADCAST,
            ReverseGearSources.resolve(app, ReverseCameraSettings.Source.BROADCAST))
        VehicleAdapterSettings.setKind(app, VehicleAdapterSettings.Kind.OFF)
    }

    @Test fun aBroadcastMatchesItsReverseValueAsText() {
        val broadcast = ReverseCameraSettings.Broadcast("com.example.REVERSE", "state", "on")
        assertTrue(ReverseBroadcast.matches(Intent(broadcast.action).putExtra("state", "ON"), broadcast))
        assertFalse(ReverseBroadcast.matches(Intent(broadcast.action).putExtra("state", "off"), broadcast))
        assertFalse(ReverseBroadcast.matches(Intent(broadcast.action), broadcast))
        val numeric = broadcast.copy(reverseValue = "1")
        assertTrue(ReverseBroadcast.matches(Intent(broadcast.action).putExtra("state", 1), numeric))
        assertTrue(ReverseBroadcast.matches(Intent(broadcast.action).putExtra("state", true), broadcast.copy(reverseValue = "true")))
    }

    @Test fun cameraAppsAreRecognizedForTheTopOfThePicker() {
        assertTrue(ReverseCameraApps.likelyCamera("AVM", "com.vendor.avm"))
        assertTrue(ReverseCameraApps.likelyCamera("倒车影像", "com.vendor.x"))
        assertTrue(ReverseCameraApps.likelyCamera("Rear Camera", "com.vendor.x"))
        assertFalse(ReverseCameraApps.likelyCamera("Music", "com.vendor.music"))
    }

    @Test fun vehicleSettingsShowOnlyTheSwitchUntilItIsOn() {
        var screen = openVehicle()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_reverse_camera) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_reverse_camera_switch) })
        assertFalse(texts(screen).any { it.text.startsWith(screen.getString(R.string.settings_reverse_camera_source)) })
        screen.finish()
        ReverseCameraSettings.setEnabled(app, true)
        screen = openVehicle()
        assertTrue(texts(screen).any { it.text.startsWith("${screen.getString(R.string.settings_reverse_camera_source)} · ") })
        assertTrue(texts(screen).any { it.text.startsWith("${screen.getString(R.string.settings_reverse_camera_app)} · ") })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_reverse_camera_source_none) })
    }

    private fun openVehicle(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { screen ->
        activity = screen
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.VEHICLE))
    }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
