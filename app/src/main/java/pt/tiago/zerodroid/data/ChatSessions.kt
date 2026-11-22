package pt.tiago.zerodroid.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import pt.tiago.zerodroid.rpc.NotConnectedException
import pt.tiago.zerodroid.rpc.RpcCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.RpcException
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.callWith
import pt.tiago.zerodroid.rpc.proto.SessionConfigureParams
import pt.tiago.zerodroid.rpc.proto.SessionConfigureResult
import pt.tiago.zerodroid.rpc.proto.SessionIdParams
import pt.tiago.zerodroid.rpc.proto.SessionOverrides
import pt.tiago.zerodroid.rpc.proto.SessionMessagesParams
import pt.tiago.zerodroid.rpc.proto.SessionMessagesResult
import pt.tiago.zerodroid.rpc.proto.SessionNewParams
import pt.tiago.zerodroid.rpc.proto.SessionPromptParams
import pt.tiago.zerodroid.rpc.proto.SessionRenameParams
import pt.tiago.zerodroid.rpc.proto.SessionStateResult
import pt.tiago.zerodroid.rpc.proto.SessionSteerParams
import pt.tiago.zerodroid.rpc.proto.SessionUpdate
import pt.tiago.zerodroid.rpc.request
import pt.tiago.zerodroid.rpc.userMessage

private const val HISTORY_PAGE = 60

/**
 * One daemon session as seen by this phone. It lives as long as the process, not the
 * screen, so a turn keeps streaming (and its approvals keep arriving) while the user
 * is elsewhere in the app or the app is in the background.
 */
