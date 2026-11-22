package pt.tiago.zerodroid.ui.sop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.sop.SopRepository
import pt.tiago.zerodroid.data.sop.SopResult
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.proto.RunOverlayDto
import pt.tiago.zerodroid.rpc.proto.SopGraphDto

data class RunState(
    val graph: SopGraphDto? = null,
    val draft: JsonObject = JsonObject(emptyMap()),
    val overlay: RunOverlayDto? = null,
    val engineOff: Boolean = false,
    /** The daemon no longer knows this run (pruned or from before a restart). */
    val gone: Boolean = false,
    val error: String? = null,
    val deciding: Boolean = false,
) {
    /** Per-step state for the graph; the step a run waits on (approval or a paused checkpoint) reads `waiting`. */
    val runStates: Map<Int, String>
        get() = overlay?.nodes?.associate { n ->
            n.step to if ((overlay.waiting || overlay.paused) && n.state == "active") "waiting" else n.state
        }.orEmpty()
}

private const val POLL_MS = 1_500L

/** Blank → null; otherwise the text if it parses as JSON. */
fun validPayload(text: String): String? {
    if (text.isBlank()) return null
    runCatching { ZdJson.parseToJsonElement(text) }.getOrElse { throw IllegalArgumentException("Input must be valid JSON") }
    return text.trim()
}

/** Live view of one run: polls `sops/run-overlay` while started and not finished. */
class SopRunViewModel(
    private val name: String,
    private val runId: String,
    private val repo: SopRepository = ZeroDroidConnection.sops,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()
    private var poll: Job? = null

    private var errorsInARow = 0

    init {
        scope.launch { loadGraph() }
        scope.launch { (repo.get(name) as? SopResult.Ok)?.let { d -> _state.update { it.copy(draft = d.value) } } }
    }

    private suspend fun loadGraph() {
        (repo.graph(name) as? SopResult.Ok)?.let { g -> _state.update { it.copy(graph = g.value) } }
    }

    fun start() {
        if (poll?.isActive == true) return
        poll = scope.launch {
            while (isActive) {
                if (!refresh()) break
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        poll?.cancel()
        poll = null
    }

    /** One poll; false when polling should end. */
    private suspend fun refresh(): Boolean = when (val r = repo.overlay(name, runId)) {
        is SopResult.Ok -> {
            errorsInARow = 0
            if (_state.value.graph == null) loadGraph() // the first load may have failed
            _state.update { it.copy(overlay = r.value, error = null) }
            !isTerminalRun(r.value.status)
        }
        SopResult.Unavailable -> { _state.update { it.copy(engineOff = true) }; false }
        SopResult.Forbidden -> { _state.update { it.copy(error = "Your sign-in can't see runs") }; false }
        is SopResult.Error -> if (r.message.contains("not found", ignoreCase = true)) {
            _state.update { it.copy(gone = true) }; false
        } else {
            // Keep trying through a blip, but not forever (e.g. the workflow was deleted meanwhile).
            errorsInARow++
            val giveUp = errorsInARow >= 3
            _state.update { it.copy(error = r.message, gone = giveUp) }
            !giveUp
        }
    }

    fun approve() = decide(true, null)
    fun deny(reason: String?) = decide(false, reason?.ifBlank { null })

    private fun decide(approve: Boolean, reason: String?) {
        if (_state.value.deciding) return
        _state.update { it.copy(deciding = true) }
        scope.launch {
            when (val r = repo.decide(name, runId, approve, reason)) {
                is SopResult.Ok -> _state.update { s -> s.copy(deciding = false, overlay = r.value ?: s.overlay) }
                is SopResult.Error -> _state.update { it.copy(deciding = false, error = r.message) }
                SopResult.Unavailable -> _state.update { it.copy(deciding = false, engineOff = true) }
                SopResult.Forbidden -> _state.update { it.copy(deciding = false, error = "Your sign-in can't decide runs") }
            }
            if (poll?.isActive != true) refresh()
        }
    }
}
