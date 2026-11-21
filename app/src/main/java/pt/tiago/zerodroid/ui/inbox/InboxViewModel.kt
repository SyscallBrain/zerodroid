package pt.tiago.zerodroid.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ApprovalExpiredException
import pt.tiago.zerodroid.data.PendingApproval
import pt.tiago.zerodroid.data.SopGate
import pt.tiago.zerodroid.data.SopGatesResult
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.userMessage

data class InboxModel(
    val gates: SopGatesResult? = null,
    val deciding: Set<String> = emptySet(),
    val message: String? = null,
)

class InboxViewModel(private val connection: ZeroDroidConnection = ZeroDroidConnection) : ViewModel() {
    val pending: StateFlow<List<PendingApproval>> = connection.approvals.pending
    val recent: StateFlow<List<PendingApproval>> = connection.approvals.recent
    private val _model = MutableStateFlow(InboxModel())
    val model: StateFlow<InboxModel> = _model.asStateFlow()
    private val _gateCount = MutableStateFlow(0)
    /** SOP gates waiting, for the tab badge. */
    val gateCount: StateFlow<Int> = _gateCount.asStateFlow()
    private var poll: Job? = null

    init {
        viewModelScope.launch {
            connection.state.collect { if (it is ConnState.Ready) refreshGates() }
        }
    }

    fun onVisible() {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch {
            while (isActive) {
                refreshGates()
                delay(30_000)
            }
        }
    }

    fun onHidden() {
        poll?.cancel()
        poll = null
    }

    private suspend fun refreshGates() {
        if (connection.state.value !is ConnState.Ready) return
        val r = connection.sopGates.pending()
        _model.update { it.copy(gates = r) }
        _gateCount.value = (r as? SopGatesResult.Ok)?.gates?.size ?: 0
    }

    fun decideApproval(requestId: String, decision: String) {
        viewModelScope.launch {
            connection.approvals.decide(requestId, decision).onFailure { t ->
                _model.update {
                    it.copy(message = if (t is ApprovalExpiredException) "Expired, denied by the daemon" else "Not sent: ${t.userMessage()}")
                }
            }
        }
    }

    fun decideGate(gate: SopGate, approve: Boolean) {
        if (gate.runId in _model.value.deciding) return
        _model.update { it.copy(deciding = it.deciding + gate.runId) }
        viewModelScope.launch {
            connection.sopGates.decide(gate, approve)
                .onSuccess { _model.update { it.copy(message = "${gate.sopName}: ${if (approve) "approved" else "denied"}") } }
                .onFailure { t -> _model.update { it.copy(message = "${gate.sopName}: ${t.userMessage()}") } }
            _model.update { it.copy(deciding = it.deciding - gate.runId) }
            refreshGates()
        }
    }

    fun consumeMessage() = _model.update { it.copy(message = null) }
}
