package bridge.identity

import bridge.port.KeyStorePort

/** This device's TLS certificate (DER). Its SHA-256 [fingerprint] is the device ID (spec §3). */
class Identity(val certificate: ByteArray) {
    val fingerprint: String = fingerprint(certificate)

    companion object {
        /** Loads the stored identity, or creates the key and self-signed certificate on first launch. */
        fun loadOrCreate(keyStore: KeyStorePort): Identity = Identity(
            keyStore.loadCertificate()
                ?: selfSignedCertificate(keyStore.generateKey(), keyStore::sign).also(keyStore::saveCertificate),
        )
    }
}

/** SHA-256 of a DER certificate, lowercase hex. */
fun fingerprint(certificate: ByteArray): String = sha256(certificate).toHexString()

/**
 * Minimal X.509 v3 certificate, DER: subject = issuer = CN=Bridge, no extensions, valid from
 * 2025-01-01 with no expiry (RFC 5280 99991231235959Z), signed ecdsa-with-SHA256.
 * Nobody validates it against a CA; peers pin its fingerprint.
 */
internal fun selfSignedCertificate(subjectPublicKeyInfo: ByteArray, sign: (ByteArray) -> ByteArray): ByteArray {
    val ecdsaWithSha256 = der(0x30, der(0x06, bytes(0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02)))
    val name = der(0x30, der(0x31, der(0x30, der(0x06, bytes(0x55, 0x04, 0x03)) + der(0x0C, "Bridge".encodeToByteArray()))))
    // Positive, minimally encoded serial: top bits 01.
    val serial = secureRandomBytes(16).also { it[0] = (it[0].toInt() and 0x3F or 0x40).toByte() }
    val validity = der(0x30, der(0x17, "250101000000Z".encodeToByteArray()) + der(0x18, "99991231235959Z".encodeToByteArray()))
    val tbs = der(
        0x30,
        der(0xA0, der(0x02, bytes(2))) + der(0x02, serial) + ecdsaWithSha256 + name + validity + name + subjectPublicKeyInfo,
    )
    return der(0x30, tbs + ecdsaWithSha256 + der(0x03, bytes(0) + sign(tbs)))
}

private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

private fun der(tag: Int, content: ByteArray): ByteArray {
    val n = content.size
    val length = when {
        n < 0x80 -> bytes(n)
        n < 0x100 -> bytes(0x81, n)
        else -> bytes(0x82, n shr 8, n and 0xFF)
    }
    return bytes(tag) + length + content
}
