package com.shilapi.xcertplay.androidauto.wireless

import com.shilapi.xcertplay.androidauto.AndroidAutoException
import com.shilapi.xcertplay.androidauto.AndroidAutoFailure
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** The phone's TCP connection behaves as the AAP session expects of any stream: null on a quiet timeout, empty at the end. */
class TcpByteStreamTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val phone = Socket(InetAddress.getLoopbackAddress(), server.localPort)
    private val head = TcpByteStream(server.accept())

    @After fun close() {
        head.close()
        phone.close()
        server.close()
    }

    @Test fun bytesGoBothWays() {
        head.send(byteArrayOf(1, 2, 3))
        val received = ByteArray(3)
        var filled = 0
        while (filled < 3) filled += phone.getInputStream().read(received, filled, 3 - filled)
        assertArrayEquals(byteArrayOf(1, 2, 3), received)

        phone.getOutputStream().write(byteArrayOf(4, 5))
        phone.getOutputStream().flush()
        var back = ByteArray(0)
        while (back.size < 2) back += head.recv(1024, 1_000) ?: continue
        assertArrayEquals(byteArrayOf(4, 5), back)
    }

    @Test fun aQuietLinkTimesOutWithNull() {
        val started = System.nanoTime()
        assertNull(head.recv(1024, 100))
        assertTrue(System.nanoTime() - started >= 90_000_000)
    }

    @Test fun thePhoneClosingEndsTheStream() {
        phone.close()
        assertEquals(0, head.recv(1024, 1_000)?.size)
    }

    @Test fun sendingAfterCloseIsADisconnect() {
        head.close()
        assertEquals(0, head.recv(1024, 100)?.size)
        try {
            head.send(byteArrayOf(1))
            fail("a closed stream does not send")
        } catch (error: AndroidAutoException) {
            assertEquals(AndroidAutoFailure.DISCONNECTED, error.failure)
        }
    }
}
