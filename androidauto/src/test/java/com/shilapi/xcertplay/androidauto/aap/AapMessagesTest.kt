package com.shilapi.xcertplay.androidauto.aap

import com.shilapi.xcertplay.androidauto.proto.ProtoMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AapMessagesTest {
    private fun head(microphone: Boolean = true, location: Boolean = false) = HeadUnitDescription(
        resolution = AndroidAutoResolution.P720,
        sixtyFps = false,
        marginWidth = 0,
        marginHeight = 40,
        density = 180,
        microphone = microphone,
        location = location,
        rightHandDrive = false,
        make = "Tarmac",
        model = "Test",
        year = "2026",
        vehicleId = "id",
        headUnitMake = "Maker",
        headUnitModel = "Model",
        softwareVersion = "1.0",
    )

    @Test fun serviceDiscoveryAnnouncesEveryServiceOnItsChannel() {
        val response = ProtoMessage.parse(AapMessages.serviceDiscoveryResponse(head()))
        val services = response.messages(1).associateBy { it.int(1) }

        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 9), services.keys)
        assertEquals(listOf(13, 10), services.getValue(1).message(2)!!.messages(1).map { it.int(1) })

        val video = services.getValue(2).message(3)!!
        assertEquals(3, video.int(1)) // H.264 baseline
        val config = video.messages(4).single()
        assertEquals(2, config.int(1)) // 1280 × 720
        assertEquals(2, config.int(2)) // 30 fps
        assertEquals(40, config.int(4))
        assertEquals(180, config.int(5))

        val touch = services.getValue(3).message(4)!!.message(2)!!
        assertEquals(1280, touch.int(1))
        assertEquals(720, touch.int(2))

        val media = services.getValue(6).message(3)!!
        assertEquals(3, media.int(2)) // MEDIA stream
        assertEquals(listOf(48_000, 16, 2), media.message(3)!!.let { listOf(it.int(1), it.int(2), it.int(3)) })
        assertEquals(16_000, services.getValue(4).message(3)!!.message(3)!!.int(1))

        assertEquals("Tarmac", response.string(2))
        assertEquals(0, response.int(6))
        assertFalse(response.bool(11, true))
    }

    /** "GPS source · Phone" announces no location sensor, so the phone keeps its own GPS. */
    @Test fun locationSensorOnlyForTheHeadUnitsGps() {
        fun sensors(location: Boolean) = ProtoMessage.parse(AapMessages.serviceDiscoveryResponse(head(location = location)))
            .messages(1).single { it.int(1) == AapChannel.SENSOR }.message(2)!!.messages(1).map { it.int(1) }

        assertEquals(listOf(SensorType.DRIVING_STATUS, SensorType.NIGHT), sensors(location = false))
        assertEquals(listOf(SensorType.DRIVING_STATUS, SensorType.LOCATION, SensorType.NIGHT), sensors(location = true))
    }

    @Test fun locationEventUsesAndroidAutosFixedPointUnits() {
        val event = ProtoMessage.parse(AapMessages.location(
            timestampMillis = 1_762_588_800_000L,
            latitude = -36.848461,
            longitude = 174.763336,
            accuracyMeters = 4.5f,
            altitudeMeters = 12.34,
            speedMetersPerSecond = 13.9f,
            bearingDegrees = 271.25f,
        )).messages(SensorType.LOCATION).single()

        assertEquals(1_762_588_800_000L, event.long(1))
        assertEquals(-368_484_610, event.int(2))
        assertEquals(1_747_633_360, event.int(3))
        assertEquals(4_500, event.int(4))
        assertEquals(1_234, event.int(5))
        assertEquals(13_900, event.int(6))
        assertEquals(271_250_000, event.int(7))
    }

    /** Accuracy is a required field; altitude, speed and bearing are left out when the fix has none. */
    @Test fun locationEventWithoutOptionalFields() {
        val event = ProtoMessage.parse(AapMessages.location(1L, 51.5, -0.12, null, null, null, null))
            .messages(SensorType.LOCATION).single()

        assertEquals(100_000, event.int(4))
        assertFalse(event.has(5))
        assertFalse(event.has(6))
        assertFalse(event.has(7))
        assertEquals(-1_200_000, event.int(3))
    }

    @Test fun noMicrophoneServiceWithoutPermission() {
        val services = ProtoMessage.parse(AapMessages.serviceDiscoveryResponse(head(microphone = false))).messages(1)

        assertFalse(services.any { it.int(1) == AapChannel.MICROPHONE })
    }

    @Test fun versionResponseStatusIsSigned() {
        assertEquals(Triple(1, 7, 0), AapMessages.versionResponse(byteArrayOf(0, 1, 0, 7, 0, 0)))
        assertEquals(-1, AapMessages.versionResponse(byteArrayOf(0, 1, 0, 7, 0xFF.toByte(), 0xFF.toByte())).third)
    }

    @Test fun audioFocusIsAlwaysGranted() {
        fun answer(request: Int) = ProtoMessage.parse(
            AapMessages.audioFocusNotification(ProtoMessage.parse(com.shilapi.xcertplay.androidauto.proto.proto { int(1, request) })),
        ).int(1)

        assertEquals(1, answer(1)) // GAIN → STATE_GAIN
        assertEquals(2, answer(2)) // GAIN_TRANSIENT → STATE_GAIN_TRANSIENT
        assertEquals(7, answer(3)) // MAY_DUCK → GUIDANCE_ONLY
        assertEquals(3, answer(4)) // RELEASE → LOSS
    }

    @Test fun touchCarriesEveryPointer() {
        val report = ProtoMessage.parse(AapMessages.touch(123L, TouchAction.POINTER_DOWN, 1,
            listOf(TouchPointer(0, 10, 20), TouchPointer(1, 30, 40))))
        val touch = report.message(3)!!

        assertEquals(123L, report.long(1))
        assertEquals(listOf(Triple(10, 20, 0), Triple(30, 40, 1)), touch.messages(1).map { Triple(it.int(1), it.int(2), it.int(3)) })
        assertEquals(1, touch.int(2))
        assertEquals(TouchAction.POINTER_DOWN, touch.int(3))
    }

    @Test fun keyEvent() {
        val key = ProtoMessage.parse(AapMessages.key(5L, 87, true)).message(4)!!.message(1)!!

        assertEquals(87, key.int(1))
        assertTrue(key.bool(2))
    }

    @Test fun timestampedMediaIsBigEndianMicroseconds() {
        val body = AapMessages.timestamped(0x0102030405060708L, byteArrayOf(9, 10))

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), body)
    }

    @Test fun drivingStatusAndNight() {
        assertEquals(0, ProtoMessage.parse(AapMessages.drivingStatusUnrestricted()).message(13)!!.int(1, -1))
        assertTrue(ProtoMessage.parse(AapMessages.nightMode(true)).message(10)!!.bool(1))
    }
}
