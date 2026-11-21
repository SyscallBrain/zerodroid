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

    suspend fun storeIdentity(identity: TuiIdentity) {
        val id = encrypt(identity.id)
        val sig = encrypt(identity.sig)
        context.tokenDataStore.edit {
            it[TUI_ID_KEY] = id
            it[TUI_SIG_KEY] = sig
        }
    }

    suspend fun loadIdentity(): TuiIdentity? {
        val id = read(TUI_ID_KEY) ?: return null
        val sig = read(TUI_SIG_KEY) ?: return null
        return TuiIdentity(id, sig)
    }

    suspend fun clear() {
        context.tokenDataStore.edit {
            it.remove(TOKEN_KEY)
            it.remove(TUI_ID_KEY)
            it.remove(TUI_SIG_KEY)
        }
    }
}
