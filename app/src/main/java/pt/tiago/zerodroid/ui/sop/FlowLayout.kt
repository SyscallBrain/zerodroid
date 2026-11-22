package pt.tiago.zerodroid.ui.sop

import pt.tiago.zerodroid.rpc.proto.GraphWireDto
import pt.tiago.zerodroid.rpc.proto.SopGraphDto

/**
 * The daemon's layout, transposed for a phone. The daemon assigns each node a column by longest
 * path from the triggers (triggers at col 0, the steps they feed from col 1) and a row within the
 * column. Here the column becomes the depth (rows going down) and steps in the same depth become
 * lanes side by side. Triggers always form depth 0 on their own.
 */
data class FlowSlot(val step: Int, val depth: Int, val lane: Int, val isTrigger: Boolean)

data class FlowPlan(
    val slots: List<FlowSlot>,
    val lanes: Int,
    val depths: Int,
    val cardWidth: Float,
    val canvasWidth: Float,
    val zoomable: Boolean,
)

const val FLOW_MARGIN = 16f
const val FLOW_LANE_GAP = 16f
const val FLOW_MIN_CARD = 148f

fun planFlow(graph: SopGraphDto, screenWidthDp: Float): FlowPlan {
    val kinds = graph.nodes.associate { it.step to (it.kind == "trigger") }
    val pos = graph.layout.positions.associateBy { it.step }
    val slots = mutableListOf<FlowSlot>()

    graph.nodes.filter { it.kind == "trigger" }.sortedBy { it.step }.forEachIndexed { i, n ->
        slots += FlowSlot(n.step, 0, i, true)
    }
    // A step no trigger reaches gets col 0; it joins depth 1 after the steps already there.
    val steps = graph.nodes.filter { kinds[it.step] != true }.map { n ->
        val p = pos[n.step]
        Triple(n.step, p?.col ?: 0, p?.row ?: 0)
    }
    steps.groupBy { maxOf(it.second, 1) }.forEach { (depth, group) ->
        group.sortedWith(compareBy({ if (it.second == 0) 1 else 0 }, { it.third }, { it.first }))
            .forEachIndexed { lane, s -> slots += FlowSlot(s.first, depth, lane, false) }
    }

    val lanes = slots.filter { !it.isTrigger }.groupBy { it.depth }.values.maxOfOrNull { it.size } ?: 1
    val depths = (slots.maxOfOrNull { it.depth } ?: 0) + 1
    val fit = (screenWidthDp - 2 * FLOW_MARGIN - (lanes - 1) * FLOW_LANE_GAP) / lanes
    return if (fit >= FLOW_MIN_CARD) {
        FlowPlan(slots, lanes, depths, fit, screenWidthDp, zoomable = false)
    } else {
        val width = 2 * FLOW_MARGIN + lanes * FLOW_MIN_CARD + (lanes - 1) * FLOW_LANE_GAP
        FlowPlan(slots, lanes, depths, FLOW_MIN_CARD, width, zoomable = true)
    }
}

/** A wire going up or sideways: drawn along the right edge so it never crosses cards. */
fun isBackEdge(from: FlowSlot, to: FlowSlot): Boolean = to.depth <= from.depth

enum class WireRole { Sequence, Dependency, Failure, Switch, Trigger, Data }

fun wireRole(w: GraphWireDto): WireRole = when {
    w.wireClass == "data" -> WireRole.Data
    else -> when (w.flowRole) {
        "dependency" -> WireRole.Dependency
        "failure" -> WireRole.Failure
        "switch" -> WireRole.Switch
        "trigger" -> WireRole.Trigger
        else -> WireRole.Sequence
    }
}
