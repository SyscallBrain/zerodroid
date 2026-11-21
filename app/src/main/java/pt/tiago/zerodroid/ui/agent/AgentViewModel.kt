package pt.tiago.zerodroid.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import pt.tiago.zerodroid.data.ModelLabel
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.config.displayValue
import pt.tiago.zerodroid.rpc.callAs
import pt.tiago.zerodroid.rpc.proto.AgentStatusEntry
import pt.tiago.zerodroid.rpc.proto.AgentsStatusResult
import pt.tiago.zerodroid.rpc.proto.CostSummary
import pt.tiago.zerodroid.rpc.proto.CronListResult
import pt.tiago.zerodroid.rpc.userMessage
import pt.tiago.zerodroid.ui.home.formatUsd
import pt.tiago.zerodroid.ui.home.upNext
import java.time.Instant
import java.time.ZoneId

data class AgentDetails(
    val status: AgentStatusEntry? = null,
    val model: ModelLabel? = null,
    val riskProfile: String? = null,
    val runtimeProfile: String? = null,
    val costToday: String? = null,
    val nextJob: Pair<String, String>? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val creating: Boolean = false,
)

class AgentViewModel(val alias: String, private val connection: ZeroDroidConnection = ZeroDroidConnection) : ViewModel() {
    private val _d = MutableStateFlow(AgentDetails())
    val details: StateFlow<AgentDetails> = _d.asStateFlow()
    val pins = connection.pins.pins

    fun refresh() {
        viewModelScope.launch {
            val rpc = connection.rpc
            val status = async { runCatching { rpc.callAs<AgentsStatusResult>("agents/status").agents.firstOrNull { it.alias == alias } }.getOrNull() }
            val fields = async { runCatching { connection.config.fields("agents.$alias", refresh = true) }.getOrDefault(emptyList()) }
            val model = async { runCatching { connection.models.label(alias, null) }.getOrNull() }
            val cost = async { runCatching { rpc.callAs<CostSummary>("cost/query", JsonObject(emptyMap())) }.getOrNull() }
            val cron = async { runCatching { rpc.callAs<CronListResult>("cron/list").jobs }.getOrDefault(emptyList()) }
            val f = fields.await()
            fun field(name: String) = f.firstOrNull { it.path == "agents.$alias.$name" }?.let(::displayValue)?.takeIf { it != "—" }
            _d.value = AgentDetails(
                status = status.await(),
                model = model.await(),
                riskProfile = field("risk_profile"),
                runtimeProfile = field("runtime_profile") ?: status.await()?.runtimeProfile,
                costToday = cost.await()?.byAgent?.get(alias)?.costUsd?.let(::formatUsd),
                nextJob = upNext(cron.await().filter { it.agentAlias == alias }, Instant.now(), ZoneId.systemDefault(), 1).firstOrNull(),
                loading = false,
                error = if (status.await() == null && f.isEmpty()) "Could not load this agent" else null,
            )
        }
    }

    fun togglePin() { viewModelScope.launch { connection.pins.toggle(alias) } }

    fun newChat(onOpen: (String, String) -> Unit) {
        if (_d.value.creating) return
        _d.update { it.copy(creating = true) }
        viewModelScope.launch {
            runCatching { connection.sessions.create(alias) }
                .onSuccess { r -> connection.chats.get(r.sessionId, r.agentAlias); onOpen(r.sessionId, r.agentAlias) }
                .onFailure { t -> _d.update { it.copy(error = "Could not start a chat: ${t.userMessage()}") } }
            _d.update { it.copy(creating = false) }
        }
    }
}
