package com.shilapi.xcertplay.androidauto

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A throwaway self-signed RSA certificate made at test time, so no key material is stored in the
 * repository. The JDK has no public certificate builder, so this writes the few DER structures a
 * version 3 certificate without extensions needs.
 */
internal class TestIdentity(commonName: String) {
    val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val certificate: X509Certificate

    init {
        val name = der(0x30, der(0x31, der(0x30, CN_OID + der(0x0C, commonName.toByteArray()))))
        val now = System.currentTimeMillis()
        val validity = der(0x30, time(Date(now - DAY)) + time(Date(now + 365 * DAY)))
        val tbs = der(0x30,
            der(0xA0, der(0x02, byteArrayOf(2))) +
                der(0x02, BigInteger.valueOf(now).toByteArray()) +
                SHA256_RSA + name + validity + name + keys.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keys.private)
            update(tbs)
        }.sign()
        val encoded = der(0x30, tbs + SHA256_RSA + der(0x03, byteArrayOf(0) + signature))
        certificate = CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
    }

    fun certificatePem(): String = pem("CERTIFICATE", certificate.encoded)

    fun privateKeyPem(): String = pem("PRIVATE KEY", keys.private.encoded)

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        val CN_OID = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
        val SHA256_RSA = byteArrayOf(
            0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00,
        )

        fun time(date: Date): ByteArray = der(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date).toByteArray())

        fun der(tag: Int, content: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(tag)
            when {
                content.size < 0x80 -> write(content.size)
                content.size < 0x100 -> { write(0x81); write(content.size) }
                else -> { write(0x82); write(content.size shr 8); write(content.size and 0xFF) }
            }
            write(content)
        }.toByteArray()

        fun pem(type: String, der: ByteArray): String =
            "-----BEGIN $type-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"
    }
}
