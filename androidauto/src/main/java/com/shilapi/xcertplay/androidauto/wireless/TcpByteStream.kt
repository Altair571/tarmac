// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto.wireless

import com.shilapi.xcertplay.androidauto.AndroidAutoException
import com.shilapi.xcertplay.androidauto.AndroidAutoFailure
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The phone's TCP connection as a [BlockingDuplexByteStream], so the same AAP session runs over
 * Wi-Fi as over USB. Reads come from the session's one reading thread; writes may come from any.
 */
internal class TcpByteStream(private val socket: Socket) : BlockingDuplexByteStream {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val readBuffer = ByteArray(READ_CHUNK)
    private val writeLock = Any()
    @Volatile private var closed = false

    init {
        // Touches and acks are small and must not wait for Nagle's algorithm.
        socket.tcpNoDelay = true
        socket.keepAlive = true
    }

    override fun send(data: ByteArray) = synchronized(writeLock) {
        if (closed) throw AndroidAutoException(AndroidAutoFailure.DISCONNECTED, "Wi-Fi connection closed")
        try {
            output.write(data)
            output.flush()
        } catch (error: IOException) {
            throw AndroidAutoException(AndroidAutoFailure.DISCONNECTED, "the phone left the Wi-Fi connection", error)
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        if (closed) return ByteArray(0)
        return try {
            socket.soTimeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val read = input.read(readBuffer, 0, minOf(maxBytes, readBuffer.size))
            if (read < 0) ByteArray(0) else readBuffer.copyOf(read)
        } catch (_: SocketTimeoutException) {
            null
        } catch (error: IOException) {
            if (closed) ByteArray(0)
            else throw AndroidAutoException(AndroidAutoFailure.DISCONNECTED, "the phone left the Wi-Fi connection", error)
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    private companion object {
        const val READ_CHUNK = 64 * 1024
    }
}
