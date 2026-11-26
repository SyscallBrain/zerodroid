package pt.tiago.zerodroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private val KEEP_SCREEN_ON = booleanPreferencesKey("chat_keep_screen_on")
private val SHOW_REASONING = booleanPreferencesKey("chat_show_reasoning")
private val SHOW_WORKFLOWS_TILE = booleanPreferencesKey("home_show_workflows")
private val SHOW_CANVAS_TILE = booleanPreferencesKey("home_show_canvas")
private val SHOW_AUTOMATION_TILE = booleanPreferencesKey("home_show_automation")
private val SHOW_DOCTOR_TILE = booleanPreferencesKey("home_show_doctor")
private val SHOW_SKILLS_TILE = booleanPreferencesKey("home_show_skills")

/** Phone-only display preferences. */
class ScreenPrefs(private val context: Context) {
    private val _keepScreenOn = MutableStateFlow(false)
    /** While true, an open chat stops the screen from timing out. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _showReasoning = MutableStateFlow(true)
    /** Whether the transcript shows the model's reasoning (Thought rows). */
    val showReasoning: StateFlow<Boolean> = _showReasoning.asStateFlow()

    private val _showWorkflowsTile = MutableStateFlow(true)
    /** Whether Home shows the Workflows tile; people who don't use SOPs can hide it. */
    val showWorkflowsTile: StateFlow<Boolean> = _showWorkflowsTile.asStateFlow()

    private val _showCanvasTile = MutableStateFlow(true)
    /** Whether Home shows the Canvas tile. Hiding it does not stop canvases from being saved. */
    val showCanvasTile: StateFlow<Boolean> = _showCanvasTile.asStateFlow()

    private val _showAutomationTile = MutableStateFlow(true)
    /** Whether Home shows the Automation tile. Hiding it does not stop jobs from running. */
    val showAutomationTile: StateFlow<Boolean> = _showAutomationTile.asStateFlow()

    private val _showDoctorTile = MutableStateFlow(true)
    /** Whether Home shows the Doctor tile. */
    val showDoctorTile: StateFlow<Boolean> = _showDoctorTile.asStateFlow()

    private val _showSkillsTile = MutableStateFlow(true)
    /** Whether Home shows the Skills tile. Hiding it does not change the daemon's skills. */
    val showSkillsTile: StateFlow<Boolean> = _showSkillsTile.asStateFlow()

    suspend fun load() {
        val prefs = runCatching { context.uiStore.data.first() }.getOrNull()
        _keepScreenOn.value = prefs?.get(KEEP_SCREEN_ON) ?: false
        _showReasoning.value = prefs?.get(SHOW_REASONING) ?: true
        _showWorkflowsTile.value = prefs?.get(SHOW_WORKFLOWS_TILE) ?: true
        _showCanvasTile.value = prefs?.get(SHOW_CANVAS_TILE) ?: true
        _showAutomationTile.value = prefs?.get(SHOW_AUTOMATION_TILE) ?: true
        _showDoctorTile.value = prefs?.get(SHOW_DOCTOR_TILE) ?: true
        _showSkillsTile.value = prefs?.get(SHOW_SKILLS_TILE) ?: true
    }

    suspend fun setKeepScreenOn(on: Boolean) {
        _keepScreenOn.value = on
        runCatching { context.uiStore.edit { it[KEEP_SCREEN_ON] = on } }
    }

    suspend fun setShowReasoning(on: Boolean) {
        _showReasoning.value = on
        runCatching { context.uiStore.edit { it[SHOW_REASONING] = on } }
    }

    suspend fun setShowWorkflowsTile(on: Boolean) {
        _showWorkflowsTile.value = on
        runCatching { context.uiStore.edit { it[SHOW_WORKFLOWS_TILE] = on } }
    }

    suspend fun setShowCanvasTile(on: Boolean) {
        _showCanvasTile.value = on
        runCatching { context.uiStore.edit { it[SHOW_CANVAS_TILE] = on } }
    }

    suspend fun setShowDoctorTile(on: Boolean) {
        _showDoctorTile.value = on
        runCatching { context.uiStore.edit { it[SHOW_DOCTOR_TILE] = on } }
    }

    suspend fun setShowAutomationTile(on: Boolean) {
        _showAutomationTile.value = on
        runCatching { context.uiStore.edit { it[SHOW_AUTOMATION_TILE] = on } }
    }

    suspend fun setShowSkillsTile(on: Boolean) {
        _showSkillsTile.value = on
        runCatching { context.uiStore.edit { it[SHOW_SKILLS_TILE] = on } }
    }
}
