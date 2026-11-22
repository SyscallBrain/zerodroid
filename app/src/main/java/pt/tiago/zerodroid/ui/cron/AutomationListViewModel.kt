package pt.tiago.zerodroid.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.cron.CronRepository
import pt.tiago.zerodroid.data.cron.CronResult
import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.rpc.proto.SchedulerSettings

data class AutomationListState(
    val loading: Boolean = true,
    val jobs: List<CronJob> = emptyList(),
    val error: String? = null,
    val settings: SchedulerSettings? = null,
    val settingsForbidden: Boolean = false,
    val savingSettings: Boolean = false,
)

class AutomationListViewModel(
    private val repo: CronRepository = ZeroDroidConnection.crons,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(AutomationListState())
    val state: StateFlow<AutomationListState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            when (val r = repo.list()) {
                is CronResult.Ok -> _state.update {
                    it.copy(loading = false, error = null, jobs = r.value.sortedWith(compareBy<CronJob> { j -> !j.enabled }.thenBy { j -> j.nextRun ?: "" }))
                }
                CronResult.Forbidden -> _state.update { it.copy(loading = false, error = "This device can't read scheduled jobs.") }
                is CronResult.Lost -> _state.update { it.copy(loading = false, error = r.message) }
                is CronResult.Error -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
        scope.launch {
            when (val r = repo.settings()) {
                is CronResult.Ok -> _state.update { it.copy(settings = r.value, settingsForbidden = false) }
                CronResult.Forbidden -> _state.update { it.copy(settingsForbidden = true) }
                else -> Unit
            }
        }
    }

    fun setSettings(s: SchedulerSettings) {
        val before = _state.value.settings
        _state.update { it.copy(settings = s, savingSettings = true) }
        scope.launch {
            val err = when (val r = repo.setSettings(s)) {
                is CronResult.Ok -> null
                CronResult.Forbidden -> "This device can't change scheduler settings."
                is CronResult.Lost -> r.message
                is CronResult.Error -> r.message
            }
            _state.update { if (err == null) it.copy(savingSettings = false) else it.copy(savingSettings = false, settings = before, error = err) }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}
