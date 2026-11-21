package pt.tiago.zerodroid.ui.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.folderName
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.proto.AgentEntry
import pt.tiago.zerodroid.rpc.proto.SessionEntry
import pt.tiago.zerodroid.rpc.userMessage
import pt.tiago.zerodroid.ui.chats.relativeTime

data class CodeRow(
    val id: String,
    val alias: String,
    /** Null until this phone has opened or created the session (the daemon's list omits it). */
    val folder: String?,
    val messages: Int,
    val running: Boolean,
    val time: String,
) {
    val title: String get() = folder?.let(::folderName) ?: "Code with $alias"
}

data class CodeModel(
    val loading: Boolean = true,
    val rows: List<CodeRow> = emptyList(),
    val agents: List<AgentEntry> = emptyList(),
    val agentsLoading: Boolean = false,
    val recent: List<String> = emptyList(),
    /** Step one of New: the agent list is showing. */
    val pickingAgent: Boolean = false,
    /** Step two of New: the folder browser. */
    val browse: FolderBrowse? = null,
    /** The agent a session is being started for. */
    val creating: String? = null,
    val error: String? = null,
    val deleting: CodeRow? = null,
    val removing: Set<String> = emptySet(),
)

private const val ROOT = "/"

class CodeViewModel(private val connection: ZeroDroidConnection = ZeroDroidConnection) : ViewModel() {
    private val _model = MutableStateFlow(CodeModel())
    val model: StateFlow<CodeModel> = _model.asStateFlow()
    private val raw = MutableStateFlow<List<SessionEntry>>(emptyList())
    private val browser = FolderBrowser(viewModelScope, { path, hidden -> connection.sessions.listDirs(path, hidden) }, { it.userMessage() })

    init {
        viewModelScope.launch {
            connection.state.collect { if (it is ConnState.Ready) refresh() }
        }
        viewModelScope.launch {
            browser.state.collect { b -> _model.update { it.copy(browse = b) } }
        }
        viewModelScope.launch {
            combine(raw, connection.chats.running, connection.codeFolders.state) { list, running, folders ->
                list.map { it.toRow(running.keys, folders.bySession) } to folders.recent
            }.collect { (rows, recent) -> _model.update { it.copy(rows = rows, recent = recent) } }
        }
    }

    private fun SessionEntry.toRow(running: Set<String>, folders: Map<String, String>) = CodeRow(
        id = sessionId,
        alias = agentAlias ?: "agent",
        folder = folders[sessionId],
        messages = messageCount,
        running = sessionId in running,
        time = relativeTime(lastActivity),
    )

    fun refresh() {
        viewModelScope.launch {
            if (connection.state.value !is ConnState.Ready) {
                _model.update { it.copy(loading = false) }
                return@launch
            }
            runCatching { connection.sessions.listCode() }
                .onSuccess { list -> raw.value = list; _model.update { it.copy(loading = false, error = null) } }
                .onFailure { t -> _model.update { it.copy(loading = false, error = t.userMessage()) } }
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

    /** Step one of New: show the agent list (in the same sheet as the folders). */
    fun startNew() {
        loadAgents()
        _model.update { it.copy(pickingAgent = true) }
    }

    /** Step two of New: browse the daemon's folders for [alias], starting at the last folder used. */
    fun pickFolderFor(alias: String) {
        browser.open(alias, _model.value.recent.firstOrNull() ?: ROOT)
        // Same update for both steps, so the sheet never has a frame with neither.
        _model.update { it.copy(pickingAgent = false, browse = browser.state.value) }
    }

    fun browseTo(path: String) = browser.browseTo(path)

    fun browseUp() = browser.up()

    fun toggleHidden() = browser.toggleHidden()

    fun closeBrowse() {
        browser.close()
        _model.update { it.copy(pickingAgent = false, browse = null) }
    }

    /** Starts a Code session for the browsed agent in [folder]; null uses the agent's workspace. */
    fun start(folder: String?, onOpen: (String, String) -> Unit) {
        val b = _model.value.browse ?: return
        val alias = b.alias
        if (_model.value.creating != null) return
        if (folder != null && !b.canStart) return
        _model.update { it.copy(creating = alias) }
        viewModelScope.launch {
            runCatching { connection.sessions.createCode(alias, folder) }
                .onSuccess { r ->
                    connection.codeFolders.remember(r.sessionId, r.workspaceDir.ifEmpty { folder.orEmpty() })
                    connection.chats.get(r.sessionId, r.agentAlias, code = true)
                    browser.close()
                    _model.update { it.copy(creating = null, browse = null, pickingAgent = false) }
                    onOpen(r.sessionId, r.agentAlias)
                    refresh()
                }
                .onFailure { t -> _model.update { it.copy(creating = null, error = "Could not start: ${t.userMessage()}") } }
        }
    }

    /**
     * Opens a Code session. One this phone has not attached yet is resumed first in
     * Code mode, which also tells us its folder.
     */
    fun open(row: CodeRow, onOpen: (String, String) -> Unit) {
        viewModelScope.launch {
            if (connection.chats.existing(row.id) == null || row.folder == null) {
                runCatching { connection.sessions.attachCode(row.id, row.alias) }
                    .onSuccess { r -> if (r.workspaceDir.isNotEmpty()) connection.codeFolders.remember(r.sessionId, r.workspaceDir) }
                    .onFailure { t ->
                        _model.update { it.copy(error = "Could not open: ${t.userMessage()}") }
                        return@launch
                    }
            }
            connection.chats.get(row.id, row.alias, code = true)
            onOpen(row.id, row.alias)
        }
    }

    fun askDelete(row: CodeRow) {
        if (row.running) {
            _model.update { it.copy(error = "${row.alias} is still working here. Stop the turn before deleting it.") }
            return
        }
        _model.update { it.copy(deleting = row) }
    }

    fun dismissDelete() = _model.update { it.copy(deleting = null) }

    fun confirmDelete() {
        val row = _model.value.deleting ?: return
        _model.update { it.copy(deleting = null, removing = it.removing + row.id) }
        viewModelScope.launch {
            runCatching { connection.sessions.delete(row.id) }
                .onSuccess {
                    connection.chats.forget(row.id)
                    connection.codeFolders.forget(row.id)
                    raw.value = raw.value.filterNot { it.sessionId == row.id }
                    _model.update { m -> m.copy(removing = m.removing - row.id) }
                }
                .onFailure { t ->
                    _model.update { it.copy(removing = it.removing - row.id, error = "Could not delete \"${row.title}\": ${t.userMessage()}") }
                    refresh()
                }
        }
    }

    fun dismissError() = _model.update { it.copy(error = null) }
}
