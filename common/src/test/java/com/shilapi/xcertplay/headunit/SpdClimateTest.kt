package com.shilapi.xcertplay.headunit

import android.os.Bundle
import com.shilapi.xcertplay.airplay.CarClimateCommand
import com.shilapi.xcertplay.airplay.CarClimateSwitch
import com.shilapi.xcertplay.airplay.CarClimateZone
import com.shilapi.xcertplay.airplay.CarTemperature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SpdClimateTest {
    /** A Raise Toyota-style panel: 15–32 °C in half degrees, 0 for LO. */
    private fun celsius(value: Float) = SpdTemperature(value, 0, min = 15f, max = 32f, step = 0.5f)

    private fun car(vararg ints: Pair<Pair<Int, Int>, Int>, driver: SpdTemperature = celsius(21f), passenger: SpdTemperature? = celsius(21f)) =
        SpdClimateKeys.snapshot({ key, zone -> ints.toMap()[key to zone] ?: 0 }, { key, zone ->
            when {
                key == SpdClimateKeys.TEMPERATURE && zone == SpdClimateKeys.ZONE_DRIVER -> driver
                key == SpdClimateKeys.TEMPERATURE && zone == SpdClimateKeys.ZONE_PASSENGER -> passenger
                else -> null
            }
        })!!

    /** A CAN-box profile that takes TEYES's widget convention: it acts on the touch up. */
    private class FakeCar(var state: SpdClimateSnapshot, private val answers: Boolean = true, private val backwards: Boolean = false) {
        val sent = mutableListOf<Triple<Int, Int, Int>>()

        fun setInt(what: Int, value: Int, arg: Int) {
            sent += Triple(what, value, arg)
            if (!answers || (value and SpdClimateKeys.FLAG_BUTTON_UP) == 0) return
            var up = (value and SpdClimateKeys.FLAG_INCREASE) != 0
            val down = (value and SpdClimateKeys.FLAG_DECREASE) != 0
            if (backwards && (up || down)) up = !up
            val s = state
            state = when (what) {
                SpdClimateKeys.AC -> s.copy(ac = if ((s.ac ?: 0) > 0) 0 else 1)
                SpdClimateKeys.RECIRCULATION -> s.copy(recirculation = if (s.recirculation == 1) 0 else 1)
                SpdClimateKeys.TEMPERATURE -> {
                    val t = if (arg == SpdClimateKeys.ZONE_DRIVER) s.driver else s.passenger!!
                    val next = when {
                        up -> if (t.value == SpdClimateKeys.TEMPERATURE_LO) t.min else minOf(t.value + t.step, t.max)
                        else -> maxOf(t.value - t.step, t.min)
                    }
                    if (arg == SpdClimateKeys.ZONE_DRIVER) s.copy(driver = t.copy(value = next)) else s.copy(passenger = t.copy(value = next))
                }
                SpdClimateKeys.WIND_LEVEL -> s.copy(fan = (s.fan ?: 0) + if (up) 1 else -1)
                // Off, then the highest level, then down: 0 → 3 → 2 → 1 → 0.
                SpdClimateKeys.SEAT_HEATED -> s.copy(driverSeat = ((s.driverSeat ?: 0) + 3) % 4)
                else -> s
            }
        }

        fun control() = SpdClimateControl(read = { state }, setInt = ::setInt, sleep = {})
    }

    @Test fun aProfileWithoutClimateReportsNone() {
        // TEYES answers 0 and an empty bundle for keys a profile does not model, and a zero range until the car speaks.
        assertNull(SpdClimateKeys.snapshot({ _, _ -> 0 }, { _, _ -> null }))
        assertNull(SpdClimateKeys.snapshot({ _, _ -> 0 }, { _, _ -> SpdTemperature(0f, 0) }))
    }

    @Test fun theCarsClimateBecomesAReading() {
        val reading = SpdClimateKeys.reading(SpdClimateKeys.snapshot({ key, zone ->
            mapOf(
                (SpdClimateKeys.POWER to 0) to 1, (SpdClimateKeys.AC to 0) to 2, (SpdClimateKeys.WIND_LEVEL to 0) to 4,
                (SpdClimateKeys.RECIRCULATION to 0) to 2, (SpdClimateKeys.SEAT_HEATED to 1) to 3,
            )[key to zone] ?: 0
        }, { key, zone ->
            when (key to zone) {
                SpdClimateKeys.TEMPERATURE to 0 -> celsius(22f)
                SpdClimateKeys.TEMPERATURE to 1 -> celsius(SpdClimateKeys.TEMPERATURE_LO)
                SpdClimateKeys.INNER_TEMPERATURE to 0 -> SpdTemperature(77f, SpdClimateKeys.UNIT_FAHRENHEIT)
                else -> null
            }
        })!!)
        assertEquals(true, reading.hvacOn)
        assertEquals(true, reading.compressorOn) // 2 is automatic A/C, still on
        assertEquals(false, reading.recirculationOn) // 2 is automatic air intake, not recirculation
        assertEquals(CarTemperature.Celsius(22.0), reading.driverTarget)
        assertEquals(CarTemperature.Low, reading.passengerTarget)
        assertEquals(25.0, reading.cabinCelsius!!, 0.01)
        assertEquals(4, reading.fanLevel)
        assertEquals(3, reading.passengerSeatHeating)
    }

    @Test fun temperatureBundlesAreReadAsTeyesFillsThem() {
        val bundle = Bundle().apply {
            putFloat("value", 72f); putInt("unit", 1); putFloat("min", 59f); putFloat("max", 93f); putFloat("step", 1f)
        }
        assertEquals(SpdTemperature(72f, 1, 59f, 93f, 1f), SpdTemperature.from(bundle))
        assertNull(SpdTemperature.from(Bundle()))
        assertEquals(CarTemperature.High, SpdTemperature(254f, 0).target())
        assertNull(SpdTemperature(255f, 0).target())
    }

    @Test fun aSwitchIsOneTouchDownAndUp() {
        val car = FakeCar(car())
        assertNull(car.control().apply(CarClimateCommand.Switch(CarClimateSwitch.AC, true)))
        assertEquals(listOf(
            Triple(SpdClimateKeys.AC, SpdClimateKeys.FLAG_BUTTON_DOWN, 0),
            Triple(SpdClimateKeys.AC, SpdClimateKeys.FLAG_BUTTON_UP, 0),
        ), car.sent)
        assertEquals(1, car.state.ac)

        car.sent.clear()
        assertNull(car.control().apply(CarClimateCommand.Switch(CarClimateSwitch.AC, true)))
        assertTrue("already on: nothing is pressed", car.sent.isEmpty())
    }

    @Test fun recirculationCountsOnlyInsideAirAsOn() {
        val car = FakeCar(car((SpdClimateKeys.RECIRCULATION to 0) to 2))
        assertNull(car.control().apply(CarClimateCommand.Switch(CarClimateSwitch.RECIRCULATION, true)))
        assertEquals(1, car.state.recirculation)
    }

    @Test fun aTargetTemperatureIsReachedInPanelSteps() {
        val car = FakeCar(car())
        assertNull(car.control().apply(CarClimateCommand.TargetTemperature(CarClimateZone.PASSENGER, 22.5)))
        val up = SpdClimateKeys.FLAG_INCREASE
        assertEquals(List(3) { listOf(
            Triple(SpdClimateKeys.TEMPERATURE, SpdClimateKeys.FLAG_BUTTON_DOWN or up, 1),
            Triple(SpdClimateKeys.TEMPERATURE, SpdClimateKeys.FLAG_BUTTON_UP or up, 1),
        ) }.flatten(), car.sent)
        assertEquals(22.5f, car.state.passenger!!.value)
    }

    @Test fun lowCountsAsOneStepBelowThePanel() {
        val car = FakeCar(car(driver = celsius(SpdClimateKeys.TEMPERATURE_LO)))
        assertNull(car.control().apply(CarClimateCommand.TargetTemperature(CarClimateZone.DRIVER, 16.0)))
        assertEquals(16f, car.state.driver.value)
        assertEquals(6, car.sent.size) // LO → 15 → 15.5 → 16
    }

    @Test fun aFahrenheitPanelGetsTheTargetInItsOwnSteps() {
        val panel = SpdTemperature(70f, SpdClimateKeys.UNIT_FAHRENHEIT, min = 59f, max = 93f, step = 1f)
        assertEquals(72.0, SpdClimatePresses.carTarget(22.0, panel), 0.0)
        val car = FakeCar(car(driver = panel))
        assertNull(car.control().apply(CarClimateCommand.TargetTemperature(CarClimateZone.DRIVER, 22.0)))
        assertEquals(72f, car.state.driver.value)
    }

    @Test fun theFanStepsToTheCarsLevelForCarPlaysScale() {
        val car = FakeCar(car((SpdClimateKeys.WIND_LEVEL to 0) to 6, (SpdClimateKeys.WIND_LEVEL_MAX to 0) to 8))
        assertNull(car.control().apply(CarClimateCommand.FanLevel(3)))
        assertEquals(3, car.state.fan) // 3 of 7 is 3 of 8
        assertEquals(SpdClimateKeys.FLAG_BUTTON_UP or SpdClimateKeys.FLAG_DECREASE, car.sent.last().second)
    }

    @Test fun seatHeatingCyclesUntilTheLevelShows() {
        val car = FakeCar(car())
        assertNull(car.control().apply(CarClimateCommand.SeatHeating(CarClimateZone.DRIVER, 1)))
        assertEquals(1, car.state.driverSeat) // 0 → 3 → 2 → 1
        assertEquals(6, car.sent.size)
    }

    @Test fun aCarThatDoesNotAnswerStopsAfterOnePress() {
        val car = FakeCar(car(), answers = false)
        val reason = car.control().apply(CarClimateCommand.TargetTemperature(CarClimateZone.DRIVER, 25.0))
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("did not answer"))
        assertEquals("one touch down and its touch up", 2, car.sent.size)
    }

    @Test fun aCarThatMovesTheOtherWayStops() {
        val car = FakeCar(car(), backwards = true)
        val reason = car.control().apply(CarClimateCommand.TargetTemperature(CarClimateZone.DRIVER, 25.0))
        assertTrue(reason!!, reason.contains("moved away"))
        assertEquals(2, car.sent.size)
    }

    @Test fun aSwitchTheProfileDoesNotReportIsNotPressed() {
        val car = FakeCar(car().copy(acMax = null))
        assertNotNull(car.control().apply(CarClimateCommand.Switch(CarClimateSwitch.MAX_AC, true)))
        assertTrue(car.sent.isEmpty())
    }
}
