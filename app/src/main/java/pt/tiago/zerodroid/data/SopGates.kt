package pt.tiago.zerodroid.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import pt.tiago.zerodroid.rpc.RpcCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.RpcException
import pt.tiago.zerodroid.rpc.userMessage

data class SopGate(val sopName: String, val runId: String, val stepLabel: String, val since: String?, val checkpoint: Boolean)

sealed interface SopGatesResult {
    data class Ok(val gates: List<SopGate>) : SopGatesResult
    data object Forbidden : SopGatesResult
    data object Unavailable : SopGatesResult
    data class Error(val message: String) : SopGatesResult
}

/** Waiting statuses from `SopRunStatus` (crates/zeroclaw-runtime/src/sop/types.rs, snake_case). */
private val WAITING = setOf("waiting_approval", "paused_checkpoint")

/** Parses the `sops/runs` result. A malformed run is skipped; it never fails the list. */
fun parseSopGates(result: JsonElement): List<SopGate> {
    val runs = (result as? JsonObject)?.get("runs") as? JsonArray ?: return emptyList()
    return runs.mapNotNull { r ->
        val obj = r as? JsonObject ?: return@mapNotNull null
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val status = str("status") ?: return@mapNotNull null
        if (status !in WAITING) return@mapNotNull null
        val name = str("sop_name") ?: return@mapNotNull null
        val runId = str("run_id") ?: return@mapNotNull null
        val step = (obj["current_step"] as? JsonPrimitive)?.content?.toIntOrNull()
        val total = (obj["total_steps"] as? JsonPrimitive)?.content?.toIntOrNull()
        SopGate(
            sopName = name,
            runId = runId,
            stepLabel = when {
                step != null && total != null && total > 0 -> "Step $step of $total"
                step != null -> "Step $step"
                else -> "Waiting"
            },
            since = str("started_at"),
            checkpoint = status == "paused_checkpoint",
        )
    }
}

class SopGates(private val rpc: RpcCaller) {
    suspend fun pending(): SopGatesResult = try {
        SopGatesResult.Ok(parseSopGates(rpc.call("sops/runs", JsonObject(emptyMap()), 30_000)))
    } catch (e: RpcException) {
        when {
            e.error.code == RpcCodes.FORBIDDEN || e.error.code == RpcCodes.AUTH_REQUIRED -> SopGatesResult.Forbidden
            e.error.message.contains("not enabled", ignoreCase = true) -> SopGatesResult.Unavailable
            else -> SopGatesResult.Error(e.userMessage())
        }
    } catch (e: Exception) {
        SopGatesResult.Error(e.userMessage())
    }

    suspend fun decide(gate: SopGate, approve: Boolean): Result<Unit> = runCatching {
        val params = buildJsonObject {
            put("name", gate.sopName)
            put("run_id", gate.runId)
            if (approve) put("decision", "approve") else putJsonObject("decision") { putJsonObject("deny") {} }
        }
        rpc.call("sops/decide", params, 30_000).let { }
    }
}
