// SPDX-License-Identifier: AGPL-3.0-or-later
// Handshake order and the client-mode, any-peer trust choice follow Headunit (Michael Reid),
// anod/headunit and open-headunit (AapSslContext, SingleKeyKeyManager).
package com.shilapi.xcertplay.androidauto.aap

import android.annotation.SuppressLint
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * TLS for one Android Auto session. The head unit is the TLS *client* and presents its
 * certificate as a client certificate; the phone checks it against Google's Automotive Link
 * authority. Handshake records travel in plaintext `ENCAPSULATED_SSL` messages, application records
 * in frames flagged as encrypted.
 *
 * Callers keep record order: [encrypt] results must be written in the order they were made.
 *
 * One [context] serves every session with the same identity. Its session cache lets the next
 * connection resume the last TLS session when the phone allows it: an abbreviated handshake, with
 * no certificate exchange and no RSA work.
 */
internal class AapTls(
    context: SSLContext,
    /** False only in tests, where the same class plays the phone. */
    client: Boolean = true,
) : AapCipher {
    constructor(certificate: X509Certificate, privateKey: PrivateKey, client: Boolean = true) :
        this(context(certificate, privateKey), client)

    private val engineLock = Any()
    // A fixed peer name and port are the session cache's lookup key; nothing is resolved.
    private val engine: SSLEngine = context.createSSLEngine(PEER_HOST, PEER_PORT).apply {
        useClientMode = client
        if (!client) needClientAuth = true
        // Head units shipped with TLS 1.2, so every Android Auto phone speaks it.
        supportedProtocols.filter { it == "TLSv1.2" }.takeIf { it.isNotEmpty() }?.let { enabledProtocols = it.toTypedArray() }
    }
    /** Received bytes not yet consumed by the engine: a partial record waits here for the rest. */
    private var pending = ByteArray(0)

    /**
     * Runs the handshake. [send] gets each outgoing flight; [receive] returns the next handshake
     * payload from the phone. Throws [SSLException] when either side gives up.
     */
    fun handshake(send: (ByteArray) -> Unit, receive: () -> ByteArray) {
        synchronized(engineLock) { engine.beginHandshake() }
        val flight = ByteArrayOutputStream()
        while (true) {
            when (synchronized(engineLock) { engine.handshakeStatus }) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val (result, bytes) = wrap(EMPTY)
                    if (result.status == SSLEngineResult.Status.CLOSED) throw SSLException("TLS closed during the handshake")
                    flight.write(bytes)
                    // One message per flight, sent once the engine wants something other than writing.
                    if (result.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_WRAP && flight.size() > 0) {
                        send(flight.toByteArray())
                        flight.reset()
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (pending.isEmpty()) pending = receive()
                    val (result, _) = unwrap()
                    when (result.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> pending += receive()
                        SSLEngineResult.Status.CLOSED -> throw SSLException("phone closed TLS during the handshake")
                        else -> Unit
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                SSLEngineResult.HandshakeStatus.FINISHED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    if (flight.size() > 0) send(flight.toByteArray())
                    return
                }
                // NEED_UNWRAP_AGAIN: the engine already holds the input it needs.
                else -> unwrap()
            }
        }
    }

    /** The TLS record(s) carrying [length] bytes of [plain] from [offset]. */
    override fun encrypt(plain: ByteArray, offset: Int, length: Int): ByteArray {
        val input = ByteBuffer.wrap(plain, offset, length)
        val out = ByteArrayOutputStream(length + 64)
        while (input.hasRemaining()) {
            val (result, bytes) = wrap(input)
            if (result.status == SSLEngineResult.Status.CLOSED) throw SSLException("TLS closed")
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /** The plaintext of the complete records in the input so far. */
    override fun decrypt(cipher: ByteArray, offset: Int, length: Int): ByteArray {
        pending += cipher.copyOfRange(offset, offset + length)
        val out = ByteArrayOutputStream(length)
        while (pending.isNotEmpty()) {
            val (result, bytes) = unwrap()
            out.write(bytes)
            if (result.status == SSLEngineResult.Status.CLOSED) throw SSLException("phone closed TLS")
            if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break
            if (result.bytesConsumed() == 0 && bytes.isEmpty()) break
        }
        return out.toByteArray()
    }

    fun close() {
        synchronized(engineLock) { runCatching { engine.closeOutbound() } }
    }

    /** The certificate the other side presented, once the handshake is done. */
    internal fun peerCertificate(): X509Certificate? =
        synchronized(engineLock) { runCatching { engine.session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() }

    /** The TLS session id; the same id on two connections means the second one resumed the first. */
    internal fun sessionId(): ByteArray = synchronized(engineLock) { engine.session.id }

    private fun wrap(source: ByteBuffer): Pair<SSLEngineResult, ByteArray> {
        var capacity = synchronized(engineLock) { engine.session.packetBufferSize }
        while (true) {
            val output = ByteBuffer.allocate(capacity)
            val result = synchronized(engineLock) { engine.wrap(source, output) }
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                capacity = grow(capacity)
                continue
            }
            if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
            output.flip()
            return result to ByteArray(output.remaining()).also(output::get)
        }
    }

    private fun unwrap(): Pair<SSLEngineResult, ByteArray> {
        var capacity = synchronized(engineLock) { engine.session.applicationBufferSize }
        while (true) {
            val input = ByteBuffer.wrap(pending)
            val output = ByteBuffer.allocate(capacity)
            val result = synchronized(engineLock) { engine.unwrap(input, output) }
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                capacity = grow(capacity)
                continue
            }
            pending = if (input.hasRemaining()) pending.copyOfRange(input.position(), pending.size) else ByteArray(0)
            if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
            output.flip()
            return result to ByteArray(output.remaining()).also(output::get)
        }
    }

    private fun grow(capacity: Int): Int {
        if (capacity >= MAXIMUM_BUFFER_BYTES) throw SSLException("TLS buffer exceeds $MAXIMUM_BUFFER_BYTES bytes")
        return capacity * 2
    }

    private fun runTasks() {
        while (true) (synchronized(engineLock) { engine.delegatedTask } ?: return).run()
    }

    /** Always presents this head unit's certificate, whatever authorities the phone lists. */
    private class FixedKeyManager(
        private val key: PrivateKey,
        private val chain: Array<X509Certificate>,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
        override fun getPrivateKey(alias: String?): PrivateKey = key
    }

    /**
     * The phone is the TLS server here and no head unit holds the authority that signs phones, so
     * its certificate is not checked; every Android Auto head unit does the same. The check that
     * matters, the phone checking the head unit, happens on the phone.
     */
    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    private object AnyPeerTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val ALIAS = "head-unit"
        private const val PEER_HOST = "android-auto"
        private const val PEER_PORT = 5277
        private const val MAXIMUM_BUFFER_BYTES = 1 shl 20
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        /** A TLS context presenting [certificate]; keep it to let later sessions resume. */
        fun context(certificate: X509Certificate, privateKey: PrivateKey): SSLContext = SSLContext.getInstance("TLS").apply {
            init(arrayOf(FixedKeyManager(privateKey, arrayOf(certificate))), arrayOf(AnyPeerTrustManager), null)
        }
    }
}

/** What the session needs from TLS once the handshake is done; tests substitute a pass-through. */
internal interface AapCipher {
    fun encrypt(plain: ByteArray, offset: Int = 0, length: Int = plain.size): ByteArray
    fun decrypt(cipher: ByteArray, offset: Int = 0, length: Int = cipher.size): ByteArray
}
