package pt.tiago.zerodroid.data.sop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pure edits over a SOP kept as the raw JSON the daemon returned. Every function returns a new
 * object and keeps every key it does not touch, so fields the app does not model (`pos`,
 * `decision`, `schema`, pinned call outputs, future additions) survive a save unchanged.
 *
 * Shapes (crates/zeroclaw-runtime/src/sop/step_contract.rs): `routing` = {when?, next?, terminal?,
 * depends_on?, switch?: [{name, when?, goto?}]}; `on_failure` = "fail" | {"retry":{"max":N}} |
 * {"goto":{"step":N}}; triggers are objects tagged by "type".
 */
object SopDraft {
    sealed interface Failure {
        data object Fail : Failure
        data class Retry(val max: Int) : Failure
        data class Goto(val step: Int) : Failure
    }

    fun blankStep(number: Int): JsonObject = buildJsonObject {
        put("number", number)
        put("title", "")
        put("body", "")
        put("kind", "execute")
        put("requires_confirmation", false)
        putJsonArray("suggested_tools") {}
    }

    /** Mirrors the web editor's `blankSop`. */
    fun blank(name: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", "")
        put("version", "1.0.0")
        put("priority", "normal")
        put("execution_mode", "supervised")
        putJsonArray("triggers") { add(buildJsonObject { put("type", "manual") }) }
        putJsonArray("steps") { add(blankStep(1)) }
        put("cooldown_secs", 0)
        put("max_concurrent", 1)
        put("admission_policy", "parallel")
        put("max_pending_approvals", 0)
        put("deterministic", false)
    }

    fun name(d: JsonObject): String = d.str("name") ?: ""

    fun steps(d: JsonObject): List<JsonObject> = d.arr("steps").mapNotNull { it as? JsonObject }

    fun step(d: JsonObject, n: Int): JsonObject? = steps(d).firstOrNull { it.number() == n }

    fun set(d: JsonObject, key: String, value: JsonElement?): JsonObject = d.with(key, value)

    fun setStep(d: JsonObject, n: Int, path: List<String>, value: JsonElement?): JsonObject =
        mapStep(d, n) { s -> tidy(setPath(s, path, value)) }

    fun addStep(d: JsonObject): Pair<JsonObject, Int> {
        val n = nextNumber(d)
        return withSteps(d, steps(d) + blankStep(n)) to n
    }

    fun duplicateStep(d: JsonObject, n: Int): Pair<JsonObject, Int> {
        val src = step(d, n) ?: return d to n
        val m = nextNumber(d)
        val copy = JsonObject(
            src.toMutableMap().apply {
                remove("routing"); remove("on_failure")
                put("number", JsonPrimitive(m))
                put("title", JsonPrimitive("${src.str("title") ?: ""} (copy)"))
            },
        )
        return withSteps(d, steps(d) + copy) to m
    }

    /** Removes step [n] and every reference to it, so nothing points at a step that is gone. */
    fun deleteStep(d: JsonObject, n: Int): JsonObject =
        withSteps(d, steps(d).filter { it.number() != n }.map { dropReferences(it, n) })

    /** Swaps array positions like the web editor; step numbers are unchanged. */
    fun moveStep(d: JsonObject, n: Int, dir: Int): JsonObject {
        val list = steps(d).toMutableList()
        val i = list.indexOfFirst { it.number() == n }
        val j = i + dir
        if (i < 0 || j !in list.indices) return d
        list[i] = list[j].also { list[j] = list[i] }
        return withSteps(d, list)
    }

    fun failure(step: JsonObject): Failure {
        val f = step["on_failure"] ?: return Failure.Fail
        val o = f as? JsonObject ?: return Failure.Fail
        (o["retry"] as? JsonObject)?.int("max")?.let { return Failure.Retry(it) }
        (o["goto"] as? JsonObject)?.int("step")?.let { return Failure.Goto(it) }
        return Failure.Fail
    }

    fun setFailure(d: JsonObject, n: Int, f: Failure): JsonObject = mapStep(d, n) { s ->
        s.with(
            "on_failure",
            when (f) {
                Failure.Fail -> null
                is Failure.Retry -> buildJsonObject { putJsonObject("retry") { put("max", f.max) } }
                is Failure.Goto -> buildJsonObject { putJsonObject("goto") { put("step", f.step) } }
            },
        )
    }

    fun triggers(d: JsonObject): List<JsonObject> = d.arr("triggers").mapNotNull { it as? JsonObject }

    fun setTriggers(d: JsonObject, t: List<JsonObject>): JsonObject = d.with("triggers", JsonArray(t))

    fun switchRules(step: JsonObject): List<JsonObject> =
        ((step["routing"] as? JsonObject)?.arr("switch") ?: emptyList()).mapNotNull { it as? JsonObject }

    fun setSwitchRules(d: JsonObject, n: Int, rules: List<JsonObject>): JsonObject =
        setStep(d, n, listOf("routing", "switch"), rules.takeIf { it.isNotEmpty() }?.let { JsonArray(it) })

    fun toolNames(d: JsonObject): Set<String> = steps(d).flatMapTo(sortedSetOf()) { s ->
        s.arr("suggested_tools").mapNotNull { (it as? JsonPrimitive)?.content } +
            s.arr("calls").mapNotNull { (it as? JsonObject)?.str("tool") }
    }

    fun hasManualTrigger(d: JsonObject): Boolean = triggers(d).any { it.str("type") == "manual" }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun nextNumber(d: JsonObject) = (steps(d).mapNotNull { it.number() }.maxOrNull() ?: 0) + 1

    private fun withSteps(d: JsonObject, s: List<JsonObject>) = d.with("steps", JsonArray(s))

    private fun mapStep(d: JsonObject, n: Int, f: (JsonObject) -> JsonObject) =
        withSteps(d, steps(d).map { if (it.number() == n) f(it) else it })

    private fun dropReferences(s: JsonObject, n: Int): JsonObject {
        var out = s
        val routing = s["routing"] as? JsonObject
        if (routing != null) {
            var r = routing
            if (r.int("next") == n) r = r.with("next", null)
            val deps = r.arr("depends_on").filterNot { (it as? JsonPrimitive)?.intOrNull == n }
            r = r.with("depends_on", deps.takeIf { it.isNotEmpty() }?.let { JsonArray(it) })
            if (r.containsKey("switch")) {
                r = r.with(
                    "switch",
                    JsonArray(r.arr("switch").map { rule -> if (rule is JsonObject && rule.int("goto") == n) rule.with("goto", null) else rule }),
                )
            }
            out = out.with("routing", r)
        }
        if (failure(out) == Failure.Goto(n)) out = out.with("on_failure", null)
        return tidy(out)
    }

    /** Drops a `routing` object left empty by an edit, as the daemon omits a default routing. */
    private fun tidy(s: JsonObject): JsonObject {
        val r = s["routing"] as? JsonObject ?: return s
        return if (r.isEmpty()) s.with("routing", null) else s
    }

    private fun setPath(o: JsonObject, path: List<String>, value: JsonElement?): JsonObject {
        val head = path.first()
        if (path.size == 1) return o.with(head, value)
        val child = o[head] as? JsonObject ?: JsonObject(emptyMap())
        val next = setPath(child, path.drop(1), value)
        return o.with(head, if (next.isEmpty() && value == null) null else next)
    }

    private fun JsonObject.with(key: String, value: JsonElement?): JsonObject =
        JsonObject(toMutableMap().apply { if (value == null) remove(key) else put(key, value) })

    private fun JsonObject.number(): Int? = int("number")
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()
}
