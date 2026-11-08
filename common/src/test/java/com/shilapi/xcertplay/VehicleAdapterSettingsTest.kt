package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.transport.EvChargingConnectors
import com.shilapi.xcertplay.vehicle.VehicleAdapter
import com.shilapi.xcertplay.vehicle.VehicleAdapterSettings
import com.shilapi.xcertplay.vehicle.VehicleStatusSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class VehicleAdapterSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @Before fun reset() = clear()

    @After fun tearDown() {
        activity?.finish()
        VehicleAdapter.stop()
        clear()
    }

    private fun clear() {
        for (name in listOf("diplay_vehicle_adapter", "diplay", "xcertplay_airplay")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun theAdapterIsOffAndTellsTheIphoneNothingByDefault() {
        assertEquals(VehicleAdapterSettings.Kind.OFF, VehicleAdapterSettings.kind(app))
        assertFalse(VehicleStatusSources.active(app))
        assertNull(VehicleStatusSources.provider(app))
        assertNull(VehicleStatusSources.source(app))
    }

    @Test fun anAdapterWithEvDataOnFeedsTheIphoneWithItsOwnConnectors() {
        VehicleAdapterSettings.setKind(app, VehicleAdapterSettings.Kind.OBD_BLUETOOTH)
        assertFalse("reading alone does not declare an EV", VehicleStatusSources.active(app))
        VehicleAdapterSettings.setEvToIphone(app, true)
        VehicleAdapterSettings.setChargingConnectors(app, EvChargingConnectors.CCS1_J1772)
        assertTrue(VehicleStatusSources.active(app))
        assertNotNull(VehicleStatusSources.provider(app))
        assertEquals(EvChargingConnectors.CCS1_J1772, VehicleStatusSources.chargingConnectors(app))
        // No reading yet and none before: the iPhone is not told about an EV.
        assertFalse(VehicleStatusSources.provider(app)!!.declarable())
    }

    @Test fun anInvalidSignalMapIsNotSaved() {
        try {
            VehicleAdapterSettings.setSignalMapText(app, """{ "obd": [ { "signal": "soc", "request": "04", "byte": 0 } ] }""")
        } catch (_: IllegalArgumentException) {}
        assertNull(VehicleAdapterSettings.signalMapText(app))
        VehicleAdapterSettings.setSignalMapText(app, """{ "name": "Mine", "obd": [ { "signal": "soc", "request": "015B", "byte": 0 } ] }""")
        assertEquals("Mine", VehicleAdapterSettings.signalMap(app).name)
    }

    @Test fun advancedShowsTheAdapterCardWithOnlyTheChoiceWhileOff() {
        val screen = openAdvanced()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_vehicle_adapter) })
        assertTrue(texts(screen).any { it.text.startsWith("${screen.getString(R.string.settings_vehicle_adapter_kind)} · ") })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_vehicle_adapter_ev) })
    }

    @Test fun aBluetoothAdapterOffersItsPairedDeviceMapAndEvData() {
        VehicleAdapterSettings.setKind(app, VehicleAdapterSettings.Kind.OBD_BLUETOOTH)
        val screen = openAdvanced()
        assertTrue(texts(screen).any { it.text.startsWith("${screen.getString(R.string.settings_vehicle_adapter_bluetooth)} · ") })
        assertTrue(texts(screen).any { it.text.startsWith("${screen.getString(R.string.settings_vehicle_adapter_map)} · ") })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_vehicle_adapter_ev) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_vehicle_adapter_ev_description) })
        // BYD's own battery switch stays in the BYD card.
        assertFalse(BydOutputSettings.batteryToIphone(app))
    }

    private fun openAdvanced(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { screen ->
        activity = screen
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.ADVANCED))
    }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
