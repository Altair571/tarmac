package com.shilapi.xcertplay.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RootI2cTransportTest {
    private class FakeChip : I2cTransport {
        val seen = mutableListOf<Triple<Int, List<Byte>, Int>>()

        override fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
            seen += Triple(address7Bit, writeData.toList(), readLength)
            if (address7Bit == 0x10) throw I2cTransportException.Nack("no chip at 0x10")
            return ByteArray(readLength) { (it + 1).toByte() }
        }
    }

    @Test fun requestsAndResponsesSurviveTheHelperRoundTrip() {
        val requests = ByteArrayOutputStream()
        DataOutputStream(requests).use { out ->
            RootI2cProtocol.writeRequest(out, 0x11, byteArrayOf(0x00), 4)
            RootI2cProtocol.writeRequest(out, 0x10, byteArrayOf(0x02), 1)
        }
        val chip = FakeChip()
        val responses = ByteArrayOutputStream()
        RootI2cProtocol.serve(chip, DataInputStream(ByteArrayInputStream(requests.toByteArray())), DataOutputStream(responses))

        assertEquals(listOf(Triple(0x11, listOf<Byte>(0), 4), Triple(0x10, listOf<Byte>(2), 1)), chip.seen)
        val replies = DataInputStream(ByteArrayInputStream(responses.toByteArray()))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), RootI2cProtocol.readResponse(replies, 4))
        try {
            RootI2cProtocol.readResponse(replies, 1)
            fail("a NACK must come back as a NACK")
        } catch (expected: I2cTransportException.Nack) {
        }
    }

    @Test fun theAppSkipsWhateverTheRomPrintsBeforeTheHelperIsReady() {
        val stream = ByteArrayOutputStream()
        stream.write("WARNING: linker: something noisy\n".toByteArray())
        RootI2cProtocol.writeReady(DataOutputStream(stream), 0)
        RootI2cProtocol.awaitReady(DataInputStream(ByteArrayInputStream(stream.toByteArray())))
    }

    @Test fun aClosedNodeIsReportedAsPermissionDenied() {
        val stream = ByteArrayOutputStream()
        RootI2cProtocol.writeReady(DataOutputStream(stream),
            RootI2cProtocol.statusOf(I2cTransportException.PermissionDenied("closed")))
        try {
            RootI2cProtocol.awaitReady(DataInputStream(ByteArrayInputStream(stream.toByteArray())))
            fail("expected PermissionDenied")
        } catch (expected: I2cTransportException.PermissionDenied) {
        }
    }

    @Test fun refusedRootEndsBeforeTheHeader() {
        try {
            RootI2cProtocol.awaitReady(DataInputStream(ByteArrayInputStream("Permission denied\n".toByteArray())))
            fail("expected end of stream")
        } catch (expected: EOFException) {
        }
    }

    @Test fun everyFailureKeepsItsKindAcrossThePipe() {
        val kinds = listOf(
            I2cTransportException.PermissionDenied("x"),
            I2cTransportException.TimedOut("x"),
            I2cTransportException.Nack("x"),
            I2cTransportException.DeviceUnavailable("x"),
            I2cTransportException.InvalidRequest("x"),
            I2cTransportException.Protocol("x"),
        )
        for (error in kinds) {
            assertEquals(error::class, RootI2cProtocol.exceptionFor(RootI2cProtocol.statusOf(error), "x")::class)
        }
    }

    @Test fun theHelperCommandRunsTheAppsOwnClassOnAnI2cNodeOnly() {
        val command = RootI2cTransport.command("/data/app/x/base.apk", "/data/app/x/lib/arm64", "/dev/i2c-2")
        assertTrue(command, command.startsWith("CLASSPATH='/data/app/x/base.apk' app_process "))
        assertTrue(command, command.contains("-Djava.library.path='/data/app/x/lib/arm64'"))
        assertTrue(command, command.contains("com.shilapi.xcertplay.transport.RootI2cHelper /dev/i2c-2"))
        assertTrue(RootI2cTransport.command("/a'b.apk", "/l", "/dev/i2c-0").contains("'/a'\"'\"'b.apk'"))
        try {
            RootI2cTransport.command("/a.apk", "/l", "/dev/ttyHS2; reboot")
            fail("only /dev/i2c-N may reach the root shell")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
