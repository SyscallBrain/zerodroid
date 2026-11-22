package pt.tiago.zerodroid.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

private val Context.tokenDataStore by preferencesDataStore("zerodroid-token")
private val TOKEN_KEY = stringPreferencesKey("ciphertext")
private val TUI_ID_KEY = stringPreferencesKey("tui_id")
private val TUI_SIG_KEY = stringPreferencesKey("tui_sig")
/** The daemon the stored identity belongs to (its CA fingerprint); absent on identities from older builds. */
private val TUI_DAEMON_KEY = stringPreferencesKey("tui_daemon")

/** The daemon-issued connection identity that keeps session ownership across reconnects. */
data class TuiIdentity(val id: String, val sig: String) {
    override fun toString() = "TuiIdentity(id=$id, sig=***)"
}

/**
 * Secrets at rest: the gateway bearer token and the TUI identity. Each value is
 * AES-GCM encrypted with a non-exportable AndroidKeyStore key; only ciphertext
 * reaches DataStore.
 */
class TokenVault(private val context: Context) {
    private val alias = "zerodroid-token-key"

    private fun key(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(alias, null)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        return ks.getKey(alias, null)
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(blob)
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val blob = java.util.Base64.getDecoder().decode(encoded)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
        String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
    }.getOrNull()

    private suspend fun read(k: Preferences.Key<String>): String? =
        context.tokenDataStore.data.first()[k]?.let(::decrypt)

    suspend fun store(token: String) {
        val enc = encrypt(token)
        context.tokenDataStore.edit { it[TOKEN_KEY] = enc }
    }

    suspend fun load(): String? = read(TOKEN_KEY)

    /** Remembers [identity] as this phone's identity on the daemon [daemon]. */
    suspend fun storeIdentity(daemon: String, identity: TuiIdentity) {
        val id = encrypt(identity.id)
        val sig = encrypt(identity.sig)
        val owner = encrypt(daemon)
        context.tokenDataStore.edit {
            it[TUI_ID_KEY] = id
            it[TUI_SIG_KEY] = sig
            it[TUI_DAEMON_KEY] = owner
        }
    }

    /**
     * This phone's identity on [daemon], or null if the stored one belongs to another daemon.
     * One stored before daemons were recorded is taken as [daemon]'s: it was the only one.
     */
    suspend fun loadIdentity(daemon: String): TuiIdentity? {
        val prefs = context.tokenDataStore.data.first()
        val stored = prefs[TUI_DAEMON_KEY]?.let { decrypt(it) ?: return null }
        if (stored != null && stored != daemon) return null
        val id = prefs[TUI_ID_KEY]?.let(::decrypt) ?: return null
        val sig = prefs[TUI_SIG_KEY]?.let(::decrypt) ?: return null
        return TuiIdentity(id, sig)
    }

    suspend fun forgetIdentity() {
        context.tokenDataStore.edit {
            it.remove(TUI_ID_KEY)
            it.remove(TUI_SIG_KEY)
            it.remove(TUI_DAEMON_KEY)
        }
    }

    /**
     * Drops the bearer token. The TUI identity stays: it is no credential (the daemon still
     * needs a valid pairing), only what makes this phone the owner of its sessions, so pairing
     * the same daemon again finds them. [forgetIdentity] drops it.
     */
    suspend fun clear() {
        context.tokenDataStore.edit { it.remove(TOKEN_KEY) }
    }
}
