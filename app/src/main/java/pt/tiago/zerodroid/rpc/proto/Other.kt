package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Source: CostSummary — crates/zeroclaw-config/src/cost/types.rs (MVP subset, lenient)
@Serializable
data class CostSummary(
    @SerialName("session_cost_usd") val sessionCostUsd: Double = 0.0,
    @SerialName("daily_cost_usd") val dailyCostUsd: Double = 0.0,
    @SerialName("monthly_cost_usd") val monthlyCostUsd: Double = 0.0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("request_count") val requestCount: Int = 0,
    @SerialName("by_agent") val byAgent: Map<String, AgentCost> = emptyMap(),
)

// Source: AgentCostStats — crates/zeroclaw-config/src/cost/types.rs
@Serializable
data class AgentCost(
    @SerialName("cost_usd") val costUsd: Double = 0.0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
)

// Source: SopRunSummary — crates/zeroclaw-runtime/src/sop/types.rs (from sops/runs)
@Serializable
data class SopRun(
    @SerialName("run_id") val runId: String,
    @SerialName("sop_name") val sopName: String,
    val status: String,
    @SerialName("current_step") val currentStep: Int = 0,
    @SerialName("total_steps") val totalSteps: Int = 0,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("trigger_source") val triggerSource: String? = null,
    val active: Boolean = false,
)

// Source: SopDecideRequest — crates/zeroclaw-runtime/src/rpc/types.rs
@Serializable
data class SopDecideParams(val name: String, @SerialName("run_id") val runId: String, val decision: kotlinx.serialization.json.JsonElement)
