package pt.tiago.zerodroid.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Shared JSON configuration — tolerant of newer daemons. */
val ZdJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

/** A parsed inbound frame. */
sealed interface Inbound {
    data class Response(val id: Long, val result: JsonElement) : Inbound
    data class Failure(val id: Long?, val error: RpcError) : Inbound
    data class Notification(val method: String, val params: JsonElement) : Inbound
    /**
     * A request the daemon sent us (e.g. `elicitation/create`). [id] is kept raw — the
     * daemon uses string ids here — and must be echoed verbatim in the response.
     */
    data class Request(val id: JsonElement, val method: String, val params: JsonElement) : Inbound
}

/** JSON-RPC 2.0 error object. */
data class RpcError(val code: Int, val message: String, val data: JsonElement? = null)

/** Error codes from zeroclaw-rpc.openrpc.json x-error-codes. */
object RpcCodes {
    const val SESSION_NOT_FOUND = -32000
    const val SESSION_LIMIT_REACHED = -32001
    const val SESSION_BUSY = -32002
    const val SESSION_NOT_OWNED = -32003
    const val AUTH_REQUIRED = -32010
    const val VERSION_MISMATCH = -32011
    const val FORBIDDEN = -32012
    const val SOP_ALREADY_EXISTS = -32020
    const val SOP_NOT_FOUND = -32021
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val PARSE_ERROR = -32700
    const val FS_NOT_FOUND = 4001
    const val FS_PERMISSION_DENIED = 4002
    const val FS_INVALID_PATH = 4003
}

/** Encodes a request. Omits params when null. */
fun encodeRequest(id: Long, method: String, params: JsonElement?): String {
    val obj = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null && params != JsonNull) {
            put("params", params)
        }
    }
    return ZdJson.encodeToString(JsonObject.serializer(), obj)
}

/** Decodes one frame. Returns null for garbage — never throws. */
fun decodeInbound(frame: String): Inbound? {
    val obj = try {
        ZdJson.parseToJsonElement(frame).jsonObject
    } catch (e: Exception) {
        return null
    }
    val version = obj["jsonrpc"]?.run { this as? kotlinx.serialization.json.JsonPrimitive }?.content
    if (version != "2.0") return null

    val hasId = obj.containsKey("id")
    val hasMethod = obj.containsKey("method")

    if (hasId && obj.containsKey("result")) {
        val id = obj["id"]!!.asLongOrNull() ?: return null
        return Inbound.Response(id, obj["result"]!!)
    }

    if (hasId && obj.containsKey("error")) {
        val id = obj["id"]!!.asLongOrNull()
        val errorObj = obj["error"]!! as? JsonObject ?: return null
        val code = errorObj["code"]?.asIntOrNull() ?: return null
        val message = errorObj["message"]?.asStringOrNull() ?: return null
        return Inbound.Failure(id, RpcError(code, message, errorObj["data"]))
    }

    if (hasId && hasMethod) {
        // A request from the daemon: answer it, echoing the id verbatim.
        val method = obj["method"]!!.asStringOrNull() ?: return null
        return Inbound.Request(obj["id"]!!, method, obj["params"] ?: JsonNull)
    }

    if (hasMethod && obj.containsKey("params")) {
        val method = obj["method"]!!.asStringOrNull() ?: return null
        return Inbound.Notification(method, obj["params"]!!)
    }

    return null
}

/** Encodes a response to a daemon request, echoing [id] exactly as received. */
fun encodeResponse(id: JsonElement, result: JsonElement): String {
    val obj = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }
    return ZdJson.encodeToString(JsonObject.serializer(), obj)
}

/** Encodes an error response to a daemon request, echoing [id] exactly as received. */
fun encodeErrorResponse(id: JsonElement, code: Int, message: String): String {
    val obj = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }
    return ZdJson.encodeToString(JsonObject.serializer(), obj)
}

private fun JsonElement.asLongOrNull(): Long? = try {
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLong()
} catch (e: Exception) {
    null
}

private fun JsonElement.asIntOrNull(): Int? = try {
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toInt()
} catch (e: Exception) {
    null
}

private fun JsonElement.asStringOrNull(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content
