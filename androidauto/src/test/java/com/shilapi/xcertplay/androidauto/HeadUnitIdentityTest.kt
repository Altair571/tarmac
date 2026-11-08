package com.shilapi.xcertplay.androidauto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadUnitIdentityTest {
    private val identity = TestIdentity("Test Head Unit")

    @Test fun readsPkcs8KeyAndCertificate() {
        val parsed = HeadUnitIdentity.parse(identity.certificatePem(), identity.privateKeyPem())

        assertEquals(identity.certificate, parsed.certificate)
        assertArrayEquals(identity.keys.private.encoded, parsed.privateKey.encoded)
        assertEquals("CN=Test Head Unit", parsed.subject)
    }

    /** aasdk ships its key as PKCS#1 (`BEGIN RSA PRIVATE KEY`); it must read as the same key. */
    @Test fun readsPkcs1Key() {
        val pkcs8 = identity.keys.private.encoded
        val pkcs1 = innerPkcs1(pkcs8)
        val pem = "-----BEGIN RSA PRIVATE KEY-----\n" +
            java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs1) +
            "\n-----END RSA PRIVATE KEY-----\n"

        assertArrayEquals(pkcs8, HeadUnitIdentity.parsePrivateKey(pem).encoded)
        assertArrayEquals(pkcs8, HeadUnitIdentity.wrapPkcs1(pkcs1))
    }

    /** PrivateKeyInfo's last element is an OCTET STRING holding the PKCS#1 key. */
    private fun innerPkcs1(pkcs8: ByteArray): ByteArray {
        var index = 0
        fun length(): Int {
            val first = pkcs8[index++].toInt() and 0xFF
            if (first < 0x80) return first
            var value = 0
            repeat(first and 0x7F) { value = (value shl 8) or (pkcs8[index++].toInt() and 0xFF) }
            return value
        }
        index++ // SEQUENCE
        length()
        index++ // INTEGER version
        // Not `index += length()`: that reads index before length() moves it.
        val version = length()
        index += version
        index++ // AlgorithmIdentifier
        val algorithm = length()
        index += algorithm
        index++ // OCTET STRING
        val size = length()
        return pkcs8.copyOfRange(index, index + size)
    }
}
