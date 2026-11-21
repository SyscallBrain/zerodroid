package pt.tiago.zerodroid.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Records every call; answers from [handlers] by method, `{}` otherwise. */
class FakeCaller(var handlers: MutableMap<String, (JsonElement?) -> JsonElement> = mutableMapOf()) : RpcCaller {
    data class Sent(val method: String, val params: JsonElement?, val timeoutMs: Long) {
        val json: String get() = params?.toString() ?: "null"
    }

    val sent = mutableListOf<Sent>()

    override suspend fun call(method: String, params: JsonElement?, timeoutMs: Long): JsonElement {
        sent += Sent(method, params, timeoutMs)
        return handlers[method]?.invoke(params) ?: JsonObject(emptyMap())
    }

    fun on(method: String, json: String) { handlers[method] = { ZdJson.parseToJsonElement(json) } }
    fun fail(method: String, code: Int, message: String = "err") { handlers[method] = { throw RpcException(RpcError(code, message)) } }
    fun methods() = sent.map { it.method }
}
