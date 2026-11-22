package pt.tiago.zerodroid.ui.sop

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.proto.GraphWireDto
import pt.tiago.zerodroid.rpc.proto.SopGraphDto

class PortModelTest {
    private val draft = ZdJson.parseToJsonElement(
        """{"name":"s","steps":[
          {"number":1,"on_failure":{"retry":{"max":2}}},
          {"number":2,"routing":{"switch":[{"name":"yes","goto":3},{"name":"no"}]}},
          {"number":3},{"number":4,"routing":{"terminal":true}},{"number":5}]}""",
    ).jsonObject
    private val graph = SopGraphDto(
        wires = listOf(
            GraphWireDto("flow", 1_000_000, 1, flowRole = "trigger"),
            GraphWireDto("flow", 1, 2, flowRole = "sequence"),
            GraphWireDto("flow", 2, 3, flowRole = "switch", fromPin = "yes"),
            GraphWireDto("flow", 3, 5, flowRole = "dependency"),
            GraphWireDto("flow", 4, 5, flowRole = "dependency"),
            GraphWireDto("flow", 3, 4, flowRole = "failure"),
            GraphWireDto("data", 1, 2),
        ),
    )

    private fun chips(step: Int) = portsFor(step, draft, graph)

    @Test
    fun nextAndRetry() {
        val c = chips(1)
        assertEquals(PortChip(PortRole.Next, null, "next · 2", listOf(2)), c.first { it.role == PortRole.Next })
        assertEquals(PortChip(PortRole.OnFailure, null, "retries 2×", emptyList()), c.first { it.role == PortRole.OnFailure })
        assertEquals(PortChip(PortRole.WaitsFor, null, "waits for", emptyList()), c.first { it.role == PortRole.WaitsFor })
    }

    @Test
    fun branchesInRuleOrder() {
        val b = chips(2).filter { it.role == PortRole.Branch }
        assertEquals(listOf(PortChip(PortRole.Branch, 0, "yes · 3", listOf(3)), PortChip(PortRole.Branch, 1, "no", emptyList())), b)
    }

    @Test
    fun failureGotoAndFanIn() {
        assertEquals(PortChip(PortRole.OnFailure, null, "on failure · 4", listOf(4)), chips(3).first { it.role == PortRole.OnFailure })
        assertEquals(PortChip(PortRole.WaitsFor, null, "waits for 3, 4", listOf(3, 4)), chips(5).first { it.role == PortRole.WaitsFor })
    }

    @Test
    fun terminalStepShowsEndsHere() {
        assertEquals(PortChip(PortRole.Next, null, "ends here", emptyList()), chips(4).first { it.role == PortRole.Next })
    }

    @Test
    fun connectSentences() {
        assertEquals("After step 2, go to…", connectSentence(EditorMode.Connect(2, PortRole.Next), draft))
        assertEquals("If step 1 fails, go to…", connectSentence(EditorMode.Connect(1, PortRole.OnFailure), draft))
        assertEquals("Step 5 waits for…", connectSentence(EditorMode.Connect(5, PortRole.WaitsFor), draft))
        assertEquals("Branch 'no' of step 2 goes to…", connectSentence(EditorMode.Connect(2, PortRole.Branch, 1), draft))
    }
}
