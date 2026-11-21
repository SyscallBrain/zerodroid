package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stream events from session/update notifications.
 * Source: SessionUpdateEvent — docs/book/src/architecture/zeroclaw-rpc.openrpc.json
 */
@Serializable(with = SessionUpdateSerializer::class)
sealed interface SessionUpdate {
    @Serializable @SerialName("agent_message_chunk")
    data class AgentMessageChunk(@SerialName("session_id") val sessionId: String, val text: String) : SessionUpdate

    @Serializable @SerialName("agent_thought_chunk")
    data class AgentThoughtChunk(@SerialName("session_id") val sessionId: String, val text: String) : SessionUpdate

    @Serializable @SerialName("tool_call")
    data class ToolCall(
        @SerialName("session_id") val sessionId: String,
        @SerialName("tool_call_id") val toolCallId: String,
        val name: String,
        @SerialName("raw_input") val rawInput: JsonElement,
    ) : SessionUpdate

    @Serializable @SerialName("tool_result")
    data class ToolResult(
        @SerialName("session_id") val sessionId: String,
        @SerialName("tool_call_id") val toolCallId: String,
        val name: String,
        @SerialName("raw_output") val rawOutput: String,
    ) : SessionUpdate

    @Serializable @SerialName("approval_request")
    data class ApprovalRequest(
        @SerialName("session_id") val sessionId: String,
        @SerialName("request_id") val requestId: String,
        @SerialName("tool_name") val toolName: String,
        @SerialName("arguments_summary") val argumentsSummary: String,
        @SerialName("timeout_secs") val timeoutSecs: Long,
    ) : SessionUpdate

    @Serializable @SerialName("context_usage")
    data class ContextUsage(
        @SerialName("session_id") val sessionId: String,
        @SerialName("input_tokens") val inputTokens: Long? = null,
        @SerialName("max_context_tokens") val maxContextTokens: Long? = null,
    ) : SessionUpdate

    @Serializable @SerialName("plan")
    data class Plan(
        @SerialName("session_id") val sessionId: String,
        val entries: List<JsonElement>,
    ) : SessionUpdate

    @Serializable @SerialName("turn_complete")
    data class TurnComplete(
        @SerialName("session_id") val sessionId: String,
        val outcome: String,
        val content: String,
        @SerialName("message_count") val messageCount: Long? = null,
    ) : SessionUpdate

    @Serializable @SerialName("budget_warning")
    data class BudgetWarning(
        @SerialName("session_id") val sessionId: String,
        @SerialName("current_usd") val currentUsd: Double,
        @SerialName("limit_usd") val limitUsd: Double,
        val period: String,
    ) : SessionUpdate

    @Serializable @SerialName("history_trimmed")
    data class HistoryTrimmed(
        @SerialName("session_id") val sessionId: String,
        @SerialName("dropped_messages") val droppedMessages: Int,
        @SerialName("dropped_turns") val droppedTurns: Int,
        @SerialName("kept_turns") val keptTurns: Int,
        val reason: String,
    ) : SessionUpdate

    data class Unknown(val type: String, val raw: JsonObject) : SessionUpdate
}

/** Selects the concrete subtype from the wire "type" discriminator. */
object SessionUpdateSerializer : KSerializer<SessionUpdate> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SessionUpdate")

    override fun deserialize(decoder: Decoder): SessionUpdate {
        require(decoder is JsonDecoder)
        val obj = decoder.decodeJsonElement().jsonObject
        val type = obj["type"]?.jsonPrimitive?.content
        fun <T : SessionUpdate> read(strategy: DeserializationStrategy<T>): T =
            decoder.json.decodeFromJsonElement(strategy, obj)
        return when (type) {
            "agent_message_chunk" -> read(SessionUpdate.AgentMessageChunk.serializer())
            "agent_thought_chunk" -> read(SessionUpdate.AgentThoughtChunk.serializer())
            "tool_call" -> read(SessionUpdate.ToolCall.serializer())
            "tool_result" -> read(SessionUpdate.ToolResult.serializer())
            "approval_request" -> read(SessionUpdate.ApprovalRequest.serializer())
            "context_usage" -> read(SessionUpdate.ContextUsage.serializer())
            "plan" -> read(SessionUpdate.Plan.serializer())
            "turn_complete" -> read(SessionUpdate.TurnComplete.serializer())
            "budget_warning" -> read(SessionUpdate.BudgetWarning.serializer())
            "history_trimmed" -> read(SessionUpdate.HistoryTrimmed.serializer())
            else -> SessionUpdate.Unknown(type ?: "", obj)
        }
    }

    override fun serialize(encoder: Encoder, value: SessionUpdate) {
        require(encoder is JsonEncoder)
        val element: JsonElement = when (value) {
            is SessionUpdate.AgentMessageChunk -> encoder.json.encodeToJsonElement(SessionUpdate.AgentMessageChunk.serializer(), value)
            is SessionUpdate.AgentThoughtChunk -> encoder.json.encodeToJsonElement(SessionUpdate.AgentThoughtChunk.serializer(), value)
            is SessionUpdate.ToolCall -> encoder.json.encodeToJsonElement(SessionUpdate.ToolCall.serializer(), value)
            is SessionUpdate.ToolResult -> encoder.json.encodeToJsonElement(SessionUpdate.ToolResult.serializer(), value)
            is SessionUpdate.ApprovalRequest -> encoder.json.encodeToJsonElement(SessionUpdate.ApprovalRequest.serializer(), value)
            is SessionUpdate.ContextUsage -> encoder.json.encodeToJsonElement(SessionUpdate.ContextUsage.serializer(), value)
            is SessionUpdate.Plan -> encoder.json.encodeToJsonElement(SessionUpdate.Plan.serializer(), value)
            is SessionUpdate.TurnComplete -> encoder.json.encodeToJsonElement(SessionUpdate.TurnComplete.serializer(), value)
            is SessionUpdate.BudgetWarning -> encoder.json.encodeToJsonElement(SessionUpdate.BudgetWarning.serializer(), value)
            is SessionUpdate.HistoryTrimmed -> encoder.json.encodeToJsonElement(SessionUpdate.HistoryTrimmed.serializer(), value)
            is SessionUpdate.Unknown -> value.raw
        }
        encoder.encodeJsonElement(element)
    }
}
