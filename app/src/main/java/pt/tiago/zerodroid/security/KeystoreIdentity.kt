package pt.tiago.zerodroid.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * EC P-256 signing key in AndroidKeyStore. The private key never leaves the keystore.
 *
 * The key must authorize [KeyProperties.DIGEST_NONE]: during a TLS 1.3 handshake
 * Conscrypt hashes the transcript itself and asks the keystore for a raw
 * `NONEwithECDSA` signature. A key limited to SHA-256 makes the client abort the
 * handshake ("tls handshake eof" on the daemon side).
 */
class KeystoreIdentity(private val alias: String = ALIAS) {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun getOrCreate(): KeyPair {
        val existing = store().getEntry(alias, null)
        if (existing is KeyStore.PrivateKeyEntry) {
            return KeyPair(existing.certificate.publicKey, existing.privateKey)
        }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .build()
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec) }.generateKeyPair()
    }

    fun privateKeyOrNull(): PrivateKey? =
        (store().getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.privateKey

    /** True when a key exists and can sign a TLS 1.3 handshake. */
    fun isUsableForTls(): Boolean {
        val key = privateKeyOrNull() ?: return false
        return runCatching {
            val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java)
            KeyProperties.DIGEST_NONE in info.digests
        }.getOrDefault(false)
    }

    fun delete() = store().deleteEntry(alias)

    companion object {
        const val ALIAS = "zerodroid-client"
    }
}
