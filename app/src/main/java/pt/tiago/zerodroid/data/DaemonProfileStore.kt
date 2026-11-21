package pt.tiago.zerodroid.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DaemonProfile(
    val id: String,
    val name: String,
    val host: String,
    val wssPort: Int,
    val caPem: String,
    val certPem: String,
    val deviceId: String,
    val issuedAt: Long,
    val notAfter: Long,
)

private val Context.profileDataStore by preferencesDataStore("zerodroid-profiles")
private val PROFILES = stringPreferencesKey("json")

class DaemonProfileStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val profiles: Flow<List<DaemonProfile>> = context.profileDataStore.data.map { prefs ->
        val raw = prefs[PROFILES] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<DaemonProfile>>(raw) }.getOrDefault(emptyList())
    }

    val active: Flow<DaemonProfile?> = profiles.map { it.firstOrNull() }

    suspend fun save(profile: DaemonProfile) {
        context.profileDataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<DaemonProfile>>(prefs[PROFILES] ?: "[]")
            }.getOrDefault(emptyList()).toMutableList()
            current.removeAll { it.id == profile.id }
            current.add(0, profile)
            prefs[PROFILES] = json.encodeToString(current)
        }
    }

    suspend fun remove(id: String) {
        context.profileDataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString<List<DaemonProfile>>(prefs[PROFILES] ?: "[]")
            }.getOrDefault(emptyList()).filterNot { it.id == id }
            prefs[PROFILES] = json.encodeToString(current)
        }
    }

    suspend fun activeNow(): DaemonProfile? = active.first()
}
