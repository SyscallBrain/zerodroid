package pt.tiago.zerodroid.rpc.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import pt.tiago.zerodroid.rpc.ZdJson

class SopsProtoTest {
    private val graphJson = """{
      "nodes":[
        {"step":0,"title":"manual","kind":"trigger","trigger_index":0,"inputs":[],"outputs":[{"class":"flow","name":"out","required":false}]},
        {"step":1,"title":"Forecast","subtitle":"weather","inputs":[],"outputs":[{"class":"data","name":"chuva_mm","data_type":"number","required":true}]},
        {"step":2,"title":"Rain?","inputs":[],"outputs":[]}
      ],
      "wires":[
        {"class":"flow","from_step":1,"to_step":2,"flow_role":"switch","from_pin":"yes"},
        {"class":"data","from_step":1,"to_step":2,"from_pin":"chuva_mm","to_pin":"body"}
      ],
      "diagnostics":[{"severity":"warning","step":2,"message":"no body"}],
      "layout":{"positions":[{"step":1,"col":0,"row":0},{"step":2,"col":1,"row":0,"x":10.5,"y":3}],"columns":2,"rows":1,
        "geometry":{"node_w":210,"node_h":84,"col_gap":130,"row_gap":46,"origin":24}}
    }"""

    @Test
    fun graphDecodesClassAndRole() {
        val g = ZdJson.decodeFromString(SopGraphDto.serializer(), graphJson)
        assertEquals("flow", g.wires[0].wireClass)
        assertEquals("switch", g.wires[0].flowRole)
        assertEquals("yes", g.wires[0].fromPin)
        assertNull(g.wires[1].flowRole)
        assertEquals("trigger", g.nodes[0].kind)
        assertEquals("step", g.nodes[1].kind)
        assertEquals("number", g.nodes[1].outputs[0].dataType)
        assertEquals(1, g.layout.positions[1].col)
        assertEquals(10.5, g.layout.positions[1].x!!, 0.0)
        assertEquals("no body", g.diagnostics[0].message)
    }

    @Test
    fun overlayDecodesToolCalls() {
        val o = ZdJson.decodeFromString(
            RunOverlayDto.serializer(),
            """{"run_id":"r1","sop_name":"s","status":"running","current_step":2,"total_steps":3,"waiting":false,"paused":false,
               "nodes":[{"step":1,"state":"completed","tool_calls":[{"index":0,"tool":"weather","args":{"q":"Lisboa"},"success":true,"output":"6.4"}]},
                        {"step":2,"state":"active"}]}""",
        )
        assertEquals("weather", o.nodes[0].toolCalls[0].tool)
        assertEquals("completed", o.nodes[0].state)
        assertEquals(emptyList<StepToolCallDto>(), o.nodes[1].toolCalls)
        assertFalse(o.waiting)
    }

    @Test
    fun registryDecodesOperatorsAndFields() {
        val r = ZdJson.decodeFromString(
            TriggerSourceRegistryDto.serializer(),
            """{"sources":["manual","cron"],
               "bound":[{"source":"cron","fields":[{"name":"expression","options":[],"multi":false,"kind":"expression"}]}],
               "channels":[{"channel":"telegram","aliases":[{"alias":"main","enabled":true}],"configured":true,"setup_path":"channels.telegram"}],
               "operators":[{"token":">=","label":"at least"},{"token":">","label":"greater"}]}""",
        )
        assertEquals(listOf(">=", ">"), r.operators.map { it.token })
        assertEquals("expression", r.bound[0].fields[0].kind)
        assertEquals("main", r.channels[0].aliases[0].alias)
    }

    @Test
    fun validateAndRunsDecode() {
        val v = ZdJson.decodeFromString(ValidateDto.serializer(), """{"ok":false,"blocking":["step 3: empty"],"warnings":[]}""")
        assertEquals(listOf("step 3: empty"), v.blocking)
        val runs = ZdJson.decodeFromString(
            SopRunsDto.serializer(),
            """{"runs":[{"run_id":"a","sop_name":"s","status":"completed","current_step":3,"total_steps":3,"started_at":"t","completed_at":null,"trigger_source":"manual","active":false}]}""",
        )
        assertEquals("a", runs.runs[0].runId)
        assertEquals(3, runs.runs[0].totalSteps)
    }
}
