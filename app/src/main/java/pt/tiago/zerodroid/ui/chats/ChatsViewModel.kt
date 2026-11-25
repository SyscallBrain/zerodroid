package pt.tiago.zerodroid.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.proto.AgentEntry
import pt.tiago.zerodroid.rpc.proto.SessionEntry
import pt.tiago.zerodroid.rpc.userMessage
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SessionRow(
    val id: String,
    val title: String,
    val alias: String,
    val meta: String,
    val running: Boolean,
    val time: String = "",
)

data class ChatsModel(
    val loading: Boolean = true,
    val sessions: List<SessionRow> = emptyList(),
    val agents: List<AgentEntry> = emptyList(),
    val agentsLoading: Boolean = false,
    val query: String = "",
    val error: String? = null,
    val takeover: SessionRow? = null,
    val creating: String? = null,
    /** Chat whose long-press menu (rename, delete) is open. */
    val menu: SessionRow? = null,
    /** Chat waiting for the user to confirm deletion. */
    val deleting: SessionRow? = null,
    /** Chat waiting for the user to type a new name. */
    val renaming: SessionRow? = null,
    /** Ids being deleted on the daemon right now. */
    val removing: Set<String> = emptySet(),
)

/** "now", "5m", "3h", "Tue", "12 Nov". */
fun relativeTime(iso: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val t = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val d = Duration.between(t, now)
    return when {
        d.toMinutes() < 1 -> "now"
        d.toMinutes() < 60 -> "${d.toMinutes()}m"
        d.toHours() < 24 -> "${d.toHours()}h"
        d.toDays() < 7 -> DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH).format(t.atZone(zone))
        else -> DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(t.atZone(zone))
    }
}

fun SessionEntry.toRow(running: Set<String>): SessionRow {
    val alias = agentAlias ?: "agent"
    return SessionRow(
        id = sessionId,
        title = name?.takeIf { it.isNotBlank() } ?: "Chat with $alias",
        alias = alias,
        meta = "$messageCount message${if (messageCount == 1) "" else "s"}",
        running = sessionId in running,
        time = relativeTime(lastActivity),
    ).let { it.copy(meta = listOf(alias, it.meta).joinToString(" · ")) }
}

class ChatsViewModel(private val connection: ZeroDroidConnection = ZeroDroidConnection) : ViewModel() {
    private val _model = MutableStateFlow(ChatsModel())
    val model: StateFlow<ChatsModel> = _model.asStateFlow()
    private val raw = MutableStateFlow<List<SessionEntry>>(emptyList())
    private var search: Job? = null

