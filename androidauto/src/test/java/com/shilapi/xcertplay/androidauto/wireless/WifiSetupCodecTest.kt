package com.shilapi.xcertplay.androidauto.wireless

import com.shilapi.xcertplay.androidauto.AndroidAutoException
import com.shilapi.xcertplay.androidauto.AndroidAutoFailure
import com.shilapi.xcertplay.androidauto.proto.ProtoMessage
import com.shilapi.xcertplay.androidauto.proto.proto
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class WifiSetupCodecTest {
    @Test fun frameIsLengthThenTypeBigEndianThenPayload() {
        val frame = WifiSetupCodec.frame(WifiSetupType.INFO_RESPONSE, byteArrayOf(9, 8, 7))

        assertArrayEquals(byteArrayOf(0, 3, 0, 3, 9, 8, 7), frame)
    }

    /** Lengths above 255 use the high byte, which a little-endian or one-byte length would get wrong. */
    @Test fun longPayloadRoundTrips() {
        val payload = ByteArray(300) { it.toByte() }
        val frame = WifiSetupCodec.frame(0x0102, payload)
        assertEquals(1, frame[0].toInt())
        assertEquals(44, frame[1].toInt())
        assertEquals(1, frame[2].toInt())
        assertEquals(2, frame[3].toInt())

        val (message, used) = WifiSetupCodec.parse(frame, frame.size)!!
        assertEquals(0x0102, message.type)
        assertArrayEquals(payload, message.payload)
        assertEquals(frame.size, used)
    }

    @Test fun parseWaitsForTheWholeMessageAndLeavesTheNext() {
        val first = WifiSetupCodec.frame(WifiSetupType.INFO_REQUEST, ByteArray(0))
        val second = WifiSetupCodec.frame(WifiSetupType.CONNECT_STATUS, proto { int(1, 0) })
        val joined = first + second

        assertNull(WifiSetupCodec.parse(joined, 3))
        val (message, used) = WifiSetupCodec.parse(joined, joined.size)!!
        assertEquals(WifiSetupType.INFO_REQUEST, message.type)
        assertEquals(0, message.payload.size)
        assertEquals(first.size, used)
        assertNull(WifiSetupCodec.parse(second, second.size - 1))
    }

    @Test fun oversizedPayloadIsRefusedRatherThanTruncated() {
        try {
            WifiSetupCodec.frame(WifiSetupType.INFO_RESPONSE, ByteArray(0x10000))
            fail("a 64 KiB payload does not fit the length field")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun startRequestCarriesAddressPortAndUserReason() {
        val body = ProtoMessage.parse(WifiSetupCodec.startRequest("192.168.49.1", 5288))

        assertEquals("192.168.49.1", body.string(1))
        assertEquals(5288, body.int(2))
        assertTrue(body.has(3))
        assertEquals(0, body.int(3, -1))
    }

    /** All five fields go out, an empty BSSID included: phones drop the message over a missing required field. */
    @Test fun infoResponseCarriesAllFiveFields() {
        val credentials = WifiCredentials("Tarmac-AA", "secret-pass", "", WifiSecurityMode.WPA2_PERSONAL, "192.168.49.1", 5288)
        val body = ProtoMessage.parse(WifiSetupCodec.infoResponse(credentials))

        assertEquals("Tarmac-AA", body.string(1))
        assertEquals("secret-pass", body.string(2))
        assertTrue(body.has(3))
        assertEquals("", body.string(3))
        assertEquals(8, body.int(4))
        assertEquals(WifiAccessPointType.DYNAMIC, body.int(5))
    }

    @Test fun statusReadsTheRightFieldForEachType() {
        val joined = WifiSetupMessage(WifiSetupType.CONNECT_STATUS, proto { int(1, 0) })
        val refused = WifiSetupMessage(WifiSetupType.CONNECT_STATUS, proto { int(1, -11); string(2, "network not found") })
        val started = WifiSetupMessage(WifiSetupType.START_RESPONSE, proto { string(1, "192.168.49.20"); int(2, 0); int(3, -4) })

        assertEquals(0, WifiSetupCodec.status(joined))
        // A negative int32 is a ten-byte varint; it must not read back as a large positive number.
        assertEquals(-11, WifiSetupCodec.status(refused))
        assertEquals("network not found", WifiSetupCodec.hint(refused))
        assertEquals(-4, WifiSetupCodec.status(started))
    }

    @Test fun missingOrUnreadableStatusIsNotARefusal() {
        assertNull(WifiSetupCodec.status(WifiSetupMessage(WifiSetupType.CONNECT_STATUS, ByteArray(0))))
        assertNull(WifiSetupCodec.status(WifiSetupMessage(WifiSetupType.CONNECT_STATUS, byteArrayOf(0x08))))
        assertNull(WifiSetupCodec.status(WifiSetupMessage(WifiSetupType.INFO_REQUEST, proto { int(1, -1) })))
        assertNull(WifiSetupCodec.hint(WifiSetupMessage(WifiSetupType.CONNECT_STATUS, proto { int(1, 0) })))
    }

    @Test fun securityModesMapFromTheHotspot() {
        assertEquals(WifiSecurityMode.OPEN, WifiSecurityMode.of(Iap2WirelessSecurity.NONE))
        assertEquals(WifiSecurityMode.WPA2_PERSONAL, WifiSecurityMode.of(Iap2WirelessSecurity.WPA_WPA2))
        assertEquals(WifiSecurityMode.WPA2_WPA3_PERSONAL, WifiSecurityMode.of(Iap2WirelessSecurity.WPA3_TRANSITION))
        assertEquals(WifiSecurityMode.WPA3_PERSONAL, WifiSecurityMode.of(Iap2WirelessSecurity.WPA3_ONLY))
    }

    @Test fun credentialsFromAWifiDirectGroup() {
        val info = info(bssid = "AA:BB:CC:00:11:22", addresses = listOf(
            InetAddress.getByName("fe80::1"), InetAddress.getByName("192.168.49.1")))

        val credentials = AndroidAutoWireless.credentials(info, 5288)

        assertEquals("DIRECT-xy-Tarmac", credentials.ssid)
        assertEquals("passphrase", credentials.key)
        assertEquals("aa:bb:cc:00:11:22", credentials.bssid)
        assertEquals(WifiSecurityMode.WPA2_PERSONAL, credentials.securityMode)
        assertEquals("192.168.49.1", credentials.hostAddress)
        assertEquals(5288, credentials.port)
        assertFalse(credentials.toString().contains("passphrase"))
    }

    /** On a network the head unit only joins, the phone needs that network's access point, not the head unit's own address. */
    @Test fun credentialsOnAStationNetworkNameTheAccessPoint() {
        val info = info(bssid = "02:00:00:00:00:01", addresses = listOf(InetAddress.getByName("10.0.0.5")),
            accessPoint = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60))

        assertEquals("10:20:30:40:50:60", AndroidAutoWireless.credentials(info, 40000).bssid)
    }

    @Test fun credentialsNeedAnIpv4Address() {
        try {
            AndroidAutoWireless.credentials(info(bssid = null, addresses = listOf(InetAddress.getByName("fe80::1"))), 5288)
            fail("the start request carries an IPv4 address")
        } catch (error: AndroidAutoException) {
            assertEquals(AndroidAutoFailure.WIFI, error.failure)
        }
    }

    private fun info(bssid: String?, addresses: List<InetAddress>, accessPoint: ByteArray? = null) = WirelessHotspotInfo(
        ssid = "DIRECT-xy-Tarmac",
        passphrase = "passphrase",
        security = Iap2WirelessSecurity.WPA_WPA2,
        channel = 36,
        frequencyMHz = 5180,
        bssid = bssid,
        interfaceName = "p2p-wlan0-0",
        hostAddress = addresses.first(),
        bandLabel = "5 GHz",
        backend = WirelessHotspotBackend.WIFI_P2P,
        hostAddresses = addresses,
        accessPointBssid = accessPoint,
    )
}
