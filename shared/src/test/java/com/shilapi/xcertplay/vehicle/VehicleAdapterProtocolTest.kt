package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.withVehicleStatusFrom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A serial link that answers each command line from a script and records what was sent. */
private class ScriptedLink(private val answer: (String) -> String?) : SerialLink {
    val sent = mutableListOf<String>()
    private val incoming = ArrayDeque<Byte>()
    private val line = StringBuilder()

    override fun write(bytes: ByteArray) {
        for (byte in bytes) {
            val char = byte.toInt().toChar()
            if (char == '\r') {
                val command = line.toString().also { line.clear() }
                sent += command
                answer(command)?.toByteArray(Charsets.US_ASCII)?.forEach(incoming::addLast)
            } else line.append(char)
        }
    }

    fun feed(text: String) = text.toByteArray(Charsets.US_ASCII).forEach(incoming::addLast)

    override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
        var count = 0
        while (count < buffer.size && incoming.isNotEmpty()) buffer[count++] = incoming.removeFirst()
        return count
    }

    override fun close() = Unit
}

/** A clock that moves on each read so timeouts end without real waiting. */
private class SteppingClock { var now = 0L; fun tick(): Long = now++ }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VehicleAdapterProtocolTest {
    @Test fun aSignalMapDescribesObdAndCanSignals() {
        val map = VehicleSignalMap.parse("""
            { "name": "Test EV", "bitrate": 500000,
              "obd": [ { "signal": "soc", "header": "7e4", "request": "220105", "byte": 32, "scale": 0.5 } ],
              "can": [ { "signal": "gear", "id": "0x111", "start": 8, "length": 4,
                         "values": { "0": "P", "1": "R", "2": "N", "3": "D" } },
                       { "signal": "range_km", "id": 1234, "start": 7, "length": 16, "endian": "big" } ] }
        """.trimIndent())
        assertEquals("Test EV", map.name)
        assertEquals("7E4", map.obd.single().header)
        assertEquals(0.5, map.obd.single().decoding.scale, 0.0)
        val gear = map.can[0]
        assertEquals(0x111, gear.id)
        assertFalse(gear.extended)
        assertEquals(VehicleGear.REVERSE, gear.decoding.gears[1L])
        assertTrue(map.can[1].bigEndian)
    }

    @Test fun fuelAndOdometerComeFromTheirStandardPids() {
        val map = VehicleSignalMap.parse("""
            { "obd": [ { "signal": "fuel_pct", "request": "012F", "byte": 0, "scale": 0.39215686 },
                       { "signal": "odometer_km", "request": "01A6", "byte": 0, "length": 4, "scale": 0.1 } ] }
        """.trimIndent())
        val (fuel, odometer) = map.obd
        val reading = odometer.decoding.apply(123_456L, fuel.decoding.apply(0x80L, VehicleReading()))
        assertEquals(50.2, reading.fuelPercent!!, 0.01)
        assertEquals(12_345.6, reading.odometerKm!!, 0.001)
        assertEquals(null, fuel.decoding.apply(300L, VehicleReading()).fuelPercent) // over 100 % is no reading
        assertEquals(reading, VehicleReading().updatedBy(reading))
    }

