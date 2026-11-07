package bridge.identity

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class CertificateTest {
    @Test
    fun selfSignedCertificateParsesAndVerifiesWithTheJdk() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val der = selfSignedCertificate(keys.public.encoded) { tbs ->
            Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(tbs); sign() }
        }

        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        cert.verify(keys.public)
        cert.checkValidity()
        assertEquals(3, cert.version)
        assertEquals("CN=Bridge", cert.subjectX500Principal.name)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        assertEquals(keys.public, cert.publicKey)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }, fingerprint(der))
    }
}
