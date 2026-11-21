package pt.tiago.zerodroid.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

interface PinPersistence {
    suspend fun load(): List<String>
    suspend fun save(p: List<String>)
}

internal val Context.uiStore by preferencesDataStore("zerodroid-ui")
private val PINNED = stringPreferencesKey("pinned_agents")

class DataStorePinPersistence(private val context: Context) : PinPersistence {
    override suspend fun load(): List<String> =
        context.uiStore.data.first()[PINNED]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    override suspend fun save(p: List<String>) {
        context.uiStore.edit { it[PINNED] = p.joinToString("\n") }
    }
}

/** Favourite agents, kept on the phone (never written to daemon config). */
class PinStore(private val persistence: PinPersistence) {
    private val _pins = MutableStateFlow<List<String>>(emptyList())
    val pins: StateFlow<List<String>> = _pins.asStateFlow()

    suspend fun load() {
        _pins.value = runCatching { persistence.load() }.getOrDefault(emptyList())
    }

    suspend fun toggle(alias: String) {
        val next = if (alias in _pins.value) _pins.value - alias else _pins.value + alias
        _pins.value = next
        runCatching { persistence.save(next) }
    }

    fun isPinned(alias: String) = alias in _pins.value
}

/** Pinned items first, in pin order; the rest keep their order. Pins naming missing items are skipped. */
fun <T> orderPinned(items: List<T>, pins: List<String>, key: (T) -> String): List<T> {
    val byKey = items.associateBy(key)
    val pinned = pins.mapNotNull { byKey[it] }
    val pinnedKeys = pinned.map(key).toSet()
    return pinned + items.filter { key(it) !in pinnedKeys }
}