class ChatSession(
    val sessionId: String,
    agentAlias: String,
    private val rpc: RpcCaller,
    private val approvals: ApprovalCenter,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keepImage: (OutgoingImage) -> Unit = {},
    /** A Code session (`chat_mode: acp`): re-attaching must keep that mode. */
    code: Boolean = false,
) {
    var agentAlias: String = agentAlias
        private set
    @Volatile var code: Boolean = code
        internal set
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()
    /** The composer's unsent text and images; kept here so leaving the chat does not lose them. */
    val draftText = MutableStateFlow("")
    val draftImages = MutableStateFlow<List<OutgoingImage>>(emptyList())
    private val _overrides = MutableStateFlow<SessionOverrides?>(null)
    /** This session's model overrides, as last reported by `session/configure`. */
    val overrides: StateFlow<SessionOverrides?> = _overrides.asStateFlow()
    private var promptJob: Job? = null
    private var loadedOnce = false

    fun setTitle(name: String?) { _title.value = name?.takeIf { it.isNotBlank() } }

    fun onUpdate(e: SessionUpdate) {
        _state.update { reduce(it, e, clock()) }
        if (e is SessionUpdate.ApprovalRequest) approvals.onEvent(sessionId, agentAlias, e)
    }

    fun onApprovalResolved(requestId: String, status: ApprovalStatus) {
        _state.update { setApprovalStatus(it, requestId, status, clock()) }
    }

    fun onConnectionLost() {
        promptJob?.cancel()
        _state.update { connectionLost(it) }
    }

    /** Loads the latest history unless a turn is streaming into the current transcript. */
    fun open() {
        if (loadedOnce && _state.value.turnRunning) return
        scope.launch {
            reload()
            refreshOverrides()
        }
    }

    suspend fun reload() {
        _loading.value = !loadedOnce
        runCatching {
            // After a daemon restart the session is dormant: attach it to this connection first.
            val page = ZdJson.decodeFromJsonElement(
                SessionMessagesResult.serializer(),
                withOwnership { rpc.callWith("session/messages", SessionMessagesParams(sessionId, limit = HISTORY_PAGE)) },
            )
            val live = runCatching {
                rpc.request<SessionIdParams, SessionStateResult>("session/state", SessionIdParams(sessionId))
            }.getOrNull()
            val rebuilt = fromHistory(page.messages, page.start, page.hasOlder ?: (page.start > 0))
            val keepNotices = _state.value.items.filterIsInstance<ChatItem.Notice>()
                .filter { it.kind == NoticeKind.TurnCancelled || it.kind == NoticeKind.TurnFailed }
                .takeLast(1)
            _state.value = rebuilt.copy(
                items = rebuilt.items + keepNotices,
                turnRunning = live?.state == "running",
                lastUserPrompt = rebuilt.lastUserPrompt ?: _state.value.lastUserPrompt,
                contextUsed = _state.value.contextUsed,
                contextMax = _state.value.contextMax,
                seq = rebuilt.seq + _state.value.seq + 1,
            )
            loadedOnce = true
        }.onFailure { t ->
            if (!loadedOnce) _state.update { addNotice(it, NoticeKind.Error, "Could not load this chat: ${t.userMessage()}") }
        }
        _loading.value = false
    }

    fun loadOlder() {
        val s = _state.value
        if (!s.hasOlder || s.historyStart <= 0) return
        scope.launch {
            runCatching {
                rpc.request<SessionMessagesParams, SessionMessagesResult>(
                    "session/messages",
                    SessionMessagesParams(sessionId, limit = HISTORY_PAGE, beforeIndex = s.historyStart),
                )
            }.onSuccess { page ->
                val older = fromHistory(page.messages, page.start, page.hasOlder ?: (page.start > 0))
                _state.update { prependHistory(it, older) }
            }
        }
    }

    fun send(text: String, images: List<OutgoingImage> = emptyList()) {
        val trimmed = text.trim().ifEmpty { if (images.isNotEmpty()) "(image)" else "" }
        if (trimmed.isEmpty()) return
        if (_state.value.turnRunning) {
            // Steering carries text only; picked images stay in the draft for the next prompt.
            draftText.value = ""
            _state.update { localPrompt(it, trimmed) }
            scope.launch {
                runCatching { withOwnership { rpc.callWith("session/steer", SessionSteerParams(sessionId, trimmed)) } }
                    .onFailure { t -> _state.update { addNotice(it, NoticeKind.Error, "Steer not delivered: ${t.userMessage()}") } }
            }
            return
        }
        val shas = images.map { it.sha256 }
        val attachments = toAttachments(images).takeIf { it.isNotEmpty() }
        draftText.value = ""
        draftImages.value = emptyList()
        _state.update { localPrompt(it, trimmed, shas) }
        promptJob = scope.launch {
            // Keep local copies off the caller's (main) thread; the prompt below does not need them.
            if (images.isNotEmpty()) launch(kotlinx.coroutines.Dispatchers.IO) { images.forEach { runCatching { keepImage(it) } } }
            runCatching {
                // The daemon answers session/prompt only when the turn ends; the turn itself streams as session/update.
                withOwnership { rpc.callWith("session/prompt", SessionPromptParams(sessionId, trimmed, attachments), timeoutMs = 0) }
            }.onFailure { t ->
                if (t is kotlinx.coroutines.CancellationException) return@onFailure
                if (t is NotConnectedException) {
                    // Same outcome as a dropped connection: the daemon cancels the turn.
                    _state.update { s -> if (s.turnRunning) connectionLost(s) else s }
                    return@onFailure
                }
                val busy = t is RpcException && t.error.code == RpcCodes.SESSION_BUSY
                _state.update {
                    addNotice(
                        it.copy(turnRunning = false), NoticeKind.Error,
                        if (busy) "This session is busy with another turn" else "Message not sent: ${t.userMessage()}",
                        retry = true,
                    )
                }
            }
            // If the reply arrived but turn_complete was lost, do not leave the composer in "running".
            if (_state.value.turnRunning) _state.update { it.copy(turnRunning = false) }
        }
    }

    fun stop() {
        scope.launch {
            runCatching { rpc.callWith("session/cancel", SessionIdParams(sessionId)) }
                .onFailure { t -> _state.update { addNotice(it, NoticeKind.Error, "Could not stop: ${t.userMessage()}") } }
        }
    }

    fun retryLast() {
        val last = _state.value.lastUserPrompt ?: return
        if (_state.value.turnRunning) return
        send(last)
    }

    /** An empty `session/configure` changes nothing and returns the current overrides. */
    suspend fun refreshOverrides() {
        // session/configure takes the daemon's config-write lock and waits for a running turn,
        // so never use it as a read while one is in flight.
        if (_state.value.turnRunning) return
        runCatching {
            rpc.request<SessionConfigureParams, SessionConfigureResult>("session/configure", SessionConfigureParams(sessionId, SessionOverrides()))
        }.onSuccess { _overrides.value = it.overrides }
    }

    /** Switch this session's provider (and optionally model). Only the given fields are sent. */
    suspend fun configureModel(provider: String, model: String?): Result<Unit> {
        if (_state.value.turnRunning) return Result.failure(IllegalStateException("Wait for the current turn to finish"))
        return runCatching {
            val r = ZdJson.decodeFromJsonElement(
                SessionConfigureResult.serializer(),
                withOwnership {
                    rpc.callWith("session/configure", SessionConfigureParams(sessionId, SessionOverrides(model = model, modelProvider = provider)))
                },
            )
            _overrides.value = r.overrides
        }
    }

    fun rename(name: String) {
        scope.launch {
            runCatching { rpc.callWith("session/rename", SessionRenameParams(sessionId, name)) }
                .onSuccess { setTitle(name) }
                .onFailure { t -> _state.update { addNotice(it, NoticeKind.Error, "Rename failed: ${t.userMessage()}") } }
        }
    }

    /** Re-attaches the session to this connection when the daemon says it lost track of it. */
    private suspend fun withOwnership(block: suspend () -> JsonElement): JsonElement = try {
        block()
    } catch (e: RpcException) {
        if (e.error.code != RpcCodes.SESSION_NOT_FOUND && e.error.code != RpcCodes.SESSION_NOT_OWNED) throw e
        // A plain re-attach of a Code session would ask the daemon for a chat under the same id.
        val params = if (code) SessionNewParams.code(agentAlias, sessionId = sessionId) else SessionNewParams(agentAlias = agentAlias, sessionId = sessionId)
        rpc.callWith("session/new", params)
        block()
    }
}

