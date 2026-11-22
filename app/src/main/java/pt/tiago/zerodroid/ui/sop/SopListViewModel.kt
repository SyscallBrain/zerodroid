package pt.tiago.zerodroid.ui.sop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.sop.SopDraft
import pt.tiago.zerodroid.data.sop.SopRepository
import pt.tiago.zerodroid.data.sop.SopResult
import pt.tiago.zerodroid.rpc.proto.SopRunDto

data class SopCardModel(
    val name: String,
    val description: String,
    /** The `type` of each trigger, in order. */
    val triggers: List<String>,
    val steps: Int,
    val canRun: Boolean,
    val last: SopRunDto?,
)

data class SopListState(
    val loading: Boolean = true,
    val cards: List<SopCardModel> = emptyList(),
    val engineOff: Boolean = false,
    /** How to turn the engine on, known only while [engineOff]. */
    val engineFix: pt.tiago.zerodroid.data.sop.EngineFix? = null,
    val restarting: Boolean = false,
    val error: String? = null,
    val query: String = "",
) {
    val visible: List<SopCardModel>
        get() = if (query.isBlank()) cards else cards.filter {
            it.name.contains(query.trim(), ignoreCase = true) || it.description.contains(query.trim(), ignoreCase = true)
        }
}

class SopListViewModel(
    private val repo: SopRepository = ZeroDroidConnection.sops,
    private val engine: pt.tiago.zerodroid.data.sop.SopEngineControl = ZeroDroidConnection.sopEngine,
    /** Waits for the connection to come back after a daemon restart; true when it did. */
    private val awaitRestart: suspend () -> Boolean = {
        pt.tiago.zerodroid.data.followRestart(ZeroDroidConnection.state, ZeroDroidConnection::retryNow)
    },
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(SopListState())
    val state: StateFlow<SopListState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val list = when (val r = repo.list()) {
                is SopResult.Ok -> r.value
                SopResult.Forbidden -> return@launch _state.update { it.copy(loading = false, error = "Your sign-in can't read workflows") }
                SopResult.Unavailable -> emptyList()
                is SopResult.Error -> return@launch _state.update { it.copy(loading = false, error = r.message) }
            }
            val runs = repo.runs()
            val latest = (runs as? SopResult.Ok)?.value.orEmpty()
                .groupBy { it.sopName }
                .mapValues { (_, rs) -> rs.maxByOrNull { it.startedAt ?: "" } }
            val gate = Semaphore(4)
            val cards = list.map { s ->
                scope.async {
                    val full = gate.withPermit { (repo.get(s.name) as? SopResult.Ok)?.value }
                    SopCardModel(
                        name = s.name,
                        description = s.description,
                        triggers = full?.let { f -> SopDraft.triggers(f).mapNotNull { (it["type"] as? JsonPrimitive)?.content } }.orEmpty(),
                        steps = full?.let { SopDraft.steps(it).size } ?: 0,
                        canRun = full?.let { SopDraft.hasManualTrigger(it) } ?: false,
                        last = latest[s.name],
                    )
                }
            }.awaitAll()
            val off = runs is SopResult.Unavailable
            _state.update { it.copy(loading = false, cards = cards, engineOff = off, engineFix = if (off) engine.fix() else null, error = null) }
        }
    }

    /** Restarts the daemon so it wires the SOP engine, then reloads the list. */
    fun restartEngine() {
        if (_state.value.restarting) return
        _state.update { it.copy(restarting = true) }
        scope.launch {
            val err = engine.restartAndWait(awaitRestart)
            _state.update { it.copy(restarting = false, error = err) }
            if (err == null) refresh()
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun run(name: String, payload: String?, onStarted: (runId: String) -> Unit) {
        scope.launch {
            when (val r = repo.run(name, payload)) {
                is SopResult.Ok -> onStarted(r.value)
                SopResult.Unavailable -> _state.update { it.copy(engineOff = true, engineFix = engine.fix()) }
                SopResult.Forbidden -> _state.update { it.copy(error = "Your sign-in can't run workflows") }
                is SopResult.Error -> _state.update { it.copy(error = r.message) }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}
