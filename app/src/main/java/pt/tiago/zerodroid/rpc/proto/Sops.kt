package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Read models for the `sops/*` RPC surface. Sources (ZeroClaw master, phone branch android-master-zerodroid):
// crates/zeroclaw-sop-graph/src/lib.rs (graph), crates/zeroclaw-runtime/src/sop/graph.rs (overlay),
// crates/zeroclaw-runtime/src/sop/trigger_registry.rs (trigger sources), sop/mod.rs (validation).
// Every field has a default so a newer daemon adding or dropping optional fields never breaks decoding.
// The SOP itself is never modelled here: the editor keeps it as a raw JsonObject (see SopDraft).

@Serializable
data class SopSummaryDto(val name: String, val description: String = "", val version: String = "")

@Serializable
data class SopListDto(val sops: List<SopSummaryDto> = emptyList())

@Serializable
data class GraphPinDto(
    @SerialName("class") val pinClass: String = "flow",
    val name: String = "",
    @SerialName("data_type") val dataType: String? = null,
    val required: Boolean = false,
)

@Serializable
data class GraphNodeDto(
    val step: Int,
    val title: String = "",
    /** `step` or `trigger`. */
    val kind: String = "step",
    val subtitle: String? = null,
    @SerialName("trigger_index") val triggerIndex: Int? = null,
    val inputs: List<GraphPinDto> = emptyList(),
    val outputs: List<GraphPinDto> = emptyList(),
)

@Serializable
data class GraphWireDto(
    /** `flow` or `data`. */
    @SerialName("class") val wireClass: String = "flow",
    @SerialName("from_step") val fromStep: Int,
    @SerialName("to_step") val toStep: Int,
    /** `sequence`, `dependency`, `failure`, `switch`, `trigger`; null on data wires. */
    @SerialName("flow_role") val flowRole: String? = null,
    @SerialName("from_pin") val fromPin: String? = null,
    @SerialName("to_pin") val toPin: String? = null,
)

@Serializable
data class GraphDiagnosticDto(val severity: String = "warning", val step: Int = 0, val message: String = "")

@Serializable
data class NodePositionDto(val step: Int, val col: Int = 0, val row: Int = 0, val x: Double? = null, val y: Double? = null)

@Serializable
data class GraphLayoutDto(
    val positions: List<NodePositionDto> = emptyList(),
    val columns: Int = 0,
    val rows: Int = 0,
)

@Serializable
data class SopGraphDto(
    val nodes: List<GraphNodeDto> = emptyList(),
    val wires: List<GraphWireDto> = emptyList(),
    val diagnostics: List<GraphDiagnosticDto> = emptyList(),
    val layout: GraphLayoutDto = GraphLayoutDto(),
)

@Serializable
data class WireDraftDto(val sop: JsonObject, val graph: SopGraphDto = SopGraphDto())

@Serializable
data class ValidateDto(
    val ok: Boolean = false,
    val blocking: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class SopRunDto(
    @SerialName("run_id") val runId: String,
    @SerialName("sop_name") val sopName: String,
    val status: String = "",
    @SerialName("current_step") val currentStep: Int = 0,
    @SerialName("total_steps") val totalSteps: Int = 0,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("trigger_source") val triggerSource: String = "",
    val active: Boolean = false,
)

@Serializable
data class SopRunsDto(val runs: List<SopRunDto> = emptyList())

@Serializable
data class StepToolCallDto(
    val index: Int = 0,
    val tool: String = "",
    val args: JsonElement? = null,
    val success: Boolean = true,
    val output: String = "",
)

@Serializable
data class NodeRunOverlayDto(
    val step: Int,
    /** `pending`, `active`, `completed`, `failed`, `skipped`. */
    val state: String = "pending",
    @SerialName("tool_calls") val toolCalls: List<StepToolCallDto> = emptyList(),
)

@Serializable
data class RunOverlayDto(
    @SerialName("run_id") val runId: String = "",
    @SerialName("sop_name") val sopName: String = "",
    val status: String = "",
    @SerialName("current_step") val currentStep: Int = 0,
    @SerialName("total_steps") val totalSteps: Int = 0,
    val waiting: Boolean = false,
    val paused: Boolean = false,
    val nodes: List<NodeRunOverlayDto> = emptyList(),
)

@Serializable
data class ConditionOpSpecDto(val token: String, val label: String = "")

@Serializable
data class TriggerFieldDto(
    val name: String,
    val options: List<String> = emptyList(),
    val multi: Boolean = false,
    /** `text`, `list` or `expression`. */
    val kind: String = "text",
)

@Serializable
data class PayloadContractDto(
    val open: Boolean = false,
    val direct: Boolean = false,
    val fields: List<JsonObject> = emptyList(),
)

@Serializable
data class BoundTriggerSourceDto(
    val source: String,
    val fields: List<TriggerFieldDto> = emptyList(),
    val condition: PayloadContractDto? = null,
)

@Serializable
data class ChannelAliasDto(
    val alias: String,
    val enabled: Boolean = true,
    @SerialName("owning_agent") val owningAgent: String? = null,
)

@Serializable
data class ChannelTriggerKindDto(
    val channel: String,
    val aliases: List<ChannelAliasDto> = emptyList(),
    val configured: Boolean = false,
    @SerialName("setup_path") val setupPath: String = "",
    val condition: PayloadContractDto? = null,
)

@Serializable
data class TriggerSourceRegistryDto(
    val sources: List<String> = emptyList(),
    val bound: List<BoundTriggerSourceDto> = emptyList(),
    val channels: List<ChannelTriggerKindDto> = emptyList(),
    val operators: List<ConditionOpSpecDto> = emptyList(),
)
