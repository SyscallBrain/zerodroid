package pt.tiago.zerodroid.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.cron.CronRepository
import pt.tiago.zerodroid.data.cron.CronResult
import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.rpc.proto.CronPatchParams
import pt.tiago.zerodroid.rpc.proto.CronRun
import pt.tiago.zerodroid.rpc.proto.CronTriggerResult

const val DECLARATIVE_NOTE = "Defined in config.toml — edit it there."
const val NEEDS_ADMIN_NOTE = "Agent jobs need admin access for this device."
const val NEEDS_UPDATE_CHANGE = "The daemon needs the automation update to change this."

/** Outlives screens: a manual run keeps going (and its result lands) after Back. */
internal val automationRunScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

data class AutomationDetailState(
    val job: CronJob? = null,
    val runs: List<CronRun> = emptyList(),
    val running: Boolean = false,
    val lastTrigger: CronTriggerResult? = null,
    /** Non-null: edits are hidden and this note explains why. */
    val readOnlyReason: String? = null,
    val error: String? = null,
    val deleted: Boolean = false,
    val canLoadMore: Boolean = false,
    val busy: Boolean = false,
)

class AutomationDetailViewModel(
    private val id: String,
    private val repo: CronRepository = ZeroDroidConnection.crons,
    private val scopeOverride: CoroutineScope? = null,
    private val launchScope: CoroutineScope = automationRunScope,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(AutomationDetailState())
    val state: StateFlow<AutomationDetailState> = _state.asStateFlow()
    private var limit = FIRST_PAGE

    fun refresh() {
        scope.launch {
            when (val r = repo.get(id)) {
                is CronResult.Ok -> setJob(r.value)
                CronResult.Forbidden -> _state.update { it.copy(error = "This device can't read this job.") }
                is CronResult.Lost -> _state.update { it.copy(error = r.message) }
                is CronResult.Error -> _state.update { it.copy(error = r.message) }
            }
            loadRuns()
        }
    }

    fun loadMore() {
        if (!_state.value.canLoadMore) return
        limit = PAGES.firstOrNull { it > limit } ?: return
        scope.launch { loadRuns() }
    }

    private suspend fun loadRuns() {
        val r = repo.runs(id, limit)
        if (r is CronResult.Ok) _state.update { it.copy(runs = r.value, canLoadMore = r.value.size >= limit && limit < PAGES.last()) }
    }

    fun runNow() {
        if (_state.value.running) return
        _state.update { it.copy(running = true, error = null) }
        launchScope.launch {
            when (val r = repo.trigger(id)) {
                is CronResult.Ok -> _state.update { it.copy(running = false, lastTrigger = r.value) }
                CronResult.Forbidden -> forbidden()
                is CronResult.Lost -> _state.update { it.copy(running = false, error = r.message) }
                is CronResult.Error -> _state.update { it.copy(running = false, error = r.message) }
            }
            loadRuns()
            (repo.get(id) as? CronResult.Ok)?.let { setJob(it.value) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        val job = _state.value.job ?: return
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            when (val r = repo.patch(CronPatchParams(id = id, agent = job.agentAlias.orEmpty(), enabled = enabled))) {
                is CronResult.Ok ->
                    if (r.value.enabled != enabled) _state.update { it.copy(busy = false, error = NEEDS_UPDATE_CHANGE) }
                    else { setJob(r.value); _state.update { it.copy(busy = false) } }
                CronResult.Forbidden -> forbidden()
                is CronResult.Lost -> _state.update { it.copy(busy = false, error = r.message) }
                is CronResult.Error -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    fun delete() {
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            when (val r = repo.delete(id)) {
                is CronResult.Ok -> _state.update { it.copy(busy = false, deleted = true) }
                CronResult.Forbidden -> forbidden()
                is CronResult.Lost -> _state.update { it.copy(busy = false, error = r.message) }
                is CronResult.Error -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    private fun setJob(job: CronJob) = _state.update {
        it.copy(job = job, readOnlyReason = if (job.isDeclarative) DECLARATIVE_NOTE else it.readOnlyReason)
    }

    private fun forbidden() = _state.update {
        val agent = it.job?.isAgent == true
        it.copy(
            running = false, busy = false,
            readOnlyReason = if (agent) NEEDS_ADMIN_NOTE else it.readOnlyReason,
            error = if (agent) null else "This device is not allowed to do that",
        )
    }

    private companion object {
        const val FIRST_PAGE = 20
        val PAGES = listOf(20, 50, 100)
    }
}
