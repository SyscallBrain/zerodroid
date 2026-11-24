package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Source: InitializeParams — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class InitializeParams(
    @SerialName("auth_token") val authToken: String,
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    // camelCase on the wire: the daemon's InitializeParams renames this one field explicitly
    // (zeroclaw-rpc-proto types.rs `rename = "clientCapabilities"`); snake_case is ignored there.
    @SerialName("clientCapabilities") val clientCapabilities: JsonObject? = null,
    /** Identity from a previous `initialize`; presenting it keeps session ownership across reconnects. */
    @SerialName("tui_id") val tuiId: String? = null,
    @SerialName("tui_sig") val tuiSig: String? = null,
    /** Which daemon provider verifies [authToken]; omitted means `native`. */
    @SerialName("auth_provider") val authProvider: String? = null,
) {
    override fun toString() = "InitializeParams(authProvider=$authProvider, protocolVersion=$protocolVersion, tuiId=$tuiId)"
}

// Source: InitializeResult — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class InitializeResult(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("server_version") val serverVersion: String,
    @SerialName("server_pid") val serverPid: Int,
    @SerialName("principal_id") val principalId: String? = null,
    val capabilities: List<JsonElement> = emptyList(),
    @SerialName("tui_id") val tuiId: String? = null,
    @SerialName("tui_sig") val tuiSig: String? = null,
) {
    override fun toString() = "InitializeResult(serverVersion=$serverVersion, tuiId=$tuiId)"
}

// Source: HealthSnapshot — crates/zeroclaw-runtime/src/health/mod.rs (`health`)
@Serializable
data class HealthResult(
    @SerialName("uptime_seconds") val uptimeSeconds: Long? = null,
    val pid: Long? = null,
    val components: Map<String, ComponentHealth> = emptyMap(),
    val process: ProcessStats? = null,
)

// Source: ComponentHealth — crates/zeroclaw-runtime/src/health/mod.rs
@Serializable
data class ComponentHealth(
    val status: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("last_ok") val lastOk: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("restart_count") val restartCount: Long = 0,
)

// Source: ProcessStats — crates/zeroclaw-runtime/src/process_stats.rs
@Serializable
data class ProcessStats(
    @SerialName("rss_bytes") val rssBytes: Long = 0,
    @SerialName("system_ram_total_bytes") val systemRamTotalBytes: Long = 0,
    @SerialName("cpu_percent") val cpuPercent: Double? = null,
    @SerialName("num_cpus") val numCpus: Int = 0,
)

// Source: StatusResult — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class StatusResult(
    @SerialName("server_version") val serverVersion: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("active_sessions") val activeSessions: Int,
    @SerialName("session_ids") val sessionIds: List<String>,
)
