package com.shilapi.xcertplay.androidauto.wireless

import com.shilapi.xcertplay.androidauto.AndroidAutoException
import com.shilapi.xcertplay.androidauto.AndroidAutoFailure
import com.shilapi.xcertplay.androidauto.proto.ProtoMessage
import com.shilapi.xcertplay.androidauto.proto.proto
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The head unit's half of the Bluetooth setup against a simulated phone over an in-memory link. */
class WifiSetupExchangeTest {
    private val credentials = WifiCredentials("DIRECT-xy-Tarmac", "passphrase", "aa:bb:cc:00:11:22",
        WifiSecurityMode.WPA2_PERSONAL, "192.168.49.1", 5288)
    private val link = Link()
    private val phone = Phone(link.phone)

    @Test fun phoneJoinsAfterTheCredentials() {
        val exchange = WifiSetupExchange(link.head, credentials)
        val result = runInBackground { exchange.run() }

        val start = phone.expect(WifiSetupType.START_REQUEST)
        assertEquals("192.168.49.1", start.string(1))
        assertEquals(5288, start.int(2))
        phone.send(WifiSetupType.INFO_REQUEST, ByteArray(0))
        val info = phone.expect(WifiSetupType.INFO_RESPONSE)
        assertEquals("DIRECT-xy-Tarmac", info.string(1))
        assertEquals("passphrase", info.string(2))
        assertEquals("aa:bb:cc:00:11:22", info.string(3))
        phone.send(WifiSetupType.START_RESPONSE, proto { string(1, "192.168.49.20"); int(2, 0); int(3, 0) })
        phone.send(WifiSetupType.CONNECT_STATUS, proto { int(1, 0) })

        assertNull(result.await())
    }

    @Test fun pingsAreEchoedAndARepeatedRequestGetsTheCredentialsAgain() {
        val exchange = WifiSetupExchange(link.head, credentials)
        val result = runInBackground { exchange.run() }

        phone.expect(WifiSetupType.START_REQUEST)
        val ping = proto { int(1, 123456789L) }
        phone.send(WifiSetupType.PING_REQUEST, ping)
        assertArrayEquals(ping, phone.expectRaw(WifiSetupType.PING_RESPONSE))
        phone.send(WifiSetupType.INFO_REQUEST, ByteArray(0))
        phone.expect(WifiSetupType.INFO_RESPONSE)
        phone.send(WifiSetupType.INFO_REQUEST, ByteArray(0))
        assertEquals("DIRECT-xy-Tarmac", phone.expect(WifiSetupType.INFO_RESPONSE).string(1))
        phone.send(WifiSetupType.CONNECT_STATUS, proto { int(1, 0) })

        assertNull(result.await())
    }

    @Test fun aRefusedJoinFailsWithThePhonesReason() {
        val exchange = WifiSetupExchange(link.head, credentials)
        val result = runInBackground { exchange.run() }

        phone.expect(WifiSetupType.START_REQUEST)
        phone.send(WifiSetupType.INFO_REQUEST, ByteArray(0))
        phone.expect(WifiSetupType.INFO_RESPONSE)
        phone.send(WifiSetupType.CONNECT_STATUS, proto { int(1, -3); string(2, "wrong password") })

        val error = result.await() as AndroidAutoException
        assertEquals(AndroidAutoFailure.PHONE_WIFI, error.failure)
        assertTrue(error.message!!.contains("-3"))
        assertTrue(error.message!!.contains("wrong password"))
    }

    @Test fun aRefusedStartRequestFails() {
        val exchange = WifiSetupExchange(link.head, credentials)
        val result = runInBackground { exchange.run() }

        phone.expect(WifiSetupType.START_REQUEST)
        phone.send(WifiSetupType.START_RESPONSE, proto { int(3, -4) })

        assertEquals(AndroidAutoFailure.PHONE_WIFI, (result.await() as AndroidAutoException).failure)
    }

    @Test fun aSilentPhoneIsReportedAsSilent() {
        val exchange = WifiSetupExchange(link.head, credentials, firstReplyMillis = 300)
        val result = runInBackground { exchange.run() }

        phone.expect(WifiSetupType.START_REQUEST)

        assertEquals(AndroidAutoFailure.SILENT, (result.await() as AndroidAutoException).failure)
    }

