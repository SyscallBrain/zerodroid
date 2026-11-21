package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Source: AgentEntry — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class AgentEntry(val alias: String, val enabled: Boolean, val channels: List<String>)

// Source: AgentsListResult — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class AgentsListResult(val agents: List<AgentEntry>)

// Source: AgentStatusEntry — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
@Serializable
data class AgentStatusEntry(
    val alias: String,
    val enabled: Boolean,
    @SerialName("runtime_profile") val runtimeProfile: String? = null,
    @SerialName("live_sessions") val liveSessions: Int = 0,
    @SerialName("persisted_sessions") val persistedSessions: Int = 0,
    val channels: List<String> = emptyList(),
)

// Source: AgentsStatusResult — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class AgentsStatusResult(val agents: List<AgentStatusEntry>)
