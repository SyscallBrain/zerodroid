package pt.tiago.zerodroid.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import pt.tiago.zerodroid.data.AppPhase
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.callAs
import pt.tiago.zerodroid.rpc.proto.AgentsStatusResult
import pt.tiago.zerodroid.rpc.proto.CostSummary
import pt.tiago.zerodroid.rpc.proto.CronListResult
import pt.tiago.zerodroid.rpc.proto.HealthResult
import pt.tiago.zerodroid.rpc.proto.StatusResult
import java.time.Instant
import java.time.ZoneId

/** Last values fetched from the daemon; survive a disconnect so Home can show them greyed out. */
private data class Fetched(
    val uptime: Long? = null,
    val cost: Double? = null,
    val sessions: Int? = null,
    val agents: List<pt.tiago.zerodroid.rpc.proto.AgentStatusEntry>? = null,
    val crons: List<pt.tiago.zerodroid.rpc.proto.CronJob>? = null,
    val runningAgents: Set<String> = emptySet(),
    val process: pt.tiago.zerodroid.rpc.proto.ProcessStats? = null,
    val components: Map<String, pt.tiago.zerodroid.rpc.proto.ComponentHealth>? = null,
    val costMonth: Double? = null,
)

class HomeViewModel(
    private val connection: ZeroDroidConnection = ZeroDroidConnection,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val fetched = MutableStateFlow(Fetched())
    private val _model = MutableStateFlow(build(ConnState.Idle, Fetched(), emptyMap(), emptyList(), null))

    fun togglePin(alias: String) {
        viewModelScope.launch { connection.pins.toggle(alias) }
    }
    val model: StateFlow<HomeModel> = _model.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private var poll: Job? = null

    init {
        viewModelScope.launch {
            combine(connection.state, fetched, connection.chats.running, connection.approvals.pending, connection.pins.pins) { s, f, running, pending, pins ->
                build(s, f, running, pending.map { it.agentAlias to it.toolName }, _model.value, pins)
            }.collect { _model.value = it }
        }
        viewModelScope.launch {
            connection.state.collect { if (it is ConnState.Ready && poll?.isActive == true) refresh() }
        }
    }

    private fun build(
        s: ConnState,
        f: Fetched,
        running: Map<String, String>,
        approvals: List<Pair<String, String>>,
        previous: HomeModel?,
        pins: List<String> = emptyList(),
    ): HomeModel {
        val profile = (connection.phase.value as? AppPhase.Paired)?.profile
        val inputs = HomeInputs(
            connState = s,
            host = profile?.host ?: "127.0.0.1",
            daemonName = profile?.name ?: "daemon",
            uptimeSeconds = f.uptime,
            costToday = f.cost,
            costLimit = null,
            activeSessions = f.sessions,
            agents = f.agents,
            crons = f.crons,
            pendingApprovals = approvals.size,
            approvalAgents = approvals,
            workingAgents = f.runningAgents + running.values,
            process = f.process,
            components = f.components,
            costMonth = f.costMonth,
            pins = pins,
        )
        val m = buildHomeModel(inputs, clock(), zone, previous)
        return if (s !is ConnState.Ready && m.daemonVersion == null) m.copy(daemonVersion = connection.lastServerVersion) else m
    }

    private val _workflows = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    /** Saved workflows (SOPs) on the daemon, for the Home tile; null until known. */
    val workflows: kotlinx.coroutines.flow.StateFlow<Int?> = _workflows

    fun onVisible() {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(15_000)
            }
        }
    }

    fun onHidden() {
        poll?.cancel()
        poll = null
    }

    fun pullToRefresh() {
        if (connection.state.value !is ConnState.Ready) {
            connection.retryNow()
            return
        }
        viewModelScope.launch {
            _refreshing.value = true
            refresh()
            _refreshing.value = false
        }
    }

    /**
     * Each call updates the model as soon as it answers, so fast ones (status, health) show
     * without waiting for the slowest (cost, cron). A failed call keeps the last value.
     */
    private suspend fun refresh() {
        if (connection.state.value !is ConnState.Ready) return
        val rpc = connection.rpc
        coroutineScope {
            launch {
                (connection.sops.list() as? pt.tiago.zerodroid.data.sop.SopResult.Ok)?.let { _workflows.value = it.value.size }
            }
            launch {
                runCatching { rpc.callAs<StatusResult>("status") }.getOrNull()?.let { r ->
                    fetched.update { it.copy(sessions = r.activeSessions) }
                }
            }
            launch {
                runCatching { rpc.callAs<HealthResult>("health") }.getOrNull()?.let { r ->
                    fetched.update {
                        it.copy(
                            uptime = r.uptimeSeconds ?: it.uptime,
                            process = r.process ?: it.process,
                            components = r.components.takeIf { c -> c.isNotEmpty() } ?: it.components,
                        )
                    }
                }
            }
            launch {
                runCatching { rpc.callAs<AgentsStatusResult>("agents/status") }.getOrNull()?.let { r ->
                    fetched.update { it.copy(agents = r.agents) }
                }
            }
            launch {
                runCatching { rpc.callAs<CronListResult>("cron/list") }.getOrNull()?.let { r ->
                    fetched.update { it.copy(crons = r.jobs) }
                }
            }
            launch {
                runCatching { rpc.callAs<CostSummary>("cost/query", JsonObject(emptyMap())) }.getOrNull()?.let { r ->
                    fetched.update { it.copy(cost = r.dailyCostUsd, costMonth = r.monthlyCostUsd) }
                }
            }
            launch {
                runCatching {
                    rpc.callAs<pt.tiago.zerodroid.rpc.proto.SessionListResult>(
                        "session/list",
                        pt.tiago.zerodroid.rpc.ZdJson.encodeToJsonElement(
                            pt.tiago.zerodroid.rpc.proto.SessionListParams.serializer(),
                            pt.tiago.zerodroid.rpc.proto.SessionListParams(running = true, limit = 100),
                        ),
                    ).sessions.mapNotNull { it.agentAlias }.toSet()
                }.getOrNull()?.let { r -> fetched.update { it.copy(runningAgents = r) } }
            }
        }
    }
}