    @Test fun aPhoneThatNeverReportsTheJoinTimesOut() {
        val exchange = WifiSetupExchange(link.head, credentials, joinMillis = 300)
        val result = runInBackground { exchange.run() }

        phone.expect(WifiSetupType.START_REQUEST)
        phone.send(WifiSetupType.INFO_REQUEST, ByteArray(0))
        phone.expect(WifiSetupType.INFO_RESPONSE)

        assertEquals(AndroidAutoFailure.PHONE_WIFI, (result.await() as AndroidAutoException).failure)
    }

    /** A phone already on the network can open Android Auto before it reports the join; the exchange stops waiting. */
    @Test fun anEarlyTcpConnectionEndsTheExchange() {
        val tcp = AtomicBoolean(false)
        val exchange = WifiSetupExchange(link.head, credentials, tcpConnected = tcp::get)
        val result = runInBackground { exchange.run() }

        phone.expect(WifiSetupType.START_REQUEST)
        phone.send(WifiSetupType.START_RESPONSE, proto { int(3, 0) })
        tcp.set(true)

        assertNull(result.await())
    }

    @Test fun thePhoneClosingTheChannelFails() {
        val exchange = WifiSetupExchange(link.head, credentials)
        val result = runInBackground { exchange.run() }

        phone.expect(WifiSetupType.START_REQUEST)
        link.close()

        assertEquals(AndroidAutoFailure.DISCONNECTED, (result.await() as AndroidAutoException).failure)
    }

    /** During the session only pings are answered: credentials onto a live session would drop it. */
    @Test fun theHeldChannelAnswersPingsOnlyUntilClosed() {
        val exchange = WifiSetupExchange(link.head, credentials)
        val joined = runInBackground { exchange.run() }
        phone.expect(WifiSetupType.START_REQUEST)
        phone.send(WifiSetupType.CONNECT_STATUS, proto { int(1, 0) })
        assertNull(joined.await())

        val held = runInBackground { exchange.hold() }
        phone.send(WifiSetupType.INFO_REQUEST, ByteArray(0))
        val ping = proto { int(1, 42L) }
        phone.send(WifiSetupType.PING_REQUEST, ping)
        assertArrayEquals(ping, phone.expectRaw(WifiSetupType.PING_RESPONSE))
        link.close()

        assertNull(held.await())
    }

    private class Outcome(private val thread: Thread, private val error: AtomicReference<Throwable?>) {
        fun await(): Throwable? {
            thread.join(5_000)
            if (thread.isAlive) fail("the exchange did not finish")
            return error.get()
        }
    }

    private fun runInBackground(block: () -> Unit): Outcome {
        val error = AtomicReference<Throwable?>()
        val thread = Thread { runCatching(block).onFailure(error::set) }.apply { isDaemon = true; start() }
        return Outcome(thread, error)
    }

    /** The phone's end: reads whole setup messages and fails on anything it did not expect. */
    private class Phone(private val stream: BlockingDuplexByteStream) {
        private var buffer = ByteArray(0)

        fun send(type: Int, payload: ByteArray) = stream.send(WifiSetupCodec.frame(type, payload))

        fun expect(type: Int): ProtoMessage = ProtoMessage.parse(expectRaw(type))

        fun expectRaw(type: Int): ByteArray {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                WifiSetupCodec.parse(buffer, buffer.size)?.let { (message, used) ->
                    buffer = buffer.copyOfRange(used, buffer.size)
                    assertEquals(type, message.type)
                    return message.payload
                }
                stream.recv(4096, 100)?.let { buffer += it }
            }
            throw AssertionError("no message of type $type")
        }
    }

    /** Two ends of an in-memory byte link, as in AapSessionTest. */
    private class Link {
        private val toHead = LinkedBlockingQueue<ByteArray>()
        private val toPhone = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false

        val head: BlockingDuplexByteStream = end(toPhone, toHead)
        val phone: BlockingDuplexByteStream = end(toHead, toPhone)

        fun close() {
            closed = true
        }

        private fun end(outgoing: LinkedBlockingQueue<ByteArray>, incoming: LinkedBlockingQueue<ByteArray>) = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) {
                if (closed) throw java.io.IOException("link closed")
                outgoing.put(data.copyOf())
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
