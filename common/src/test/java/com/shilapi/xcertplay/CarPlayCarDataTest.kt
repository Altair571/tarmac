package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayVehicleState.CarData
import com.shilapi.xcertplay.airplay.CarPlayVehicleState.CarReading
import com.shilapi.xcertplay.headunit.HeadUnitVehicleReading
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydTyreReading
import com.shilapi.xcertplay.vehicle.VehicleReading
import com.shilapi.xcertplay.vehicle.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class CarPlayCarDataTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        context.applicationInfo.flags = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    @Test fun eachSourceGivesOnlyWhatItReads() {
        // BYD's battery switch declares an electric car: its charge is the fuel gauge, and it reports the range.
        assertEquals(setOf(CarData.FUEL_LEVEL, CarData.CHARGING, CarData.BATTERY_ENERGY, CarData.RANGE, CarData.TYRES),
            CarPlayCarData.available(CarPlayCarData.Setup(bydBattery = true)))
        // An adapter gives what its map names; its charge counts only when the iPhone is told the car is electric.
        assertEquals(setOf(CarData.TYRES), CarPlayCarData.available(CarPlayCarData.Setup(
            adapterSignals = setOf(VehicleSignal.STATE_OF_CHARGE, VehicleSignal.GEAR))))
        assertEquals(setOf(CarData.FUEL_LEVEL, CarData.TYRES), CarPlayCarData.available(CarPlayCarData.Setup(
            adapterSignals = setOf(VehicleSignal.STATE_OF_CHARGE), adapterEv = true)))
        assertEquals(setOf(CarData.FUEL_LEVEL, CarData.CHARGING, CarData.RANGE, CarData.ODOMETER, CarData.TYRES),
            CarPlayCarData.available(CarPlayCarData.Setup(adapterSignals = setOf(VehicleSignal.FUEL, VehicleSignal.CHARGING,
                VehicleSignal.RANGE, VehicleSignal.ODOMETER))))
        // A TEYES CAN box gives the range and the outside temperature; its charge stays out (electric or plug-in hybrid?).
        assertEquals(setOf(CarData.RANGE, CarData.OUTSIDE_TEMPERATURE, CarData.TYRES),
            CarPlayCarData.available(CarPlayCarData.Setup(headUnitVehicle = true)))
    }

    @Test fun theReadingTakesEachValueFromItsSource() {
        val tyres = BydTyreReading(listOf(230, 231, null, 229), listOf(20, 21, null, 20))
        assertEquals(CarReading(fuelPercent = 81.0, rangeKm = 402.0, tyrePressureKpa = listOf(230, 231, null, 229),
            charging = true, batteryEnergyKwh = 61.5, tyreTemperatureC = listOf(20, 21, null, 20)),
            CarPlayCarData.reading(VehicleReading(stateOfChargePercent = 81.0, rangeKm = 402, charging = true,
                batteryEnergyKwh = 61.5), true, null, tyres))
        // Not declared electric: the charge is not a fuel gauge. The head unit fills a range BYD or the adapter lacks.
        assertEquals(CarReading(rangeKm = 120.0, outsideTemperatureC = 7.0), CarPlayCarData.reading(VehicleReading(stateOfChargePercent = 81.0),
            false, HeadUnitVehicleReading(rangeKm = 120, stateOfChargePercent = 50.0, outsideTemperatureC = 7.0), null))
        // The tank wins over a hybrid's battery.
        assertEquals(CarReading(fuelPercent = 40.0, odometerKm = 55_000.0), CarPlayCarData.reading(
            VehicleReading(stateOfChargePercent = 81.0, fuelPercent = 40.0, odometerKm = 55_000.0), true, null, null))
        assertEquals(setOf(CarData.RANGE, CarData.TYRES),
            CarPlayCarData.given(CarReading(rangeKm = 1.0, tyrePressureKpa = listOf(null, 200, null, null))))
        assertEquals(emptySet<CarData>(), CarPlayCarData.given(CarReading(tyrePressureKpa = listOf(null, null, null, null))))
    }

    @Test fun aConnectionDescribesWhatThisHeadUnitHasGivenBefore() {
        assertEquals(emptySet<CarData>(), CarPlayCarData.forConnection(context)) // the experiment is off
        AirPlayPersistence.saveCarPlayUltra(context, true)
        assertEquals(emptySet<CarData>(), CarPlayCarData.forConnection(context)) // nothing read yet
        AirPlayPersistence.saveCarPlayUltraCarDataSeen(context, setOf("TYRES", "ODOMETER", "SOMETHING_OLD"))
        // No adapter is set up any more, so the odometer it once gave is not described.
        assertEquals(setOf(CarData.TYRES), CarPlayCarData.forConnection(context))
        AirPlayPersistence.saveCarPlayUltraCarData(context, false)
        assertEquals(emptySet<CarData>(), CarPlayCarData.forConnection(context))
    }

    @Test fun sampleValuesNeedADebuggableBuild() {
        AirPlayPersistence.saveCarPlayUltra(context, true)
        context.getSharedPreferences("xcertplay_airplay", 0).edit().putBoolean("carplay_ultra_test_car_data", true).commit()
        assertFalse(AirPlayPersistence.loadCarPlayUltraTestCarData(context))
        assertEquals(emptySet<CarData>(), CarPlayCarData.forConnection(context))
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        assertEquals(CarData.entries.toSet(), CarPlayCarData.forConnection(context))
    }

    @Test fun theVehicleAppGroupsTheLinesTheWayADriverReadsThem() {
        assertEquals(emptyList<Any>(), CarPlayCarData.settingLines(context, CarData.entries.toSet())) // the experiment is off
        AirPlayPersistence.saveCarPlayUltra(context, true)
        val lines = CarPlayCarData.settingLines(context, CarData.entries.toSet())
        assertEquals(listOf("TarmacCharge", "TarmacCharging", "TarmacEnergy", "TarmacRange", "TarmacTyre0", "TarmacTyre1",
            "TarmacTyre2", "TarmacTyre3", "TarmacOdometer", "TarmacOutside", "TarmacVersion", "TarmacSource"), lines.map { it.id })
        assertEquals(listOf("TarmacBattery", "TarmacTyres", "TarmacCar", "Tarmac"), lines.map { it.section.id }.distinct())
        assertEquals(listOf(text(R.string.carplay_vehicle_section_battery), text(R.string.carplay_vehicle_section_tyres),
            text(R.string.carplay_vehicle_section_car), "Tarmac"), lines.map { it.section.name }.distinct())
        assertEquals(listOf(0, 1, 2, 3), lines.mapNotNull { it.wheel })
        // Only described data gets a line; no reader is set up here, so there is no source to name either.
        assertEquals(listOf("TarmacRange", "TarmacVersion"), CarPlayCarData.settingLines(context, setOf(CarData.RANGE)).map { it.id })
        // Sample values say so.
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        context.getSharedPreferences("xcertplay_airplay", 0).edit().putBoolean("carplay_ultra_test_car_data", true).commit()
        assertEquals(text(R.string.carplay_vehicle_source_test),
            CarPlayCarData.settingLines(context, setOf(CarData.RANGE)).single { it.id == "TarmacSource" }.value)
    }

    @Test fun theSwitchSitsUnderTheExperimentInAdvanced() {
        assertFalse(text(R.string.settings_carplay_ultra_car_data) in advancedTexts())
        AirPlayPersistence.saveCarPlayUltra(context, true)
        AirPlayPersistence.saveCarPlayUltraCarDataSeen(context, setOf("TYRES"))
        val advanced = advancedTexts()
        assertTrue(text(R.string.settings_carplay_ultra_car_data) in advanced)
        assertTrue(context.getString(R.string.settings_carplay_ultra_car_data_next,
            text(R.string.settings_carplay_ultra_car_data_tyres)) in advanced)
        AirPlayPersistence.saveCarPlayUltraCarDataSeen(context, emptySet())
        assertTrue(text(R.string.settings_carplay_ultra_car_data_none) in advancedTexts())
    }

    private fun text(id: Int) = context.getString(id)

    private fun advancedTexts(): List<String> {
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup().get()
        descendants(screen.window.decorView).first {
            it.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(R.string.settings_advanced))
        }.performClick()
        return descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            .also { screen.finish() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
