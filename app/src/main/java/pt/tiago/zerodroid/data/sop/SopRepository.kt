package pt.tiago.zerodroid.data.sop

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import pt.tiago.zerodroid.rpc.RpcCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.RpcException
import pt.tiago.zerodroid.rpc.decodeOffMain
import pt.tiago.zerodroid.rpc.proto.RunOverlayDto
import pt.tiago.zerodroid.rpc.proto.SopGraphDto
import pt.tiago.zerodroid.rpc.proto.SopListDto
import pt.tiago.zerodroid.rpc.proto.SopRunDto
import pt.tiago.zerodroid.rpc.proto.SopRunsDto
import pt.tiago.zerodroid.rpc.proto.SopSummaryDto
import pt.tiago.zerodroid.rpc.proto.TriggerSourceRegistryDto
import pt.tiago.zerodroid.rpc.proto.ValidateDto
import pt.tiago.zerodroid.rpc.proto.WireDraftDto
import pt.tiago.zerodroid.rpc.userMessage

sealed interface SopResult<out T> {
    data class Ok<T>(val value: T) : SopResult<T>
    /** The daemon's SOP engine is off (`sop.sops_dir` unset): authoring works, running does not. */
    data object Unavailable : SopResult<Nothing>
    data object Forbidden : SopResult<Nothing>
    data class Error(val message: String) : SopResult<Nothing>
}

/** One edge mutation for `sops/wire-draft`. [role]: sequence, dependency, failure, switch. */
data class WireEdit(val op: String, val from: Int, val to: Int, val role: String, val port: Int? = null)

/**
 * The daemon's SOP RPC methods. Never calls `sops/rename` or `sops/run-detail`: the daemon refuses
 * both over remote WSS.
 */
class SopRepository(private val rpc: RpcCaller) {
    suspend fun list(): SopResult<List<SopSummaryDto>> = call("sops/list", null) { decode<SopListDto>(it).sops }

    suspend fun get(name: String): SopResult<JsonObject> =
        call("sops/get", named(name), saving = false) { it as? JsonObject ?: JsonObject(emptyMap()) }

    suspend fun graph(name: String): SopResult<SopGraphDto> = call("sops/graph", named(name), saving = false) { decode(it) }

    suspend fun graphDraft(sop: JsonObject): SopResult<SopGraphDto> = call("sops/graph-draft", withSop(sop)) { decode(it) }

    suspend fun wireDraft(sop: JsonObject, edit: WireEdit): SopResult<WireDraftDto> = call(
        "sops/wire-draft",
        buildJsonObject {
            put("sop", sop)
            putJsonObject("edit") {
                put("op", edit.op); put("from", edit.from); put("to", edit.to); put("role", edit.role)
                if (edit.port != null) put("port", edit.port)
            }
        },
    ) { decode(it) }

    suspend fun validate(sop: JsonObject): SopResult<ValidateDto> = call("sops/validate", withSop(sop)) { decode(it) }

    suspend fun create(sop: JsonObject): SopResult<Unit> = call("sops/create", withSop(sop)) { }

    /** An edit-save; `original_name` always equals the SOP's own name (renaming is refused remotely). */
    suspend fun save(sop: JsonObject): SopResult<Unit> = call(
        "sops/save",
        buildJsonObject { put("sop", sop); put("original_name", SopDraft.name(sop)) },
    ) { }

    suspend fun delete(name: String): SopResult<Unit> = call("sops/delete", named(name), saving = false) { }

    suspend fun triggerSources(): SopResult<TriggerSourceRegistryDto> = call("sops/trigger-sources", null) { decode(it) }

    /** Starts a manual run; returns the run id. */
    suspend fun run(name: String, payload: String?): SopResult<String> = call(
        "sops/run",
        buildJsonObject { put("name", name); if (payload != null) put("payload", payload) },
        timeoutMs = 60_000,
        saving = false,
    ) { ((it as? JsonObject)?.get("run_id") as? JsonPrimitive)?.content ?: "" }

    suspend fun runs(sop: String? = null): SopResult<List<SopRunDto>> =
        call("sops/runs", buildJsonObject { if (sop != null) put("sop", sop) }, saving = false) { decode<SopRunsDto>(it).runs }

    suspend fun overlay(name: String, runId: String): SopResult<RunOverlayDto> =
        call("sops/run-overlay", buildJsonObject { put("name", name); put("run_id", runId) }, saving = false) { decode(it) }

    /** Approve, or deny with an optional reason. Returns the refreshed overlay when the daemon sends one. */
    suspend fun decide(name: String, runId: String, approve: Boolean, reason: String? = null): SopResult<RunOverlayDto?> = call(
        "sops/decide",
        buildJsonObject {
            put("name", name); put("run_id", runId)
            if (approve) put("decision", "approve")
            else putJsonObject("decision") { putJsonObject("deny") { if (reason != null) put("reason", reason) } }
        },
        saving = false,
    ) { r -> (r as? JsonObject)?.takeIf { it.containsKey("nodes") }?.let { decode<RunOverlayDto>(it) } }

    private fun named(name: String) = buildJsonObject { put("name", name) }
    private fun withSop(sop: JsonObject) = buildJsonObject { put("sop", sop) }

    private suspend inline fun <reified R> decode(json: JsonElement): R = decodeOffMain(serializer<R>() as KSerializer<R>, json)

    private suspend fun <T> call(
        method: String,
        params: JsonElement?,
        timeoutMs: Long = 30_000,
        saving: Boolean = true,
        map: suspend (JsonElement) -> T,
    ): SopResult<T> = try {
        SopResult.Ok(map(rpc.call(method, params, timeoutMs)))
    } catch (e: RpcException) {
        when {
            e.error.code == RpcCodes.FORBIDDEN || e.error.code == RpcCodes.AUTH_REQUIRED -> SopResult.Forbidden
            e.error.message.contains("not enabled", ignoreCase = true) -> SopResult.Unavailable
            else -> SopResult.Error(sopErrorText(e, saving))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        SopResult.Error(sopErrorText(e, saving))
    }
}

/** A plain sentence for a failed SOP call. [saving]: the call wrote a definition (not-found means it was deleted elsewhere). */
fun sopErrorText(e: Throwable, saving: Boolean = true): String {
    val code = (e as? RpcException)?.error?.code
    return when (code) {
        RpcCodes.SOP_ALREADY_EXISTS -> "A workflow with this name already exists"
        RpcCodes.SOP_NOT_FOUND -> if (saving) "This workflow was deleted elsewhere. Save as new?" else "This workflow no longer exists"
        RpcCodes.FORBIDDEN, RpcCodes.AUTH_REQUIRED -> "Your sign-in can't edit workflows"
        else -> e.userMessage()
    }
}
