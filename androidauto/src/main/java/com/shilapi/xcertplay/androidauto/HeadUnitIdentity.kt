// SPDX-License-Identifier: AGPL-3.0-or-later
package com.shilapi.xcertplay.androidauto

import android.content.Context
import com.shilapi.xcertplay.compat.Base64Compat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * The TLS client certificate and key this head unit presents to Android Auto.
 *
 * Android Auto accepts only a certificate issued by Google's *Automotive Link* authority, so the
 * identity has to come from a real product and is never part of the source tree. It is read from
 * the app's private `files/android-auto/` (pushed there on a debug build) or from APK assets that a
 * local build adds with `DIPLAY_AUTH_ASSETS_DIR`. See docs/ANDROID_AUTO.md.
 */
class HeadUnitIdentity internal constructor(
    internal val certificate: X509Certificate,
    internal val privateKey: PrivateKey,
) {
    /** For Settings: who issued the certificate and to whom, never the key. */
    val subject: String get() = certificate.subjectX500Principal.name
    val issuer: String get() = certificate.issuerX500Principal.name

    companion object {
        const val DIRECTORY = "android-auto"
        const val CERTIFICATE = "certificate.pem"
        const val PRIVATE_KEY = "private-key.pem"

        /** The installed identity, or null when none is installed or it cannot be read. */
        fun load(context: Context): HeadUnitIdentity? = try {
            val (certificate, key) = read(context) ?: return null
            parse(certificate, key)
        } catch (error: Exception) {
            AndroidAutoLog.w("head-unit identity unreadable: ${error.javaClass.simpleName}")
            null
        }

        fun installed(context: Context): Boolean = load(context) != null

        private fun read(context: Context): Pair<String, String>? {
            val directory = File(context.filesDir, DIRECTORY)
            val certificate = File(directory, CERTIFICATE)
            val key = File(directory, PRIVATE_KEY)
            if (certificate.isFile && key.isFile) return certificate.readText() to key.readText()
            return try {
                val assets = context.assets
                assets.open("$DIRECTORY/$CERTIFICATE").use { it.reader().readText() } to
                    assets.open("$DIRECTORY/$PRIVATE_KEY").use { it.reader().readText() }
            } catch (_: IOException) {
                null
            }
        }

        internal fun parse(certificatePem: String, privateKeyPem: String): HeadUnitIdentity {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certificatePem.trim().byteInputStream()) as X509Certificate
            return HeadUnitIdentity(certificate, parsePrivateKey(privateKeyPem))
        }

        /** PKCS#8 (`BEGIN PRIVATE KEY`) as open-headunit ships it, or PKCS#1 (`BEGIN RSA PRIVATE KEY`) as aasdk does. */
        internal fun parsePrivateKey(pem: String): PrivateKey {
            val pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY")
            val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
            val der = Base64Compat.decodeMime(body.toByteArray(Charsets.US_ASCII))
            val pkcs8 = if (pkcs1) wrapPkcs1(der) else der
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        }

        /** PrivateKeyInfo { version 0, AlgorithmIdentifier rsaEncryption NULL, OCTET STRING pkcs1 }. */
        internal fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val rsaEncryption = byteArrayOf(
                0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
                0x0D, 0x01, 0x01, 0x01, 0x05, 0x00,
            )
            return der(0x30, version + rsaEncryption + der(0x04, pkcs1))
        }

        private fun der(tag: Int, content: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(tag)
            val size = content.size
            when {
                size < 0x80 -> write(size)
                size < 0x100 -> { write(0x81); write(size) }
                size < 0x10000 -> { write(0x82); write(size shr 8); write(size and 0xFF) }
                else -> { write(0x83); write(size shr 16); write((size shr 8) and 0xFF); write(size and 0xFF) }
            }
            write(content)
        }.toByteArray()
    }
}
