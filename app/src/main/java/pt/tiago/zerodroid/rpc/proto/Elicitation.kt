package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Source: ElicitationRequest / ElicitationResponse — crates/zeroclaw-api/src/elicitation.rs
// (ACP `elicitation/create` subset; note the camelCase wire keys, unlike session/* methods).

/** Params of a daemon→client `elicitation/create` request. */
@Serializable
data class ElicitationRequestParams(
    @SerialName("sessionId") val sessionId: String = "",
    val mode: String = "form",
    val message: String = "",
    @SerialName("requestedSchema") val requestedSchema: JsonObject = JsonObject(emptyMap()),
)

/** One selectable option: [const] is the wire value ("choice-N"), [title] the label shown to the user. */
data class ElicitationOption(val const: String, val title: String)

/** The form a request asks for: [property] is "choice" (single) or "choices" (multi). */
data class ElicitationForm(
    val property: String,
    val multi: Boolean,
    val options: List<ElicitationOption>,
)

/** Extracts the options from a form-mode `requestedSchema` (`properties.choice.oneOf` / `properties.choices.items.anyOf`). */
fun parseElicitationForm(schema: JsonObject): ElicitationForm? {
    val props = schema["properties"]?.jsonObject ?: return null
    val single = props["choice"]?.jsonObject?.get("oneOf")?.jsonArray
    if (single != null) return ElicitationForm("choice", multi = false, options = single.mapNotNull { it.toOption() })
    val multi = props["choices"]?.jsonObject?.get("items")?.jsonObject?.get("anyOf")?.jsonArray
    if (multi != null) return ElicitationForm("choices", multi = true, options = multi.mapNotNull { it.toOption() })
    return null
}

private fun kotlinx.serialization.json.JsonElement.toOption(): ElicitationOption? {
    val obj = jsonObject
    val const = obj["const"]?.jsonPrimitive?.content ?: return null
    val title = obj["title"]?.jsonPrimitive?.content ?: const
    return ElicitationOption(const, title)
}

/** Builds the `result` of an `elicitation/create` response (ACP three-action model). */
object ElicitationResult {
    fun acceptSingle(property: String, const: String): JsonObject = buildJsonObject {
        put("action", "accept")
        put("content", buildJsonObject { put(property, const) })
    }

    fun acceptMulti(property: String, consts: List<String>): JsonObject = buildJsonObject {
        put("action", "accept")
        put("content", buildJsonObject { put(property, buildJsonArray { consts.forEach { add(JsonPrimitive(it)) } }) })
    }

    fun decline(): JsonObject = buildJsonObject { put("action", "decline") }
    fun cancel(): JsonObject = buildJsonObject { put("action", "cancel") }
}
