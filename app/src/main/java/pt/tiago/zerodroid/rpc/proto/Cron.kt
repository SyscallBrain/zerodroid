package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Source: Schedule — crates/zeroclaw-runtime/src/cron/types.rs (tagged by `kind`)
@Serializable
data class CronSchedule(
    val kind: String = "cron",
    val expr: String? = null,
    val tz: String? = null,
    val at: String? = null,
    @SerialName("every_ms") val everyMs: Long? = null,
)

// Source: DeliveryConfig — crates/zeroclaw-runtime/src/cron/types.rs
// The daemon defaults a missing `mode` to "" (rejected), so mode and best_effort are always encoded.
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class CronDelivery(
    @kotlinx.serialization.EncodeDefault val mode: String = "none",
    val channel: String? = null,
    val to: String? = null,
    @kotlinx.serialization.EncodeDefault @SerialName("best_effort") val bestEffort: Boolean = true,
    /** Thread or topic inside [channel]; set by agents, kept across edits. */
    @SerialName("thread_id") val threadId: String? = null,
)

// Source: CronJob — crates/zeroclaw-runtime/src/cron/types.rs (lenient)
@Serializable
data class CronJob(
    val id: String,
    val expression: String = "",
    val schedule: CronSchedule? = null,
    val command: String = "",
    val name: String? = null,
    val prompt: String? = null,
    @SerialName("job_type") val jobType: String? = null,
    @SerialName("session_target") val sessionTarget: String = "isolated",
    val model: String? = null,
    @SerialName("agent_alias") val agentAlias: String? = null,
    val enabled: Boolean = true,
    val delivery: CronDelivery = CronDelivery(),
    @SerialName("allowed_tools") val allowedTools: List<String>? = null,
    @SerialName("uses_memory") val usesMemory: Boolean = true,
    val source: String = "imperative",
    /** RFC 3339 UTC timestamp of the next scheduled run. */
    @SerialName("next_run") val nextRun: String? = null,
    @SerialName("last_run") val lastRun: String? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_output") val lastOutput: String? = null,
) {
    val isAgent: Boolean get() = jobType == "agent"
    /** Defined in config.toml: the daemon rewrites it at every start, so the app never edits it. */
    val isDeclarative: Boolean get() = source == "declarative"
}

// Source: CronListResult — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class CronListResult(val jobs: List<CronJob>)

// Source: CronRun — crates/zeroclaw-runtime/src/cron/types.rs
@Serializable
data class CronRun(
    val id: Long,
    @SerialName("job_id") val jobId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String,
    val status: String,
    val output: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
)

@Serializable
data class CronRunsResult(val runs: List<CronRun>)

// Source: CronTriggerResult — crates/zeroclaw-rpc-proto/src/types.rs
@Serializable
data class CronTriggerResult(
    val id: String,
    val success: Boolean,
    val status: String,
    val output: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("finished_at") val finishedAt: String = "",
)

// Source: SchedulerConfig — crates/zeroclaw-config/src/schema.rs (the fields the app edits)
@Serializable
data class SchedulerSettings(
    val enabled: Boolean = true,
    @SerialName("catch_up_on_startup") val catchUpOnStartup: Boolean = true,
    @SerialName("max_run_history") val maxRunHistory: Int = 50,
)

/** `cron/add`. Optional fields are null by default: ZdJson never encodes defaults, so a set value is always sent. */
@Serializable
data class CronAddParams(
    val agent: String,
    val schedule: String,
    val tz: String? = null,
    val name: String? = null,
    @SerialName("job_type") val jobType: String? = null,
    val command: String? = null,
    val prompt: String? = null,
    val model: String? = null,
    @SerialName("session_target") val sessionTarget: String? = null,
    @SerialName("allowed_tools") val allowedTools: List<String>? = null,
    @SerialName("uses_memory") val usesMemory: Boolean? = null,
    val delivery: CronDelivery? = null,
)

/** `cron/patch`. Same null-default rule as [CronAddParams]; `clear_*` flags empty a field. */
@Serializable
data class CronPatchParams(
    val id: String,
    val agent: String,
    val name: String? = null,
    val schedule: String? = null,
    val tz: String? = null,
    @SerialName("clear_tz") val clearTz: Boolean? = null,
    val command: String? = null,
    val prompt: String? = null,
    val enabled: Boolean? = null,
    val delivery: CronDelivery? = null,
    val model: String? = null,
    @SerialName("clear_model") val clearModel: Boolean? = null,
    @SerialName("session_target") val sessionTarget: String? = null,
    @SerialName("allowed_tools") val allowedTools: List<String>? = null,
    @SerialName("clear_allowed_tools") val clearAllowedTools: Boolean? = null,
    @SerialName("uses_memory") val usesMemory: Boolean? = null,
)
