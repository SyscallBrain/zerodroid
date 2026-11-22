package pt.tiago.zerodroid.ui.sop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import pt.tiago.zerodroid.data.sop.SopDraft
import pt.tiago.zerodroid.rpc.proto.SopGraphDto

/**
 * One port chip on a step card. [targets] are the steps it currently points at (for WaitsFor: the
 * steps this one waits for). [port] is the switch rule index for branches.
 */
data class PortChip(val role: PortRole, val port: Int?, val label: String, val targets: List<Int>) {
    val connected: Boolean get() = targets.isNotEmpty()
}

/** The chips of step [n], read from the daemon's graph wires (which include implicit fallthrough). */
fun portsFor(n: Int, draft: JsonObject, graph: SopGraphDto): List<PortChip> {
    val step = SopDraft.step(draft, n) ?: return emptyList()
    val flow = graph.wires.filter { it.wireClass == "flow" }
    fun outs(role: String) = flow.filter { it.fromStep == n && it.flowRole == role }.map { it.toStep }

    val chips = mutableListOf<PortChip>()
    val terminal = ((step["routing"] as? JsonObject)?.get("terminal") as? JsonPrimitive)?.booleanOrNull == true
    val next = outs("sequence").distinct()
    chips += PortChip(
        PortRole.Next, null,
        when {
            next.isNotEmpty() -> "next · ${next.joinToString(", ")}"
            terminal -> "ends here"
            else -> "next"
        },
        next,
    )
    SopDraft.switchRules(step).forEachIndexed { i, rule ->
        val name = (rule["name"] as? JsonPrimitive)?.content?.ifBlank { null } ?: "branch ${i + 1}"
        val goto = (rule["goto"] as? JsonPrimitive)?.content?.toIntOrNull()
        chips += PortChip(PortRole.Branch, i, if (goto != null) "$name · $goto" else name, listOfNotNull(goto))
    }
    val failTo = outs("failure").distinct()
    chips += when (val f = SopDraft.failure(step)) {
        is SopDraft.Failure.Retry -> PortChip(PortRole.OnFailure, null, "retries ${f.max}×", emptyList())
        else -> PortChip(PortRole.OnFailure, null, if (failTo.isNotEmpty()) "on failure · ${failTo.joinToString(", ")}" else "on failure", failTo)
    }
    val deps = flow.filter { it.toStep == n && it.flowRole == "dependency" }.map { it.fromStep }.distinct().sorted()
    chips += PortChip(PortRole.WaitsFor, null, if (deps.isEmpty()) "waits for" else "waits for ${deps.joinToString(", ")}", deps)
    return chips
}

/** The bottom-bar sentence while picking a target. */
fun connectSentence(m: EditorMode.Connect, draft: JsonObject): String = when (m.role) {
    PortRole.Next -> "After step ${m.from}, go to…"
    PortRole.OnFailure -> "If step ${m.from} fails, go to…"
    PortRole.WaitsFor -> "Step ${m.from} waits for…"
    PortRole.Branch -> {
        val rule = SopDraft.step(draft, m.from)?.let { SopDraft.switchRules(it) }?.getOrNull(m.port ?: -1)
        val name = (rule?.get("name") as? JsonPrimitive)?.content?.ifBlank { null } ?: "branch ${(m.port ?: 0) + 1}"
        "Branch '$name' of step ${m.from} goes to…"
    }
}
