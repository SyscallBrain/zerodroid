package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Source: SessionNewParams — crates/zeroclaw-runtime/src/rpc/types.rs
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class SessionNewParams(
    @SerialName("agent_alias") val agentAlias: String,
    @SerialName("session_id") val sessionId: String? = null,
    val cwd: String? = null,
    @EncodeDefault @SerialName("keep_siblings") val keepSiblings: Boolean? = true,
    /** `"acp"` for a Code session: its own store, no persistent memory, no MCP, rooted at [cwd]. */
    @SerialName("chat_mode") val chatMode: String? = null,
    @SerialName("exclude_memory") val excludeMemory: Boolean? = null,
    /** Closed daemon enum; `"zerocode_code"` adds the interactive-coding context to the prompt. */
    @SerialName("interaction_surface") val interactionSurface: String? = null,
) {
    companion object {
        /** A Code session exactly as zerocode opens one, so either client can resume it. */
        fun code(agentAlias: String, cwd: String? = null, sessionId: String? = null) = SessionNewParams(
            agentAlias = agentAlias,
            sessionId = sessionId,
            cwd = cwd,
            chatMode = "acp",
            excludeMemory = true,
            interactionSurface = "zerocode_code",
        )
    }
}

// Source: FsListDirRequest — crates/zeroclaw-api/src/jsonrpc.rs
@Serializable
data class FsListDirParams(val path: String, @SerialName("show_hidden") val showHidden: Boolean? = null)

// Source: FsListDirResponse / FsEntry — crates/zeroclaw-api/src/jsonrpc.rs
@Serializable
data class FsListDirResult(val entries: List<FsEntry> = emptyList(), val cwd: String = "")

@Serializable
data class FsEntry(
    val name: String,
    @SerialName("full_path") val fullPath: String,
    @SerialName("is_dir") val isDir: Boolean,
    @SerialName("is_hidden") val isHidden: Boolean = false,
)

// Source: SessionNewResult — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class SessionNewResult(
    @SerialName("session_id") val sessionId: String,
    @SerialName("agent_alias") val agentAlias: String,
    @SerialName("message_count") val messageCount: Int,
    @SerialName("workspace_dir") val workspaceDir: String,
)

// Source: SessionListParams — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SessionListParams(
    val query: String? = null,
    val limit: Int? = null,
    val running: Boolean? = null,
)

// Source: SessionListResult — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class SessionListResult(val sessions: List<SessionEntry> = emptyList())

// Source: SessionRenameParams — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SessionRenameParams(@SerialName("session_id") val sessionId: String, val name: String)

// Source: SessionEntry — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class SessionEntry(
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_key") val sessionKey: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_activity") val lastActivity: String,
    @SerialName("message_count") val messageCount: Int,
    @SerialName("agent_alias") val agentAlias: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    val name: String? = null,
)

// Source: SessionMessagesParams — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SessionMessagesParams(
    @SerialName("session_id") val sessionId: String,
    val limit: Int? = null,
    @SerialName("before_index") val beforeIndex: Int? = null,
)

// Source: MessageEntry — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class MessageEntry(
    val role: String,
    val content: String,
    val kind: String = "message",
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_input") val toolInput: JsonElement? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_output") val toolOutput: String? = null,
)

// Source: SessionMessagesResult — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class SessionMessagesResult(
    @SerialName("session_id") val sessionId: String,
    val messages: List<MessageEntry>,
    @SerialName("has_older") val hasOlder: Boolean? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
    val start: Int = 0,
    val total: Int = 0,
)

// Source: SessionStateResult — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class SessionStateResult(
    @SerialName("session_id") val sessionId: String,
    val state: String,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("turn_started_at") val turnStartedAt: String? = null,
)

// Source: SessionPromptParams — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SessionPromptParams(
    @SerialName("session_id") val sessionId: String,
    val prompt: String,
    /** Inline files (`FileEntry`): the daemon uploads them and adds their markers to the prompt. */
    val attachments: List<PromptAttachment>? = null,
) {
    init { require(sessionId.isNotBlank() && prompt.isNotBlank()) }
}

// Source: FileEntry — docs/book/src/architecture/zeroclaw-rpc.openrpc.json (as zerocode sends in session/prompt)
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class PromptAttachment(
    val filename: String,
    @SerialName("data_b64") val dataB64: String,
    @EncodeDefault @SerialName("mime_type") val mimeType: String = "image/jpeg",
) {
    override fun toString() = "PromptAttachment(filename=$filename, ${dataB64.length} b64 chars)"
}

// Source: SessionSteerParams — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SessionSteerParams(
    @SerialName("session_id") val sessionId: String,
    val content: String,
) { init { require(sessionId.isNotBlank() && content.isNotBlank()) } }

// Source: SessionIdParams — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SessionIdParams(@SerialName("session_id") val sessionId: String)

// Source: SessionOverrides — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class SessionOverrides(
    val model: String? = null,
    @SerialName("model_provider") val modelProvider: String? = null,
    val temperature: Double? = null,
) {
    val isSet: Boolean get() = model != null || modelProvider != null || temperature != null
}

// Source: SessionConfigureParams / SessionConfigureResult
@Serializable
data class SessionConfigureParams(@SerialName("session_id") val sessionId: String, val overrides: SessionOverrides)

@Serializable
data class SessionConfigureResult(@SerialName("session_id") val sessionId: String = "", val overrides: SessionOverrides = SessionOverrides())

// Source: CatalogModelsResult
@Serializable
data class CatalogModelsResult(@SerialName("model_provider") val modelProvider: String = "", val models: List<String> = emptyList())

// Source: SessionApproveParams — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SessionApproveParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("request_id") val requestId: String,
    val decision: String,
)

// Source: handle_session_approve — crates/zeroclaw-runtime/src/rpc/dispatch.rs
object ApprovalDecision {
    const val APPROVE = "allow_once"
    const val ALWAYS = "allow_always"
    const val DENY = "reject_once"
}
