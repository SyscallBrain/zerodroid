package pt.tiago.zerodroid.data

import pt.tiago.zerodroid.rpc.RpcCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.RpcException
import pt.tiago.zerodroid.rpc.proto.AgentsListResult
import pt.tiago.zerodroid.rpc.proto.FsListDirParams
import pt.tiago.zerodroid.rpc.proto.FsListDirResult
import pt.tiago.zerodroid.rpc.proto.SessionEntry
import pt.tiago.zerodroid.rpc.proto.SessionIdParams
import pt.tiago.zerodroid.rpc.proto.SessionListParams
import pt.tiago.zerodroid.rpc.proto.SessionListResult
import pt.tiago.zerodroid.rpc.proto.SessionNewParams
import pt.tiago.zerodroid.rpc.proto.SessionNewResult
import pt.tiago.zerodroid.rpc.proto.SessionRenameParams
import pt.tiago.zerodroid.rpc.callAs
import pt.tiago.zerodroid.rpc.callWith
import pt.tiago.zerodroid.rpc.request

/**
 * Sessions visible to this phone. Over remote WSS the daemon lists only the
 * sessions owned by this client's TUI identity, so desktop and channel sessions
 * never appear here.
 */
class SessionsRepository(private val rpc: RpcCaller) {
    private val owned = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun list(query: String? = null): List<SessionEntry> =
        rpc.request<SessionListParams, SessionListResult>(
            "session/list", SessionListParams(query = query?.takeIf { it.isNotBlank() }, limit = 100),
        ).sessions.sortedByDescending { it.lastActivity }

    suspend fun running(): Set<String> =
        rpc.request<SessionListParams, SessionListResult>("session/list", SessionListParams(running = true, limit = 100))
            .sessions.map { it.sessionId }.toSet()

    suspend fun agents(): AgentsListResult = rpc.callAs("agents/list")

    suspend fun create(agentAlias: String): SessionNewResult =
        rpc.request<SessionNewParams, SessionNewResult>("session/new", SessionNewParams(agentAlias = agentAlias))
            .also { owned += it.sessionId }

    suspend fun attach(sessionId: String, agentAlias: String): SessionNewResult =
        rpc.request<SessionNewParams, SessionNewResult>(
            "session/new", SessionNewParams(agentAlias = agentAlias, sessionId = sessionId),
        ).also { owned += it.sessionId }

    /** Code sessions (`chat_mode: acp`), kept by the daemon apart from chats. */
    suspend fun listCode(): List<SessionEntry> =
        rpc.callAs<SessionListResult>("session/list-acp").sessions.sortedByDescending { it.lastActivity }

    /** A Code session rooted at [cwd]; null roots it at the agent's workspace. */
    suspend fun createCode(agentAlias: String, cwd: String?): SessionNewResult =
        rpc.request<SessionNewParams, SessionNewResult>("session/new", SessionNewParams.code(agentAlias, cwd))
            .also { owned += it.sessionId }

    suspend fun attachCode(sessionId: String, agentAlias: String): SessionNewResult =
        rpc.request<SessionNewParams, SessionNewResult>("session/new", SessionNewParams.code(agentAlias, sessionId = sessionId))
            .also { owned += it.sessionId }

    /** Folders the daemon lets this phone list under [path]; files are dropped. */
    suspend fun listDirs(path: String, showHidden: Boolean = false): FsListDirResult {
        val r = rpc.request<FsListDirParams, FsListDirResult>("fs/list_dir", FsListDirParams(path, showHidden.takeIf { it }))
        return r.copy(entries = r.entries.filter { it.isDir }.sortedBy { it.name.lowercase() })
    }

    /** Deletes the session on the daemon: its history and, if live, the running session. */
    suspend fun delete(sessionId: String) {
        rpc.callWith("session/delete", SessionIdParams(sessionId))
        owned -= sessionId
    }

    /**
     * Renames the session on the daemon, without opening it. A dormant session (after a
     * daemon restart) is re-attached first, the same way [attach] does.
     */
    suspend fun rename(sessionId: String, agentAlias: String, name: String) {
        runCatching { rpc.callWith("session/rename", SessionRenameParams(sessionId, name)) }
            .onFailure { t ->
                if (t is RpcException && (t.error.code == RpcCodes.SESSION_NOT_FOUND || t.error.code == RpcCodes.SESSION_NOT_OWNED)) {
                    attach(sessionId, agentAlias)
                    rpc.callWith("session/rename", SessionRenameParams(sessionId, name))
                } else throw t
            }
    }

    fun markOwned(sessionId: String) { owned += sessionId }

    fun isOwnedHere(sessionId: String) = sessionId in owned

    /** Running on the daemon, but not by a turn this app process started. */
    suspend fun isLiveElsewhere(sessionId: String, runningHere: Set<String>): Boolean =
        sessionId !in runningHere && sessionId in running()
}
