package com.shilapi.xcertplay.androidauto.aap

import com.shilapi.xcertplay.androidauto.TestIdentity
import com.shilapi.xcertplay.androidauto.proto.ProtoMessage
import com.shilapi.xcertplay.androidauto.proto.proto
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs the head unit's whole session against a simulated phone over an in-memory link: version,
 * TLS with the head unit's client certificate, auth complete, service discovery, channel opens,
 * media setup and acks, pings and the goodbye.
 */
class AapSessionTest {
    private val headIdentity = TestIdentity("Head Unit")
    private val phoneIdentity = TestIdentity("Phone")
    private val link = Link()
    private val events = LinkedBlockingQueue<String>()
    private val ended = CountDownLatch(1)
    private var endReason: String? = null
    private var endError: Throwable? = null
    private val videoFrames = LinkedBlockingQueue<Pair<ByteArray, Long>>()

    private val listener: AapSession.Listener = object : AapSession.Listener {
        override fun onConnected() { events += "connected" }
        override fun onVideoConfig(codecConfig: ByteArray) { events += "config:${codecConfig.size}" }
        override fun onVideoFrame(frame: ByteArray, timestampMicros: Long) { videoFrames += frame to timestampMicros }
        override fun onLocationRequested() {
            events += "location"
            session.sendLocation(AapMessages.location(5L, 1.0, 2.0, 3f, null, null, null))
        }
        override fun onAudio(channel: Int, pcm: ByteArray, offset: Int, length: Int) { events += "audio:$channel:$length" }
        override fun onEnded(reason: String, error: Throwable?) {
            endReason = reason
            endError = error
            ended.countDown()
        }
    }

    private val session: AapSession = AapSession(
        link.head,
        { AapTls(headIdentity.certificate, headIdentity.keys.private) },
        HeadUnitDescription(AndroidAutoResolution.P720, false, 0, 0, 160, microphone = false, location = true,
            rightHandDrive = false, make = "Tarmac", model = "Test", year = "2026", vehicleId = "id",
            headUnitMake = "Maker", headUnitModel = "Model", softwareVersion = "1"),
        listener,
    )
    private val phone = Phone(link.phone, phoneIdentity)

    @After fun tearDown() {
        session.close()
    }

