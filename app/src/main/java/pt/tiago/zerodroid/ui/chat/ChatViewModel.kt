package pt.tiago.zerodroid.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ApprovalCenter
import pt.tiago.zerodroid.data.ApprovalExpiredException
import pt.tiago.zerodroid.data.ChatSession
import pt.tiago.zerodroid.data.ChatState
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.userMessage

/** Screen adapter over a process-level [ChatSession]; leaving the screen does not stop the turn. */
class ChatViewModel(
    val session: ChatSession,
    private val approvals: ApprovalCenter = ZeroDroidConnection.approvals,
    val connection: StateFlow<ConnState> = ZeroDroidConnection.state,
) : ViewModel() {
    val sessionId: String get() = session.sessionId
    val agentAlias: String get() = session.agentAlias
    val state: StateFlow<ChatState> = session.state
    val loading: StateFlow<Boolean> = session.loading
    val title: StateFlow<String?> = session.title
    /** A Code session: rooted at [folder], no persistent memory, cannot be renamed. */
    val isCode: Boolean get() = session.code
    /** The Code session's folder, if this phone knows it. */
    val folder: StateFlow<String?> = ZeroDroidConnection.codeFolders.state
        .map { it.bySession[session.sessionId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ZeroDroidConnection.codeFolders.folderOf(session.sessionId))
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _model = MutableStateFlow<pt.tiago.zerodroid.data.ModelLabel?>(null)
    /** The model and provider answering in this chat. */
    val model: StateFlow<pt.tiago.zerodroid.data.ModelLabel?> = _model.asStateFlow()

    init {
        session.open()
        viewModelScope.launch {
            session.overrides.collect { o -> _model.value = runCatching { ZeroDroidConnection.models.label(agentAlias, o) }.getOrNull() }
        }
    }

    fun send(text: String, images: List<pt.tiago.zerodroid.data.OutgoingImage> = emptyList()) = session.send(text, images)

    suspend fun providers(): List<String> = runCatching { ZeroDroidConnection.models.providers() }.getOrDefault(emptyList())

    suspend fun models(provider: String): List<String> = runCatching { ZeroDroidConnection.models.models(provider) }.getOrDefault(emptyList())

    fun changeModel(provider: String, model: String?) {
        viewModelScope.launch {
            session.configureModel(provider, model?.takeIf { it.isNotBlank() })
                .onSuccess { _toast.value = "Model changed for this chat" }
                .onFailure { t -> _toast.value = t.message ?: "Could not change the model" }
        }
    }

    /** A new session with the same agent (and, for Code, the same folder): the clean context the daemon cannot give in place. */
    fun freshStart(onOpen: (String, String) -> Unit) {
        if (!isCode) return newChatWith(agentAlias, onOpen)
        viewModelScope.launch {
            val dir = folder.value
            runCatching { ZeroDroidConnection.sessions.createCode(agentAlias, dir) }
                .onSuccess { r ->
                    ZeroDroidConnection.codeFolders.remember(r.sessionId, r.workspaceDir.ifEmpty { dir.orEmpty() })
                    ZeroDroidConnection.chats.get(r.sessionId, r.agentAlias, code = true)
                    onOpen(r.sessionId, r.agentAlias)
                }
                .onFailure { t -> _toast.value = "Could not start a session: ${t.userMessage()}" }
        }
    }

    fun newChatWith(alias: String, onOpen: (String, String) -> Unit) {
        viewModelScope.launch {
            runCatching { ZeroDroidConnection.sessions.create(alias) }
                .onSuccess { r -> ZeroDroidConnection.chats.get(r.sessionId, r.agentAlias); onOpen(r.sessionId, r.agentAlias) }
                .onFailure { t -> _toast.value = "Could not start a chat: ${t.userMessage()}" }
        }
    }

    /** Deletes this chat on the daemon, then leaves the screen. */
    fun delete(onDeleted: () -> Unit) {
        if (state.value.turnRunning) { _toast.value = "Stop the turn before deleting this chat"; return }
        viewModelScope.launch {
            runCatching { ZeroDroidConnection.sessions.delete(sessionId) }
                .onSuccess {
                    onDeleted()
                    ZeroDroidConnection.chats.forget(sessionId)
                    if (isCode) ZeroDroidConnection.codeFolders.forget(sessionId)
                }
                .onFailure { t -> _toast.value = "Could not delete: ${t.userMessage()}" }
        }
    }

    fun showToast(text: String) { _toast.value = text }
    fun stop() = session.stop()
    fun retryLast() = session.retryLast()
    fun loadOlder() = session.loadOlder()
    fun rename(name: String) = session.rename(name)
    fun consumeToast() { _toast.value = null }

    fun decide(requestId: String, decision: String) {
        viewModelScope.launch {
            approvals.decide(requestId, decision).onFailure { t ->
                _toast.value = when (t) {
                    is ApprovalExpiredException -> "Expired, denied by the daemon"
                    else -> "Not sent: ${t.userMessage()}"
                }
            }
        }
    }
}
