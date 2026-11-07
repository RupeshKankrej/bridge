package dev.bridge.android

import java.net.Socket
import java.security.Principal
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** Presents the Keystore identity and core's certificate in TLS, as client (control, files) and server (files). */
class IdentityKeyManager(private val keyStore: AndroidKeyStorePort) : X509ExtendedKeyManager() {
    private val alias = AndroidKeyStorePort.ALIAS

    override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = alias

    override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?) = alias

    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(alias)

    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?) = alias

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<Principal>?, engine: SSLEngine?) = alias

    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(alias)

    override fun getCertificateChain(alias: String?) = arrayOf(keyStore.certificate())

    override fun getPrivateKey(alias: String?) = keyStore.privateKey()
}