    @Test fun aMapThatWouldWriteToTheCarIsRefused() {
        // Write data, clear codes, session control, reset, routine control, I/O control, security access.
        for (request in listOf("2E010501", "04", "14FFFFFF", "1003", "1101", "31010203", "2F010203", "2701")) {
            try {
                VehicleSignalMap.parse("""{ "obd": [ { "signal": "soc", "request": "$request", "byte": 0 } ] }""")
                fail("$request must be refused")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!, expected.message!!.contains("not a read"))
            }
        }
        for (request in listOf("015B", "0902", "22B046")) assertTrue(request, Elm327Requests.allowed(request))
    }

    @Test fun mistakesInAMapAreExplained() {
        fun reason(json: String) = try { VehicleSignalMap.parse(json); null } catch (error: IllegalArgumentException) { error.message }
        assertTrue(reason("""{ "can": [ { "signal": "gear", "id": "0x10", "start": 0, "length": 2 } ] }""")!!.contains("values"))
        assertTrue(reason("""{ "can": [ { "signal": "gear", "id": "0x10", "start": 0, "length": 2, "values": { "1": "X" } } ] }""")!!.contains("P, R, N or D"))
        assertTrue(reason("""{ "bitrate": 123 }""")!!.contains("bitrate"))
        assertTrue(reason("""{ "can": [ { "signal": "fuel", "id": 1, "start": 0, "length": 8 } ] }""")!!.contains("signal must be"))
        assertTrue(reason("not json")!!.startsWith("not JSON"))
    }

    @Test fun canBitsFollowDbcNumbering() {
        val data = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        fun signal(start: Int, length: Int, big: Boolean, signed: Boolean = false) =
            CanSignal(SignalDecoding(VehicleSignal.RANGE), 1, false, start, length, big, signed)
        // Intel: the start bit is the least significant bit.
        assertEquals(0x3412L, CanBits.extract(data, signal(0, 16, big = false)))
        assertEquals(0x4L, CanBits.extract(data, signal(8, 4, big = false)))
        // Motorola: the start bit is the most significant bit, then down through the byte and on to the next.
        assertEquals(0x1234L, CanBits.extract(data, signal(7, 16, big = true)))
        assertEquals(0x23L, CanBits.extract(data, signal(3, 8, big = true)))
        assertEquals(-1L, CanBits.extract(byteArrayOf(0xFF.toByte()), signal(0, 8, big = false, signed = true)))
        assertNull(CanBits.extract(data, signal(24, 16, big = false)))
    }

    @Test fun elmAnswersAreReadAsDataAfterTheEcho() {
        assertArrayEquals(byteArrayOf(0x9C.toByte()), Elm327Response.data("SEARCHING...\r41 5B 9C\r\r", "015B"))
        assertArrayEquals(byteArrayOf(0x9C.toByte()), Elm327Response.data("415B9C\r415BA0\r", "015B"))
        assertNull(Elm327Response.data("NO DATA\r", "015B"))
        assertNull(Elm327Response.data("CAN ERROR\r", "015B"))
        assertNull(Elm327Response.data("410D20\r", "015B"))
        // ISO-TP: length line, then numbered frames.
        val multi = "00A\r0:620105AABBCC\r1:DDEEFF11\r"
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(), 0x11),
            Elm327Response.data(multi, "220105"))
        assertEquals(setOf(0x01, 0x0D, 0x20), Elm327Response.supportedPids(0x00, byteArrayOf(0x80.toByte(), 0x08, 0x00, 0x01)))
    }

    @Test fun theElmDriverSetsUpTheAdapterAndSwitchesHeadersOnlyWhenNeeded() {
        val clock = SteppingClock()
        val link = ScriptedLink { command ->
            when (command) {
                "ATZ" -> "\r\rELM327 v1.5\r\r>"
                "ATI" -> "ELM327 v1.5\r\r>"
                "0100" -> "SEARCHING...\r4100BE1FA813\r\r>"
                "220105" -> "00A\r0:620105AABBCC\r1:DDEEFF11\r\r>"
                else -> "OK\r\r>"
            }
        }
        val elm = Elm327(link, clock::tick)
        assertTrue(elm.initialize())
        assertEquals("ELM327 v1.5", elm.version)
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0", "ATI", "0100"), link.sent)
        link.sent.clear()
        assertEquals(0xAA.toByte(), elm.query("220105", "7E4")!![0])
        elm.query("220105", "7E4")
        elm.query("0100", null)
        assertEquals(listOf("ATSH7E4", "220105", "220105", "ATSH7DF", "0100"), link.sent)
    }

    @Test fun theElmDriverNeverSendsAnythingOutsideTheAllowList() {
        val elm = Elm327(ScriptedLink { "OK\r>" }, SteppingClock()::tick)
        for (command in listOf("04", "ATMA", "ATBI", "2E010501", "ATSH7E", "ATPP 0C SV 23")) {
            try {
                elm.command(command)
                fail("$command must be refused")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.startsWith("refused"))
            }
        }
    }

    @Test fun slcanOpensListenOnlyAndNeverNormalMode() {
        val clock = SteppingClock()
        val link = ScriptedLink { command -> if (command == "C") "\u0007" else "\r" }
        val slcan = Slcan(link, clock::tick)
        assertTrue(slcan.openListenOnly(500_000))
        assertEquals(listOf("C", "S6", "L"), link.sent)
        link.feed("t1112A0B1\rT123456788010203040506070812AB\rr1110\r")
        val first = slcan.readFrame(1_000)!!
        assertEquals(0x111, first.id)
        assertFalse(first.extended)
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 0xB1.toByte()), first.data)
        val second = slcan.readFrame(1_000)!!
        assertTrue(second.extended)
        assertEquals(0x12345678, second.id)
        assertEquals(8, second.data.size)
        // The remote frame is skipped; nothing else is waiting.
        assertNull(slcan.readFrame(200))
        assertTrue(link.sent.none { it.startsWith("O") || it.startsWith("t") || it.startsWith("T") })
    }

    @Test fun anAdapterWithoutListenOnlyStaysClosed() {
        val link = ScriptedLink { command -> if (command == "L") "\u0007" else "\r" }
        assertFalse(Slcan(link, SteppingClock()::tick).openListenOnly(500_000))
        assertEquals(listOf("C", "S6", "L"), link.sent)
        for (command in listOf("O", "t1230", "T123456780", "r1230", "M0", "W2")) assertFalse(command, SlcanCommands.allowed(command))
    }

    @Test fun malformedSlcanLinesAreIgnored() {
        assertNull(Slcan.parse("t11"))
        assertNull(Slcan.parse("t1119"))
        assertNull(Slcan.parse("t1112A0"))
        assertNull(Slcan.parse("z"))
        assertEquals(2, Slcan.parse("t1112A0B1FFFF")!!.data.size) // with a timestamp
    }

    @Test fun theIphoneGetsARangeOnlyWhenTheCarOrTheDriverGivesOne() {
        val withRange = VehicleStatusMapping.snapshot(VehicleReading(stateOfChargePercent = 50.0, rangeKm = 200), 20, null)!!
        assertEquals(200, withRange.rangeKm)
        assertEquals(400, withRange.maxRangeKm)
        assertFalse(withRange.rangeWarning)
        assertNull(withRange.currentChargeWh)
        val estimated = VehicleStatusMapping.snapshot(VehicleReading(stateOfChargePercent = 15.0, charging = true), 20, 400)!!
        assertEquals(60, estimated.rangeKm)
        assertEquals(400, estimated.maxRangeKm)
        assertTrue(estimated.rangeWarning)
        assertTrue(estimated.charging)
        assertNull(VehicleStatusMapping.snapshot(VehicleReading(stateOfChargePercent = 50.0), 20, null))
        assertNull(VehicleStatusMapping.snapshot(VehicleReading(rangeKm = 200), 20, 400))
    }

    @Test fun aDecodedGearOrChargeUpdatesTheReading() {
        val gear = SignalDecoding(VehicleSignal.GEAR, gears = mapOf(2L to VehicleGear.REVERSE))
        assertEquals(VehicleGear.REVERSE, gear.apply(2, VehicleReading()).gear)
        assertNull(gear.apply(9, VehicleReading()).gear)
        val charging = SignalDecoding(VehicleSignal.CHARGING, chargingValues = setOf(3L, 4L))
        assertEquals(true, charging.apply(3, VehicleReading()).charging)
        assertEquals(false, charging.apply(1, VehicleReading()).charging)
        assertEquals(42.5, SignalDecoding(VehicleSignal.STATE_OF_CHARGE, scale = 0.5).apply(85, VehicleReading()).stateOfChargePercent!!, 0.0)
        assertNull(SignalDecoding(VehicleSignal.STATE_OF_CHARGE).apply(250, VehicleReading()).stateOfChargePercent)
    }

    @Test fun anAdapterSetupThatReadTheBatteryBeforeDeclaresTheEvBeforeItsFirstReading() {
        val config = Iap2IdentificationConfig(name = "Tarmac", modelIdentifier = "m", manufacturer = "x",
            serialNumber = "s", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
            vehicleStatusEnabled = true)
        val pending = object : VehicleStatusProvider {
            override fun snapshot() = null
            override fun declarable() = true
        }
        assertTrue(config.withVehicleStatusFrom(pending).vehicleStatusEnabled)
        assertFalse(config.withVehicleStatusFrom(VehicleStatusProvider { null }).vehicleStatusEnabled)
    }
}
