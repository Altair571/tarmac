package com.shilapi.xcertplay.headunit

import com.shilapi.xcertplay.airplay.CarClimateCommand
import com.shilapi.xcertplay.airplay.CarClimateSwitch
import com.shilapi.xcertplay.airplay.CarClimateZone
import com.shilapi.xcertplay.airplay.CarTemperature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FytClimateTest {
    private val c = FytClimateCodes

    /** A protocol with shared climate codes (VW on Raise, protocol 1) and a target in tenths of a degree. */
    private fun codes(vararg extra: Pair<Int, Int>) =
        mapOf(c.U_CANBUS_ID to 1, c.U_EXIST_AIR to 1, c.TEMP_LEFT to 215, c.TEMP_RIGHT to 220) + extra

    /** A protocol that answers voice commands as `k0.on` does: absolute switches and one step a command. */
    private class FakeCar(var codes: Map<Int, Int>, val voice: Set<Int>, private val step: Int = 5, private val answers: Boolean = true) {
        val sent = mutableListOf<List<Int>>()

        fun send(ints: IntArray) {
            sent += ints.toList()
            if (!answers) return
            val (va, value) = ints[0] to ints[1]
            codes = codes + when (va) {
                FytClimateCodes.VA_AC -> mapOf(FytClimateCodes.AC to value)
                FytClimateCodes.VA_CYCLE -> mapOf(FytClimateCodes.CYCLE to value)
                FytClimateCodes.VA_FAN -> mapOf(FytClimateCodes.WIND_LEVEL to codes.getValue(FytClimateCodes.WIND_LEVEL) + if (value == FytClimateCodes.VA_FAN_UP) 1 else -1)
                FytClimateCodes.VA_TEMP_LEFT -> mapOf(FytClimateCodes.TEMP_LEFT to codes.getValue(FytClimateCodes.TEMP_LEFT) + if (value == 1) step else -step)
                else -> emptyMap()
            }
        }

        fun control() = FytClimateControl(read = { FytClimateSnapshot(codes, voice) }, send = ::send, sleep = {})
    }

    @Test fun climateIsReadOnlyWhereTheProtocolHasSharedClimate() {
        assertNotNull(c.reading(codes()))
        assertNull("the protocol says it has no climate", c.reading(codes(c.U_EXIST_AIR to 0)))
        assertNull("no protocol reported yet", c.reading(codes() - c.U_CANBUS_ID))
        assertNull("KSW Audi Q5 puts A/C where the shared table has the temperature", c.reading(codes(c.U_CANBUS_ID to 442)))
        assertNull("SGMW variant 1 moves the whole range", c.reading(codes(c.U_CANBUS_ID to (1 shl 16 or 443))))
        assertNotNull("another SGMW variant keeps it", c.reading(codes(c.U_CANBUS_ID to (2 shl 16 or 443))))
    }

    @Test fun onlyTemperaturesOfACertainScaleAreShown() {
        assertEquals(CarTemperature.Celsius(21.5), c.temperature(215))
        assertEquals(CarTemperature.High, c.temperature(-3))
        assertEquals(CarTemperature.Low, c.temperature(-2))
        assertNull(c.temperature(-1))
        assertNull("half degrees (MQB) look like 21.5 °C × 2", c.temperature(43))
        assertNull("MQB's HI marker is not 25.5 °C", c.temperature(255))
        assertNull("a value replayed before the car speaks", c.temperature(0))
        assertNull("no decodable driver target, no climate", c.reading(codes(c.TEMP_LEFT to 43)))
    }

    @Test fun theSharedCodesBecomeAReading() {
        val reading = c.reading(codes(c.POWER to 1, c.AC to 0, c.CYCLE to 3, c.WIND_LEVEL to 9, c.SEAT_HOT_LEFT to 3, c.TEMP_RIGHT to -3))!!
        assertEquals(true, reading.hvacOn)
        assertEquals(false, reading.compressorOn)
        assertEquals(true, reading.recirculationOn)
        assertEquals(7, reading.fanLevel)
        assertEquals(3, reading.driverSeatHeating)
        assertEquals(CarTemperature.Celsius(21.5), reading.driverTarget)
        assertEquals(CarTemperature.High, reading.passengerTarget)
    }

    @Test fun switchesAreSentAsAbsoluteVoiceCommands() {
        val car = FakeCar(codes(c.AC to 0, c.CYCLE to 0), voice = setOf(c.VA_AC, c.VA_CYCLE))
        assertNull(car.control().apply(CarClimateCommand.Switch(CarClimateSwitch.AC, true)))
        assertEquals(listOf(listOf(c.VA_AC, 1)), car.sent)
        assertNull(car.control().apply(CarClimateCommand.Switch(CarClimateSwitch.RECIRCULATION, false)))
        assertEquals("already outside air: nothing sent", 1, car.sent.size)
    }

    @Test fun onlyVoiceCommandsTheProtocolListsAreSent() {
        val car = FakeCar(codes(c.AC to 0), voice = setOf(c.VA_CYCLE))
        assertNotNull(car.control().apply(CarClimateCommand.Switch(CarClimateSwitch.AC, true)))
        assertNotNull(car.control().apply(CarClimateCommand.Switch(CarClimateSwitch.MAX_AC, true)))
        assertNotNull(car.control().apply(CarClimateCommand.SeatHeating(CarClimateZone.DRIVER, 1)))
        assertTrue(car.sent.isEmpty())
    }

    @Test fun temperatureStepsUntilTheTargetAndLearnsThePanelStep() {
        val half = FakeCar(codes(), voice = setOf(c.VA_TEMP_LEFT))
        assertNull(half.control().apply(CarClimateCommand.TargetTemperature(CarClimateZone.DRIVER, 23.0)))
        assertEquals(230, half.codes[c.TEMP_LEFT])
        assertEquals(List(3) { listOf(c.VA_TEMP_LEFT, c.VA_TEMP_UP) }, half.sent)

        // Whole-degree steps cannot reach 22.5: the nearest step is close enough.
        val whole = FakeCar(codes(c.TEMP_LEFT to 210), voice = setOf(c.VA_TEMP_LEFT), step = 10)
        assertNull(whole.control().apply(CarClimateCommand.TargetTemperature(CarClimateZone.DRIVER, 22.5)))
        assertEquals(1, whole.sent.size)
    }

    @Test fun theFanStepsAndACarThatDoesNotAnswerStops() {
        val car = FakeCar(codes(c.WIND_LEVEL to 2), voice = setOf(c.VA_FAN))
        assertNull(car.control().apply(CarClimateCommand.FanLevel(4)))
        assertEquals(4, car.codes[c.WIND_LEVEL])

        val silent = FakeCar(codes(c.WIND_LEVEL to 2), voice = setOf(c.VA_FAN), answers = false)
        val reason = silent.control().apply(CarClimateCommand.FanLevel(4))
        assertTrue(reason!!, reason.contains("did not answer"))
        assertEquals(1, silent.sent.size)
    }
}
