package pt.tiago.zerodroid.ui.sop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.proto.GraphLayoutDto
import pt.tiago.zerodroid.rpc.proto.GraphNodeDto
import pt.tiago.zerodroid.rpc.proto.GraphWireDto
import pt.tiago.zerodroid.rpc.proto.NodePositionDto
import pt.tiago.zerodroid.rpc.proto.SopGraphDto

class FlowLayoutTest {
    private val trig = 1_000_000

    /**
     * steps: step number → daemon (col, row). As the daemon lays it out (zeroclaw-runtime sop/graph.rs
     * `layout_graph`): trigger nodes sit at col 0 and steps they feed start at col 1.
     */
    private fun graph(vararg steps: Pair<Int, Pair<Int, Int>>): SopGraphDto {
        val nodes = steps.map { GraphNodeDto(step = it.first) } + GraphNodeDto(step = trig, kind = "trigger")
        val pos = steps.map { NodePositionDto(it.first, it.second.first, it.second.second) } + NodePositionDto(trig, 0, 0)
        return SopGraphDto(nodes = nodes, layout = GraphLayoutDto(positions = pos))
    }

    @Test
    fun linearSopIsOneLaneFullWidth() {
        val p = planFlow(graph(1 to (1 to 0), 2 to (2 to 0), 3 to (3 to 0)), 360f)
        assertEquals(1, p.lanes)
        assertEquals(328f, p.cardWidth, 0.01f)
        assertEquals(360f, p.canvasWidth, 0.01f)
        assertFalse(p.zoomable)
        assertEquals(FlowSlot(2, depth = 2, lane = 0, isTrigger = false), p.slots.first { it.step == 2 })
    }

    @Test
    fun twoLanesSplitWidth() {
        val p = planFlow(graph(1 to (1 to 0), 2 to (2 to 0), 3 to (2 to 1)), 360f)
        assertEquals(2, p.lanes)
        assertEquals(156f, p.cardWidth, 0.01f)
        assertFalse(p.zoomable)
    }

    @Test
    fun threeLanesOn360BecomeZoomable() {
        val p = planFlow(graph(1 to (1 to 0), 2 to (2 to 0), 3 to (2 to 1), 4 to (2 to 2)), 360f)
        assertEquals(3, p.lanes)
        assertEquals(148f, p.cardWidth, 0.01f)
        assertEquals(508f, p.canvasWidth, 0.01f)
        assertTrue(p.zoomable)
    }

    @Test
    fun triggersExcludedFromLaneCount() {
        val p = planFlow(graph(1 to (1 to 0)), 360f)
        assertEquals(1, p.lanes)
        assertTrue(p.slots.first { it.step == trig }.isTrigger)
        assertEquals(2, p.depths)
    }

    @Test
    fun orphanStepAtColZeroJoinsFirstStepRow() {
        // A step no trigger reaches gets col 0 (next to the triggers, row 1 there). It must never
        // share the trigger row: it joins depth 1, in the lane after the steps already there.
        val p = planFlow(graph(1 to (1 to 0), 2 to (2 to 0), 5 to (0 to 1)), 360f)
        assertEquals(0, p.slots.first { it.isTrigger }.depth)
        assertEquals(FlowSlot(1, 1, 0, false), p.slots.first { it.step == 1 })
        assertEquals(FlowSlot(5, 1, 1, false), p.slots.first { it.step == 5 })
        assertEquals(2, p.lanes)
    }

    @Test
    fun backEdgeDetected() {
        val a = FlowSlot(1, 1, 0, false)
        val b = FlowSlot(2, 2, 0, false)
        assertTrue(isBackEdge(b, a))
        assertTrue(isBackEdge(a, a.copy(step = 9)))
        assertFalse(isBackEdge(a, b))
    }

    @Test
    fun wireRoles() {
        assertEquals(WireRole.Data, wireRole(GraphWireDto("data", 1, 2, flowRole = "sequence")))
        assertEquals(WireRole.Sequence, wireRole(GraphWireDto("flow", 1, 2)))
        assertEquals(WireRole.Failure, wireRole(GraphWireDto("flow", 1, 2, flowRole = "failure")))
        assertEquals(WireRole.Dependency, wireRole(GraphWireDto("flow", 1, 2, flowRole = "dependency")))
        assertEquals(WireRole.Switch, wireRole(GraphWireDto("flow", 1, 2, flowRole = "switch")))
        assertEquals(WireRole.Trigger, wireRole(GraphWireDto("flow", 0, 1, flowRole = "trigger")))
    }
}
