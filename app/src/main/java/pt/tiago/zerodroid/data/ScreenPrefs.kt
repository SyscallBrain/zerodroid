package pt.tiago.zerodroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private val KEEP_SCREEN_ON = booleanPreferencesKey("chat_keep_screen_on")

/** Phone-only display preferences for the chat screen. */
class ScreenPrefs(private val context: Context) {
    private val _keepScreenOn = MutableStateFlow(false)
    /** While true, an open chat stops the screen from timing out. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    suspend fun load() {
        _keepScreenOn.value = runCatching { context.uiStore.data.first()[KEEP_SCREEN_ON] }.getOrNull() ?: false
    }

    suspend fun setKeepScreenOn(on: Boolean) {
        _keepScreenOn.value = on
        runCatching { context.uiStore.edit { it[KEEP_SCREEN_ON] = on } }
    }
}