    @Test fun fullSession() {
        session.start()

        // 1. Version, in the clear.
        val version = phone.readPlain()
        assertEquals(ControlType.VERSION_REQUEST, version.type)
        assertArrayEquals(byteArrayOf(0, 1, 0, 1), version.payload)
        phone.writePlain(ControlType.VERSION_RESPONSE, byteArrayOf(0, 1, 0, 1, 0, 0))

        // 2. TLS: the phone is the server and requires the head unit's certificate.
        phone.tls.handshake(
            send = { phone.writePlain(ControlType.ENCAPSULATED_SSL, it) },
            receive = { phone.readPlain().also { assertEquals(ControlType.ENCAPSULATED_SSL, it.type) }.payload },
        )
        assertEquals(headIdentity.certificate, phone.tls.peerCertificate())

        // 3. Auth complete, still in the clear.
        val auth = phone.readPlain()
        assertEquals(ControlType.AUTH_COMPLETE, auth.type)
        assertEquals(0, ProtoMessage.parse(auth.payload).int(1, -1))
        assertEquals("connected", events.poll(5, TimeUnit.SECONDS))

        // 4. Service discovery.
        phone.send(AapChannel.CONTROL, ControlType.SERVICE_DISCOVERY_REQUEST, proto { string(4, "Pixel"); string(5, "Google") })
        val discovery = phone.expect(AapChannel.CONTROL, ControlType.SERVICE_DISCOVERY_RESPONSE)
        val channels = ProtoMessage.parse(discovery.payload).messages(1).map { it.int(1) }.toSet()
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 9), channels)
        val sensors = ProtoMessage.parse(discovery.payload).messages(1).single { it.int(1) == AapChannel.SENSOR }
            .message(2)!!.messages(1).map { it.int(1) }
        assertTrue(SensorType.LOCATION in sensors)

        // 5. Channel open on the video channel carries the CONTROL flag both ways.
        phone.send(AapChannel.VIDEO, ControlType.CHANNEL_OPEN_REQUEST, proto { sint(1, 0); int(2, AapChannel.VIDEO) }, control = true)
        val open = phone.expect(AapChannel.VIDEO, ControlType.CHANNEL_OPEN_RESPONSE)
        assertTrue(open.control)
        assertEquals(0, ProtoMessage.parse(open.payload).int(1, -1))

        // The sensor channel answers its open with a driving status before Android Auto shows anything.
        phone.send(AapChannel.SENSOR, ControlType.CHANNEL_OPEN_REQUEST, proto { sint(1, 0); int(2, AapChannel.SENSOR) }, control = true)
        phone.expect(AapChannel.SENSOR, ControlType.CHANNEL_OPEN_RESPONSE)
        val driving = phone.expect(AapChannel.SENSOR, SensorType.EVENT)
        assertEquals(0, ProtoMessage.parse(driving.payload).message(SensorType.DRIVING_STATUS)!!.int(1, -1))
        val night = phone.expect(AapChannel.SENSOR, SensorType.EVENT)
        assertTrue(ProtoMessage.parse(night.payload).has(SensorType.NIGHT))

        // The phone asks for the location sensor it was offered, and location events follow.
        phone.send(AapChannel.SENSOR, SensorType.START_REQUEST, proto { int(1, SensorType.LOCATION); int(2, 0L) })
        assertEquals(0, ProtoMessage.parse(phone.expect(AapChannel.SENSOR, SensorType.START_RESPONSE).payload).int(1, -1))
        val fix = ProtoMessage.parse(phone.expect(AapChannel.SENSOR, SensorType.EVENT).payload).message(SensorType.LOCATION)!!
        assertEquals(10_000_000, fix.int(2))
        assertEquals("location", events.poll(5, TimeUnit.SECONDS))

        // 6. Video setup, start, codec config and one frame, which is acknowledged.
        phone.send(AapChannel.VIDEO, MediaType.SETUP, proto { int(1, 3) })
        val config = ProtoMessage.parse(phone.expect(AapChannel.VIDEO, MediaType.CONFIG).payload)
        assertEquals(2, config.int(1))
        assertTrue(config.int(2) > 0)
        phone.send(AapChannel.VIDEO, MediaType.START, proto { int(1, 7); int(2, 0) })
        phone.send(AapChannel.VIDEO, MediaType.CODEC_CONFIG, byteArrayOf(0, 0, 0, 1, 0x67))
        phone.send(AapChannel.VIDEO, MediaType.DATA, AapMessages.timestamped(42, byteArrayOf(0, 0, 0, 1, 0x65, 1, 2)))
        val ack = ProtoMessage.parse(phone.expect(AapChannel.VIDEO, MediaType.ACK).payload)
        assertEquals(7, ack.int(1))
        assertEquals(1, ack.int(2))
        val (frame, timestamp) = videoFrames.poll(5, TimeUnit.SECONDS)!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2), frame)
        assertEquals(42L, timestamp)
        assertEquals("config:5", events.poll(5, TimeUnit.SECONDS))

        // 7. A media message larger than one fragment arrives whole.
        val large = ByteArray(AapFrameCodec.MAX_FRAGMENT * 2) { it.toByte() }
        phone.send(AapChannel.VIDEO, MediaType.DATA, AapMessages.timestamped(43, large))
        phone.expect(AapChannel.VIDEO, MediaType.ACK)
        assertArrayEquals(large, videoFrames.poll(5, TimeUnit.SECONDS)!!.first)

        // 8. Ping, answered with the phone's own timestamp.
        phone.send(AapChannel.CONTROL, ControlType.PING_REQUEST, proto { int(1, 123_456L) })
        assertEquals(123_456L, ProtoMessage.parse(phone.expect(AapChannel.CONTROL, ControlType.PING_RESPONSE).payload).long(1))

        // 9. Goodbye ends the session cleanly.
        phone.send(AapChannel.CONTROL, ControlType.BYEBYE_REQUEST, proto { int(1, 1) })
        phone.expect(AapChannel.CONTROL, ControlType.BYEBYE_RESPONSE)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals("phone ended Android Auto", endReason)
        assertNull(endError)
    }

    @Test fun incompatibleVersionEndsTheSession() {
        session.start()
        phone.readPlain()
        phone.writePlain(ControlType.VERSION_RESPONSE, byteArrayOf(0, 1, 0, 1, 0xFF.toByte(), 0xFF.toByte()))

        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals("phone has no compatible Android Auto version", endReason)
    }

    /** The simulated phone: frames, TLS as server, and a reader that skips the head unit's own pings. */
    private class Phone(stream: BlockingDuplexByteStream, identity: TestIdentity) {
        private val stream = stream
        val tls = AapTls(identity.certificate, identity.keys.private, client = false)
        private val reader = AapStreamReader(stream, 5_000) { false }
        private val reassembler = AapReassembler()

        fun writePlain(type: Int, body: ByteArray) {
            AapFrameCodec.frames(AapChannel.CONTROL, 0, AapFrameCodec.plaintext(type, body)) { bytes, offset, length ->
                bytes.copyOfRange(offset, offset + length)
            }.forEach(stream::send)
        }

        fun readPlain(): AapMessage {
            while (true) {
                val frame = AapFrameCodec.read(reader::readFully)
                reassembler.accept(frame, frame.payload)?.let { return it }
            }
        }

        fun send(channel: Int, type: Int, body: ByteArray, control: Boolean = false) {
            val flags = AapFlags.ENCRYPTED or (if (control) AapFlags.CONTROL else 0)
            AapFrameCodec.frames(channel, flags, AapFrameCodec.plaintext(type, body)) { bytes, offset, length ->
                tls.encrypt(bytes, offset, length)
            }.forEach(stream::send)
        }

        fun expect(channel: Int, type: Int): AapMessage {
            while (true) {
                val frame = AapFrameCodec.read(reader::readFully)
                val plain = if (frame.encrypted) tls.decrypt(frame.payload) else frame.payload
                val message = reassembler.accept(frame, plain) ?: continue
                if (message.channel == channel && message.type == type) return message
                // The head unit's own pings come every two seconds; anything else is a failure.
                if (message.type != ControlType.PING_REQUEST) throw AssertionError("expected $type on $channel, got ${message.type} on ${message.channel}")
            }
        }
    }

    /** Two ends of an in-memory byte link. */
    private class Link {
        private val toHead = LinkedBlockingQueue<ByteArray>()
        private val toPhone = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false

        val head: BlockingDuplexByteStream = end(toPhone, toHead)
        val phone: BlockingDuplexByteStream = end(toHead, toPhone)

        private fun end(outgoing: LinkedBlockingQueue<ByteArray>, incoming: LinkedBlockingQueue<ByteArray>) = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) {
                if (!closed) outgoing.put(data.copyOf())
            }
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                if (closed) return ByteArray(0)
                return incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            }
            override fun close() {
                closed = true
            }
        }
    }
}
