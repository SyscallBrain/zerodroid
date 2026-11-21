package pt.tiago.zerodroid.security

import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

internal fun parsePemCertificates(pem: String): List<X509Certificate> {
    val factory = CertificateFactory.getInstance("X.509")
    return pem.split("-----BEGIN CERTIFICATE-----")
        .drop(1)
        .map { "-----BEGIN CERTIFICATE-----" + it.substringBefore("-----END CERTIFICATE-----") + "-----END CERTIFICATE-----\n" }
        .map { factory.generateCertificate(ByteArrayInputStream(it.toByteArray())) as X509Certificate }
}

/**
 * Presents one fixed identity: the AndroidKeyStore private key plus the
 * certificate the daemon issued for it. The key object is handed to Conscrypt
 * as-is, so it is never copied out of the keystore.
 */
internal class FixedKeyManager(
    private val alias: String,
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
    override fun getCertificateChain(alias: String?) = if (alias == this.alias) chain else null
    override fun getPrivateKey(alias: String?) = if (alias == this.alias) key else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}

object TlsFactory {
    fun pinnedTrustManager(caPem: String): X509TrustManager {
        val certificates = parsePemCertificates(caPem)
        require(certificates.isNotEmpty()) { "no CA certificate in PEM" }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { i, cert -> setCertificateEntry("ca-$i", cert) }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Keystore private key paired with the server-issued certificate chain. */
    fun clientKeyManager(alias: String, certChainPem: String): X509KeyManager {
        val chain = parsePemCertificates(certChainPem).toTypedArray()
        require(chain.isNotEmpty()) { "no client certificate in PEM" }
        val key = KeystoreIdentity(alias).privateKeyOrNull()
            ?: throw IllegalStateException("client key missing from the keystore")
        return FixedKeyManager(alias, key, chain)
    }

    fun tls13Context(km: X509KeyManager?, tm: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLSv1.3").apply {
            init(km?.let { arrayOf(it) }, arrayOf(tm), null)
        }
}
