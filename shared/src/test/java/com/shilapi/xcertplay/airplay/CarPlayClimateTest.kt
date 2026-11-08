package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayClimateTest {
    @Test fun aReadingBecomesTheCharacteristicsCarPlayDescribes() {
        val values = CarPlayClimate.characteristics(CarClimateReading(
            hvacOn = true, compressorOn = false, recirculationOn = true, autoClimateOn = true,
            driverTarget = CarTemperature.Celsius(21.3), passengerTarget = CarTemperature.High,
            cabinCelsius = 24.26, fanLevel = 9, driverSeatHeating = 3,
        ))
        assertEquals(true, values["hvacOn"])
        assertEquals(true, values["driverTemperatureOn"])
        assertEquals(false, values["compressorOn"])
        assertEquals(true, values["fanAutoMode"])
        assertEquals(21.5, values["driverTargetTemperature"])
        assertEquals(28.0, values["passengerTargetTemperature"])
        assertEquals(24.5, values["driverCurrentTemperature"])
        assertEquals(7L, values["fanLevel"])
        assertEquals(true, values["fanOn"])
        assertEquals(2L, values["driverSeatHeatingLevel"])
        // Unknown fields leave CarPlay's own value alone.
        assertNull(values["maxACOn"])
        assertNull(values["passengerSeatHeatingLevel"])
    }

    @Test fun lowAndOutOfRangeTargetsShowAsCarPlaysEnds() {
        assertEquals(16.0, CarPlayClimate.target(CarTemperature.Low), 0.0)
        assertEquals(16.0, CarPlayClimate.target(CarTemperature.Celsius(15.0)), 0.0)
        assertEquals(28.0, CarPlayClimate.target(CarTemperature.Celsius(31.5)), 0.0)
    }

    @Test fun aCarsFanRangeIsScaledAndItsLowestLevelIsNeverOff() {
        assertEquals(0, CarPlayClimate.fanLevel(0, 8))
        assertEquals(1, CarPlayClimate.fanLevel(1, 12))
        assertEquals(7, CarPlayClimate.fanLevel(8, 8))
        assertEquals(4, CarPlayClimate.fanLevel(2, 4))
        assertNull(CarPlayClimate.fanLevel(9, 8))
        assertNull(CarPlayClimate.fanLevel(-1, 7))
    }

    @Test fun onlyAllowListedWritesInRangeBecomeCommands() {
        assertEquals(CarClimateCommand.Switch(CarClimateSwitch.AC, true), CarPlayClimate.command("compressorOn", true))
        assertEquals(CarClimateCommand.TargetTemperature(CarClimateZone.PASSENGER, 22.5),
            CarPlayClimate.command("passengerTargetTemperature", 22.5))
        assertEquals(CarClimateCommand.FanLevel(3), CarPlayClimate.command("fanLevel", 3L))
        assertEquals(CarClimateCommand.SeatHeating(CarClimateZone.DRIVER, 2), CarPlayClimate.command("driverSeatHeatingLevel", 2L))

        // Out of range, off the steps, the wrong type, or not on the list.
        assertNull(CarPlayClimate.command("driverTargetTemperature", 30.0))
        assertNull(CarPlayClimate.command("driverTargetTemperature", 21.2))
        assertNull(CarPlayClimate.command("fanLevel", 8L))
        assertNull(CarPlayClimate.command("fanLevel", 2.5))
        assertNull(CarPlayClimate.command("driverSeatHeatingLevel", 3L))
        assertNull(CarPlayClimate.command("compressorOn", 1L))
        assertNull(CarPlayClimate.command("hvacOn", false))
        assertNull(CarPlayClimate.command("autoClimateOn", true))
        assertNull(CarPlayClimate.command("interiorStyle", "5Seater"))
    }
}