/** Process-wide registry of open chat sessions; routes `session/update` by `session_id`. */
class ChatSessions(
    private val rpc: RpcCaller,
    private val approvals: ApprovalCenter,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, ChatSession>()
    /** Set once the app has a files dir; sessions created afterwards keep copies of sent images. */
    @Volatile var imageCache: ImageCache? = null
    private val _running = MutableStateFlow<Map<String, String>>(emptyMap())
    /** sessionId → agent alias for every turn running on this phone. */
    val running: StateFlow<Map<String, String>> = _running.asStateFlow()

    init {
        scope.launch {
            approvals.resolutions.collect { r -> sessions[r.sessionId]?.onApprovalResolved(r.requestId, r.status) }
        }
    }

    /** Names of tools called in the chats loaded in this process (for SOP tool suggestions). */
    fun seenToolNames(): Set<String> = sessions.values.flatMapTo(mutableSetOf()) { c ->
        c.state.value.items.mapNotNull { (it as? ChatItem.Tool)?.name?.takeIf { n -> n != "tool" } }
    }

    /** [code] marks a Code session; a session already known keeps its mode unless [code] is true. */
    fun get(sessionId: String, agentAlias: String, code: Boolean = false): ChatSession = sessions.getOrPut(sessionId) {
        ChatSession(sessionId, agentAlias, rpc, approvals, scope, clock, keepImage = { img -> imageCache?.put(img) }, code = code).also { s ->
            scope.launch {
                s.state.collect { st ->
                    _running.update { m -> if (st.turnRunning) m + (sessionId to s.agentAlias) else m - sessionId }
                }
            }
        }
    }.also { if (code) it.code = true }

    fun existing(sessionId: String): ChatSession? = sessions[sessionId]

    /** Drops a deleted session from this phone (state, draft, running flag). */
    fun forget(sessionId: String) {
        sessions.remove(sessionId)
        _running.update { it - sessionId }
    }

    fun dispatch(params: JsonElement) {
        val event = runCatching { ZdJson.decodeFromJsonElement(SessionUpdate.serializer(), params) }.getOrNull() ?: return
        val id = sessionIdOf(event) ?: return
        sessions[id]?.onUpdate(event)
    }

    fun onConnectionLost() = sessions.values.forEach { it.onConnectionLost() }

    fun onReconnected() = sessions.values.forEach { s -> scope.launch { s.reload() } }

    fun stopAll() = _running.value.keys.forEach { id -> sessions[id]?.stop() }

    fun clear() {
        sessions.clear()
        _running.value = emptyMap()
    }
}

fun sessionIdOf(e: SessionUpdate): String? = when (e) {
    is SessionUpdate.AgentMessageChunk -> e.sessionId
    is SessionUpdate.AgentThoughtChunk -> e.sessionId
    is SessionUpdate.ToolCall -> e.sessionId
    is SessionUpdate.ToolResult -> e.sessionId
    is SessionUpdate.ApprovalRequest -> e.sessionId
    is SessionUpdate.ContextUsage -> e.sessionId
    is SessionUpdate.Plan -> e.sessionId
    is SessionUpdate.TurnComplete -> e.sessionId
    is SessionUpdate.BudgetWarning -> e.sessionId
    is SessionUpdate.HistoryTrimmed -> e.sessionId
    is SessionUpdate.Unknown -> (e.raw["session_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
