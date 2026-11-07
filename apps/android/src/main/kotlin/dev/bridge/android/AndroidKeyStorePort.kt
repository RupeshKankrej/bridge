package dev.bridge.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import bridge.port.KeyStorePort
import bridge.port.TrustedPeer
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Identity key in Android Keystore (hardware-backed, never exportable). The trust list is in
 * app-private SharedPreferences: Keystore holds keys, not data, and fingerprints aren't secret.
 */
class AndroidKeyStorePort(context: Context) : KeyStorePort {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    override fun loadCertificate(): ByteArray? = if (prefs.getBoolean(CERT_SAVED, false)) certificate().encoded else null

    override fun generateKey(): ByteArray {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // TLS may hand Keystore an already-hashed CertificateVerify, which needs DIGEST_NONE.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair().public.encoded
    }

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey())
        update(data)
        sign()
    }

    override fun saveCertificate(certificate: ByteArray) {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certificate.inputStream())
        // Replaces the placeholder certificate Keystore created with the key, so TLS presents core's.
        keyStore.setKeyEntry(ALIAS, privateKey(), null, arrayOf(cert))
        prefs.edit().putBoolean(CERT_SAVED, true).commit()
    }

    fun privateKey(): PrivateKey = keyStore.getKey(ALIAS, null) as PrivateKey

    fun certificate(): X509Certificate = keyStore.getCertificate(ALIAS) as X509Certificate

    override fun trustedPeers(): List<TrustedPeer> {
        val array = JSONArray(prefs.getString(PEERS, "[]"))
        return (0 until array.length()).map { i ->
            with(array.getJSONObject(i)) { TrustedPeer(getString("fingerprint"), getString("name"), optString("host").ifEmpty { null }, optInt("port")) }
        }
    }

    override fun saveTrustedPeer(peer: TrustedPeer) = store(trustedPeers().filter { it.fingerprint != peer.fingerprint } + peer)

    override fun removeTrustedPeer(fingerprint: String) = store(trustedPeers().filter { it.fingerprint != fingerprint })

    private fun store(peers: List<TrustedPeer>) {
        val json = JSONArray(peers.map { JSONObject().put("fingerprint", it.fingerprint).put("name", it.name).put("host", it.host).put("port", it.port) })
        prefs.edit().putString(PEERS, json.toString()).commit()
    }

    companion object {
        const val ALIAS = "bridge-identity"
        private const val CERT_SAVED = "identity_certificate_saved"
        private const val PEERS = "trusted_peers"
    }
}