    init {
        viewModelScope.launch {
            connection.state.collect { if (it is ConnState.Ready) refresh() }
        }
        viewModelScope.launch {
            connection.chats.running.collect { running ->
                _model.update { m -> m.copy(sessions = raw.value.map { it.toRow(running.keys) }) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (connection.state.value !is ConnState.Ready) {
                _model.update { it.copy(loading = false) }
                return@launch
            }
            runCatching {
                // The daemon's query matches message content only; names, agents and ids are matched here.
                val q = _model.value.query
                val all = connection.sessions.list(null)
                val hits = if (q.isBlank()) emptyList() else runCatching { connection.sessions.list(q) }.getOrDefault(emptyList())
                pt.tiago.zerodroid.data.mergeSessionSearch(all, hits, q)
            }
                .onSuccess { list ->
                    raw.value = list
                    _model.update { m ->
                        m.copy(loading = false, error = null, sessions = list.map { it.toRow(connection.chats.running.value.keys) })
                    }
                }
                .onFailure { t -> _model.update { it.copy(loading = false, error = t.userMessage()) } }
        }
    }

    fun onQuery(q: String) {
        _model.update { it.copy(query = q) }
        search?.cancel()
        search = viewModelScope.launch {
            delay(300)
            refresh()
        }
    }

    fun loadAgents() {
        if (_model.value.agents.isNotEmpty()) return
        viewModelScope.launch {
            _model.update { it.copy(agentsLoading = true) }
            runCatching { connection.sessions.agents().agents.filter { it.enabled }.sortedBy { it.alias.lowercase() } }
                .onSuccess { a -> _model.update { it.copy(agents = a, agentsLoading = false) } }
                .onFailure { t -> _model.update { it.copy(agentsLoading = false, error = t.userMessage()) } }
        }
    }

    fun newSession(alias: String, onOpen: (String, String) -> Unit) {
        if (_model.value.creating != null) return
        _model.update { it.copy(creating = alias) }
        viewModelScope.launch {
            runCatching { connection.sessions.create(alias) }
                .onSuccess { r ->
                    _model.update { it.copy(creating = null) }
                    connection.chats.get(r.sessionId, r.agentAlias)
                    onOpen(r.sessionId, r.agentAlias)
                    refresh()
                }
                .onFailure { t -> _model.update { it.copy(creating = null, error = "Could not start a chat: ${t.userMessage()}") } }
        }
    }

    fun open(row: SessionRow, onOpen: (String, String) -> Unit) {
        viewModelScope.launch {
            val runningHere = connection.chats.running.value.keys
            val elsewhere = runCatching { connection.sessions.isLiveElsewhere(row.id, runningHere) }.getOrDefault(false)
            if (elsewhere) _model.update { it.copy(takeover = row) } else onOpen(row.id, row.alias)
        }
    }

    fun takeOver(onOpen: (String, String) -> Unit) {
        val row = _model.value.takeover ?: return
        _model.update { it.copy(takeover = null) }
        viewModelScope.launch {
            runCatching { connection.sessions.attach(row.id, row.alias) }
                .onSuccess { onOpen(row.id, row.alias) }
                .onFailure { t -> _model.update { it.copy(error = "Could not take over: ${t.userMessage()}") } }
        }
    }

    fun askMenu(row: SessionRow) = _model.update { it.copy(menu = row) }
    fun dismissMenu() = _model.update { it.copy(menu = null) }

    fun askRename(row: SessionRow) = _model.update { it.copy(menu = null, renaming = row) }
    fun dismissRename() = _model.update { it.copy(renaming = null) }

    /** Renames the chat on the daemon from the list, without opening it. */
    fun confirmRename(name: String) {
        val row = _model.value.renaming ?: return
        _model.update { it.copy(renaming = null) }
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { connection.sessions.rename(row.id, row.alias, trimmed) }
                .onSuccess {
                    // A chat open in this process shows the new name right away; the list refreshes from the daemon.
                    connection.chats.existing(row.id)?.setTitle(trimmed)
                    refresh()
                }
                .onFailure { t -> _model.update { it.copy(error = "Could not rename \"${row.title}\": ${t.userMessage()}") } }
        }
    }

    fun askDelete(row: SessionRow) {
        if (row.running) {
            _model.update { it.copy(menu = null, error = "${row.alias} is still working in this chat. Stop the turn before deleting it.") }
            return
        }
        _model.update { it.copy(menu = null, deleting = row) }
    }

    fun dismissDelete() = _model.update { it.copy(deleting = null) }

    fun confirmDelete() {
        val row = _model.value.deleting ?: return
        _model.update { it.copy(deleting = null, removing = it.removing + row.id) }
        viewModelScope.launch {
            runCatching { connection.sessions.delete(row.id) }
                .onSuccess {
                    connection.chats.forget(row.id)
                    raw.value = raw.value.filterNot { it.sessionId == row.id }
                    _model.update { m -> m.copy(removing = m.removing - row.id, sessions = m.sessions.filterNot { it.id == row.id }) }
                }
                .onFailure { t ->
                    _model.update { it.copy(removing = it.removing - row.id, error = "Could not delete \"${row.title}\": ${t.userMessage()}") }
                    refresh()
                }
        }
    }

    fun dismissTakeover() = _model.update { it.copy(takeover = null) }
    fun dismissError() = _model.update { it.copy(error = null) }
}
