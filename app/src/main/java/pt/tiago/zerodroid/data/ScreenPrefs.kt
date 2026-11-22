package pt.tiago.zerodroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private val KEEP_SCREEN_ON = booleanPreferencesKey("chat_keep_screen_on")
private val SHOW_WORKFLOWS_TILE = booleanPreferencesKey("home_show_workflows")
private val SHOW_CANVAS_TILE = booleanPreferencesKey("home_show_canvas")

/** Phone-only display preferences. */
class ScreenPrefs(private val context: Context) {
    private val _keepScreenOn = MutableStateFlow(false)
    /** While true, an open chat stops the screen from timing out. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _showWorkflowsTile = MutableStateFlow(true)
    /** Whether Home shows the Workflows tile; people who don't use SOPs can hide it. */
    val showWorkflowsTile: StateFlow<Boolean> = _showWorkflowsTile.asStateFlow()

    private val _showCanvasTile = MutableStateFlow(true)
    /** Whether Home shows the Canvas tile. Hiding it does not stop canvases from being saved. */
    val showCanvasTile: StateFlow<Boolean> = _showCanvasTile.asStateFlow()

    suspend fun load() {
        val prefs = runCatching { context.uiStore.data.first() }.getOrNull()
        _keepScreenOn.value = prefs?.get(KEEP_SCREEN_ON) ?: false
        _showWorkflowsTile.value = prefs?.get(SHOW_WORKFLOWS_TILE) ?: true
        _showCanvasTile.value = prefs?.get(SHOW_CANVAS_TILE) ?: true
    }

    suspend fun setKeepScreenOn(on: Boolean) {
        _keepScreenOn.value = on
        runCatching { context.uiStore.edit { it[KEEP_SCREEN_ON] = on } }
    }

    suspend fun setShowWorkflowsTile(on: Boolean) {
        _showWorkflowsTile.value = on
        runCatching { context.uiStore.edit { it[SHOW_WORKFLOWS_TILE] = on } }
    }

    suspend fun setShowCanvasTile(on: Boolean) {
        _showCanvasTile.value = on
        runCatching { context.uiStore.edit { it[SHOW_CANVAS_TILE] = on } }
    }
}
