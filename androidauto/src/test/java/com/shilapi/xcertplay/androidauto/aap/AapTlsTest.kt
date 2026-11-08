package com.shilapi.xcertplay.androidauto.aap

import com.shilapi.xcertplay.androidauto.TestIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AapTlsTest {
    private val head = TestIdentity("Head Unit")
    private val phone = TestIdentity("Phone")

    /** A kept context lets the next connection resume the TLS session instead of a full handshake. */
    @Test fun secondConnectionResumesTheSession() {
        val headContext = AapTls.context(head.certificate, head.keys.private)
        val phoneContext = AapTls.context(phone.certificate, phone.keys.private)

        val first = connect(AapTls(headContext), AapTls(phoneContext, client = false))
        val second = connect(AapTls(headContext), AapTls(phoneContext, client = false))
        val fresh = connect(AapTls(head.certificate, head.keys.private), AapTls(phoneContext, client = false))

        assertArrayEquals(first.sessionId(), second.sessionId())
        assertFalse(first.sessionId().contentEquals(fresh.sessionId()))
    }

    @Test fun recordsRoundTrip() {
        val client = AapTls(head.certificate, head.keys.private)
        val server = AapTls(phone.certificate, phone.keys.private, client = false)
        connect(client, server)
        val message = ByteArray(40_000) { (it * 7).toByte() }

        assertArrayEquals(message, server.decrypt(client.encrypt(message)))
        assertArrayEquals(message, client.decrypt(server.encrypt(message)))
        assertEquals(head.certificate, server.peerCertificate())
    }

    /** Runs both handshakes over in-memory queues; returns the head unit's side. */
    private fun connect(client: AapTls, server: AapTls): AapTls {
        val toServer = LinkedBlockingQueue<ByteArray>()
        val toClient = LinkedBlockingQueue<ByteArray>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val serverDone = executor.submit {
                server.handshake(send = toClient::put, receive = { toServer.poll(5, TimeUnit.SECONDS)!! })
            }
            client.handshake(send = toServer::put, receive = { toClient.poll(5, TimeUnit.SECONDS)!! })
            serverDone.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        return client
    }
}
